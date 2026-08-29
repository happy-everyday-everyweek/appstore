package me.spica27.spicamusic.store.gitlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 镜像调度器 v2：竞速（hedged request）与失速检测（吞吐口径）单元测试（规格 §6.3）。
 *
 * 用自建 ServerSocket 起本地源（仅依赖 java.base，任何 JDK 可编译运行）：
 * - 竞速：首选源首字节延迟 >400ms → 第二源胜出，下载耗时显著低于「首字节超时 1500ms + 换源」路径；
 * - 失速：首选源赢得首字节后以 1B/800ms 慢慢吐（吞吐 << 32KB/s）→ 5s 看门狗触发 → 断点续传切下一源。
 * 预置新鲜探测结果跳过会话探测，保证测试只覆盖竞速/失速路径。
 */
class MirrorSchedulerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<MiniHttpServer>()
    private lateinit var stateStore: MirrorStateStore

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Before
    fun setUp() {
        stateStore = MirrorStateStore(tmp.newFolder())
    }

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        servers.clear()
    }

    /** 本地源：body 固定、支持 Range 续传、可选首字节延迟，并统计请求数 */
    private data class Source(
        val prefix: String,
        val reqCount: AtomicInteger,
        val probeCount: AtomicInteger,
    )

    /** 起一个本地源：body 固定、支持 Range 续传、可选首字节延迟 */
    private fun rangeServer(
        body: ByteArray,
        firstByteDelayMs: Long = 0,
    ): Source {
        val counter = AtomicInteger()
        val probeCounter = AtomicInteger()
        val s = MiniHttpServer(body, firstByteDelayMs = firstByteDelayMs, counter = counter, probeCounter = probeCounter)
        servers.add(s)
        return Source("http://127.0.0.1:${s.port}/", counter, probeCounter)
    }

    /** 慢吐源：先发首字节赢得竞速，然后以 1B/trickleMs 慢慢吐，模拟低吞吐失速 */
    private fun trickleServer(
        body: ByteArray,
        trickleMs: Long,
    ): Source {
        val counter = AtomicInteger()
        val probeCounter = AtomicInteger()
        val s = MiniHttpServer(body, trickleMs = trickleMs, counter = counter, probeCounter = probeCounter)
        servers.add(s)
        return Source("http://127.0.0.1:${s.port}/", counter, probeCounter)
    }

    /** 预置新鲜探测结果（probedAt=now），跳过会话探测，聚焦竞速/失速路径 */
    private fun scheduler(vararg mirrors: Mirror): MirrorScheduler {
        val snap =
            MirrorStateSnapshot(
                probedAt = System.currentTimeMillis(),
                mirrors =
                    mirrors.associate {
                        it.id to MirrorStateEntry(latencyMs = 1, lastOkAt = System.currentTimeMillis())
                    },
            )
        stateStore.save(snap)
        return MirrorScheduler(client, stateStore, mirrors.toList())
    }

    /** 空状态调度器：首轮下载会触发会话探测（验收3：每会话探测≤1轮） */
    private fun freshScheduler(vararg mirrors: Mirror): MirrorScheduler = MirrorScheduler(client, stateStore, mirrors.toList())

    private fun mirror(
        id: String,
        prefix: String,
    ) = Mirror(id = id, name = id, prefix = prefix)

    @Test
    fun `竞速 首选源首字节延迟超过400ms 第二源胜出且总耗时小于首字节超时路径`() {
        val bodyA = ByteArray(64 * 1024) { 'A'.code.toByte() }
        val bodyB = ByteArray(64 * 1024) { 'B'.code.toByte() }
        val a = rangeServer(bodyA, firstByteDelayMs = 600)
        val b = rangeServer(bodyB, firstByteDelayMs = 0)
        val s = scheduler(mirror("a", a.prefix), mirror("b", b.prefix))
        val dest = tmp.newFile().apply { delete() }

        val t0 = System.currentTimeMillis()
        runBlocking { s.download("dist/app/index.v2.json", dest, null, {}, false) }
        val elapsed = System.currentTimeMillis() - t0

        // 第二源（无延迟）胜出 → 内容为 B；且总耗时远小于「首选超时 1500ms 后换源」路径
        assertArrayEquals("竞速胜者应返回第二源内容", bodyB, dest.readBytes())
        assertTrue("竞速应 < 首字节超时路径（实际 ${elapsed}ms）", elapsed < 1200)
        assertTrue("首选源应收到请求", a.reqCount.get() > 0)
        assertTrue("第二源应收到请求", b.reqCount.get() > 0)
    }

    @Test
    fun `失速 首选源低吞吐超过5秒 看门狗触发并断点续传切换到下一候选源`() {
        val bodyC = ByteArray(64 * 1024) { 'C'.code.toByte() }
        val slowA = trickleServer(bodyC, trickleMs = 800) // 1B/800ms → 吞吐远低于 32KB/s
        val slowB = trickleServer(bodyC, trickleMs = 800)
        val fastC = rangeServer(bodyC, firstByteDelayMs = 0)
        val s = scheduler(mirror("a", slowA.prefix), mirror("b", slowB.prefix), mirror("c", fastC.prefix))
        val dest = tmp.newFile().apply { delete() }

        val t0 = System.currentTimeMillis()
        runBlocking { s.download("dist/app/index.v2.json", dest, null, {}, false) }
        val elapsed = System.currentTimeMillis() - t0

        // 慢源（a）赢得首字节后被看门狗判失速 → 第三候选（c，支持 Range）续传完成
        assertArrayEquals("断点续传后文件应与完整内容一致", bodyC, dest.readBytes())
        assertTrue("失速检测需要等待 ~5s 窗口（实际 ${elapsed}ms）", elapsed >= 5000)
        assertTrue("失速看门狗应在 5s 窗口内触发，而非等调用超时（实际 ${elapsed}ms）", elapsed < 15000)
        assertTrue("第三候选源应收到续传请求", fastC.reqCount.get() > 0)
    }

    @Test
    fun `验收3 会话内镜像探测仅1轮 第二次下载命中TTL缓存不再探测`() {
        val body = ByteArray(32 * 1024) { 'X'.code.toByte() }
        val a = rangeServer(body)
        val b = rangeServer(body)
        val s = freshScheduler(mirror("a", a.prefix), mirror("b", b.prefix))

        runBlocking { s.download("dist/app/index.v2.json", tmp.newFile().apply { delete() }, null, {}, false) }
        val probesAfterFirst = a.probeCount.get() + b.probeCount.get()
        assertEquals("首轮应探测全部镜像各一次（实际 $probesAfterFirst）", 2, probesAfterFirst)

        runBlocking { s.download("dist/app/index.v2.json", tmp.newFile().apply { delete() }, null, {}, false) }
        val probesAfterSecond = a.probeCount.get() + b.probeCount.get()
        assertEquals("第二次下载命中 TTL 缓存，探测不再发生（验收3：每会话≤1轮）", probesAfterFirst, probesAfterSecond)
    }

    @Test
    fun `并发下载只触发一轮全量探测 single-flight`() {
        val body = ByteArray(32 * 1024) { 'Z'.code.toByte() }
        val a = rangeServer(body)
        val b = rangeServer(body)
        val s = freshScheduler(mirror("a", a.prefix), mirror("b", b.prefix))
        val dests = List(4) { tmp.newFile().apply { delete() } }
        runBlocking {
            coroutineScope {
                dests
                    .map { d ->
                        async(Dispatchers.IO) {
                            runCatching { s.download("dist/app/index.v2.json", d, null, {}, false) }
                        }
                    }.awaitAll()
            }
        }
        val total = a.probeCount.get() + b.probeCount.get()
        assertTrue(
            "并发首下应共享一轮探测（每镜像各1次=2），实际 $total——single-flight 可能失效",
            total == 2,
        )
    }

    /** 一律返回指定错误码的源（默认 404）。 */
    private fun serverError(status: Int = 404): Source {
        val counter = AtomicInteger()
        val probeCounter = AtomicInteger()
        val s = MiniHttpServer(ByteArray(0), failStatus = status, counter = counter, probeCounter = probeCounter)
        servers.add(s)
        return Source("http://127.0.0.1:${s.port}/", counter, probeCounter)
    }

    /** 收到带偏移的 Range 请求仍回 200 全量（模拟源不支持 Range 续传）。 */
    private fun ignoreRangeServer(body: ByteArray): Source {
        val counter = AtomicInteger()
        val probeCounter = AtomicInteger()
        val s = MiniHttpServer(body, ignoreRange = true, counter = counter, probeCounter = probeCounter)
        servers.add(s)
        return Source("http://127.0.0.1:${s.port}/", counter, probeCounter)
    }

    @Test
    fun `全部候选源404时抛MirrorNotFound`() {
        val a = serverError(404)
        val b = serverError(404)
        val s = scheduler(mirror("a", a.prefix), mirror("b", b.prefix))
        val dest = tmp.newFile().apply { delete() }
        var thrown: Throwable? = null
        try {
            runBlocking { s.download("dist/app/index.v2.json", dest, null, {}, false) }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("全部候选 404 应判为资产不存在 MirrorNotFound，实际 $thrown", thrown is MirrorNotFound)
    }

    @Test
    fun `混合404与非404失败不误判为资产不存在`() {
        val a = serverError(404)
        val b = serverError(500) // 非 404 的服务端错误：资产可能存在，只是取不到
        val s = scheduler(mirror("a", a.prefix), mirror("b", b.prefix))
        val dest = tmp.newFile().apply { delete() }
        var thrown: Throwable? = null
        try {
            runBlocking { s.download("dist/app/index.v2.json", dest, null, {}, false) }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(
            "存在非 404 失败时不得判为 MirrorNotFound，避免误清健康 v2 缓存；实际 $thrown",
            thrown != null && thrown !is MirrorNotFound,
        )
    }

    @Test
    fun `源忽略Range时清空半成品从头重下`() {
        val body = ByteArray(64 * 1024) { 'R'.code.toByte() }
        val a = ignoreRangeServer(body)
        val s = scheduler(mirror("a", a.prefix))
        val dest = tmp.newFile()
        dest.writeBytes(ByteArray(10)) // 预置 10B 半成品 → resumeFrom>0
        var thrown: Throwable? = null
        try {
            runBlocking { s.download("dist/app/index.v2.json", dest, null, {}, false) }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("单源且忽略 Range 最终应失败抛出，实际 $thrown", thrown != null)
        assertTrue("restartRequired 必须删除错误偏移的半成品 dest", !dest.exists() || dest.length() == 0L)
    }
}

/**
 * 极简本地 HTTP 源：仅依赖 java.base 的 ServerSocket。
 *
 * 支持三种形态（由构造参数组合）：
 * - 普通/Range：firstByteDelayMs 大于 0 时在写响应前 sleep（模拟首字节延迟）；
 * - Range 续传：解析 Range 头，回 206 + Content-Range 并从指定偏移写 body；
 * - trickle 慢吐：trickleMs 大于 0 时立即回 200，然后逐字节慢吐（模拟低吞吐失速）。
 * 客户端中断（竞速败者 / 失速取消）导致的写异常统一吞掉。
 */
private class MiniHttpServer(
    private val body: ByteArray,
    private val firstByteDelayMs: Long = 0,
    private val trickleMs: Long = 0,
    private val failStatus: Int = 0,
    private val ignoreRange: Boolean = false,
    private val counter: AtomicInteger,
    private val probeCounter: AtomicInteger,
) {
    val port: Int

    private val server: ServerSocket

    init {
        server = ServerSocket(0)
        port = server.localPort
        Thread {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                Thread { handle(socket) }.apply {
                    isDaemon = true
                    start()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { server.close() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val headers = readHeaders(input) ?: return
                counter.incrementAndGet()
                val rangeLine = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                val rangeValue = rangeLine?.substringAfter(":")?.trim()
                // 会话级探测请求带 Range: bytes=0-0（验收3：每会话探测≤1轮）
                if (rangeValue == "bytes=0-0") probeCounter.incrementAndGet()
                val from = rangeLine?.let { parseRangeFrom(it) }

                if (failStatus != 0) {
                    writeHead(output, "$failStatus Error", "Content-Length: 0")
                    return
                }
                val effFrom = if (ignoreRange) null else from

                if (trickleMs > 0 && effFrom == null) {
                    // trickle：立即回 200，逐字节慢吐
                    writeHead(output, "200 OK", "Content-Type: application/octet-stream\r\nContent-Length: ${body.size}")
                    var i = 0
                    while (i < body.size) {
                        output.write(body[i].toInt())
                        output.flush()
                        i++
                        if (i < body.size) Thread.sleep(trickleMs)
                    }
                    return
                }
                if (firstByteDelayMs > 0) Thread.sleep(firstByteDelayMs)
                if (effFrom != null) {
                    val len = body.size - effFrom
                    writeHead(
                        output,
                        "206 Partial Content",
                        "Content-Range: bytes $effFrom-${body.size - 1}/${body.size}\r\nContent-Length: $len",
                    )
                    output.write(body, effFrom, len)
                    output.flush()
                } else {
                    writeHead(output, "200 OK", "Content-Type: application/octet-stream\r\nContent-Length: ${body.size}")
                    output.write(body)
                    output.flush()
                }
            }
        } catch (e: IOException) {
            // 客户端中断（竞速败者 / 失速取消），忽略
        }
    }

    private fun writeHead(
        output: OutputStream,
        status: String,
        extraHeaders: String,
    ) {
        val head =
            "HTTP/1.1 $status\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "$extraHeaders\r\n" +
                "Connection: close\r\n\r\n"
        output.write(head.toByteArray())
        output.flush()
    }

    private fun readHeaders(input: InputStream): List<String>? {
        val sb = StringBuilder()
        val buf = ByteArray(1024)
        while (true) {
            val n =
                try {
                    input.read(buf)
                } catch (e: IOException) {
                    return null
                }
            if (n <= 0) return null
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            if (sb.contains("\r\n\r\n")) break
            if (sb.length > 16 * 1024) return null
        }
        val head = sb.substring(0, sb.indexOf("\r\n\r\n"))
        return head.split("\r\n")
    }

    private fun parseRangeFrom(line: String): Int? {
        val value = line.substringAfter(":", "").trim()
        if (!value.startsWith("bytes=")) return null
        val range = value.removePrefix("bytes=").substringBefore(",").trim()
        val from = range.substringBefore("-").trim().toIntOrNull() ?: return null
        if (from < 0 || from >= body.size) return null
        return from
    }
}
