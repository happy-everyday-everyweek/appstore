package me.spica27.spicamusic.store.gitlink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.spica27.spicamusic.store.DebugLog
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import okio.appendingSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 镜像调度器（v2：会话级选择 + 下载中竞速/自适应，替代 v1 每文件全量测速）。
 *
 * 与 v1 [SpeedTester] 12MB/6s 全量测速的关键差异：
 * - 测速退化为「会话首次请求」的一次 Range 0-0 轻探测（HEAD 等价，流量≈0），
 *   结果持久化，TTL 24h 内复用，命中缓存为 0 次探测；
 * - 对象下载按「top-3 + GitHub 直连」候选顺序，首选源先发、400ms 内未出首字节
 *   并发第二源竞速（hedged request），首字节到达即定胜者、取消其余；
 * - 下载中失速检测：吞吐 < [STALL_MIN_BPS]（32KB/s）持续 [STALL_GRACE_MS]（5s）
 *   → 看门狗取消当前源，断点续传切换下一候选源（Range 续传能力保留）；
 * - 保留 v1 防护：SHA-256 强校验、空文件防护、HTML 挑战页嗅探；
 * - raw.githubusercontent.com 直链仅路由到此前成功过 raw 的镜像（rawOk）。
 */
class MirrorScheduler(
    private val client: OkHttpClient,
    private val stateStore: MirrorStateStore,
    private val mirrors: List<Mirror> = Mirrors.DEFAULT,
) : ObjectFetcher {
    companion object {
        const val PROBE_TTL_MS = 24 * 60 * 60 * 1000L
        const val PROBE_RANGE = "bytes=0-0"
        const val FIRST_BYTE_TIMEOUT_MS = 1500L

        /** 竞速阈值：首选源 400ms 内未出首字节 → 并发第二源 */
        const val HEDGE_FIRST_BYTE_MS = 400L

        /** 一次竞速尝试内最多并发候选源数（首选 + 竞速第二源） */
        const val HEDGE_BATCH_SIZE = 2
        const val STALL_CHECK_MS = 500L
        const val STALL_GRACE_MS = 5000L

        /** 失速吞吐下限：低于 32KB/s 视为失速 */
        const val STALL_MIN_BPS = 32 * 1024L
        const val TOP_N = 3
        const val MAX_ATTEMPTS = 5
        const val FAIL_DEMOTE = 3
        const val DIRECT_ID = "github"
    }

    /** 会话内缓存的状态快照（含持久化结果） */
    @Volatile
    private var sessionState: MirrorStateSnapshot? = null

    /** 串行化 sessionState 的读-改-写：@Volatile 只保证可见性，多路并发下载同时更新会互相覆盖 */
    private val stateMutex = Mutex()

    /** 阶段回调用（供 UI 展示具体流程） */
    var onStage: ((String) -> Unit)? = null

    /** 会话级探测客户端：贴近规格 §6.3「500ms 超时」，留 callTimeout 1s 防误判 */
    private fun probeClient(): OkHttpClient =
        client
            .newBuilder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1000, TimeUnit.MILLISECONDS)
            .build()

    /**
     * 下载对象（manifest / index / icon / bundle 通用）。
     *
     * @param isRaw 是否为 raw.githubusercontent.com 直链（影响镜像路由）
     * @throws MirrorNotFound 全部候选源返回 404（v2 协议探测用）
     */
    override suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
        isRaw: Boolean,
    ): File {
        val t0 = System.currentTimeMillis()
        DebugLog.i("Download", "[v2] 开始下载：$url 目标=${dest.name} sha256=${expectedSha256?.take(8) ?: "无"} raw=$isRaw")
        val ranked = ensureProbed(url, isRaw)
        val candidates =
            buildList {
                addAll(ranked.take(TOP_N))
                // GitHub 直连腿（§6.3）：仅对 github.com/releases 类 URL 有效——「github」镜像的
                // prefix 是 https://github.com/，raw.githubusercontent.com 不在其代理范围，
                // raw 对象经 rawOk 镜像路由（§6.5），故不叠加直连。
                if (!isRaw) {
                    mirrors.firstOrNull { it.id == DIRECT_ID }?.takeIf { it !in this }?.let { add(it) }
                }
            }
        DebugLog.i("Download", "[v2] 候选源：${candidates.joinToString { it.name }}")
        var lastErr: Throwable = IOException("无可用镜像")
        var notFound404 = 0
        var otherFailures = 0
        var tried = 0
        var i = 0
        while (i < candidates.size && tried < MAX_ATTEMPTS) {
            val batch = candidates.subList(i, minOf(i + HEDGE_BATCH_SIZE, candidates.size))
            i += batch.size
            tried += batch.size
            try {
                val started = System.currentTimeMillis()
                val (result, winner, failed) = streamFromSources(batch, url, dest, dest.length(), onProgress)
                val blamed = winner ?: batch.first()
                when (result) {
                    is StreamResult.Completed -> {
                        if (dest.length() < 1) {
                            lastErr = IOException("下载文件为空（0B），疑似空响应")
                            otherFailures++
                            recordFail(blamed)
                            dest.delete()
                            continue
                        }
                        if (expectedSha256 != null && sha256(dest) != expectedSha256) {
                            lastErr = IOException("SHA-256 不一致：期望 ${expectedSha256.take(8)}…")
                            otherFailures++
                            recordFail(blamed)
                            dest.delete()
                            continue
                        }
                        recordSuccess(blamed, isRaw, started, dest.length())
                        val cost = System.currentTimeMillis() - t0
                        DebugLog.i("Download", "[v2] 镜像「${blamed.name}」成功 ${dest.length()} 字节，耗时 ${cost}ms")
                        return dest
                    }
                    is StreamResult.Aborted -> {
                        if (result.code == 404) notFound404++ else otherFailures++
                        lastErr = IOException("镜像组失败：${result.reason}")
                        if (result.restartRequired) dest.delete()
                        // 竞速批内每个失败源独立累计（修复：不只记 blamed 首个，保证连续失败≥3 降级准确）
                        (failed + blamed).distinct().forEach { recordFail(it) }
                    }
                }
            } catch (e: Exception) {
                lastErr = e
                otherFailures++
                batch.forEach { recordFail(it) }
                runCatching { dest.delete() }
            }
        }
        DebugLog.e("Download", "[v2] 全部候选源失败（尝试 $tried 个）：${lastErr.message}")
        // 仅当所有失败都是 404 才判定资产不存在：MirrorNotFound 会被上层当作「对方仍为 v1 形态」的权威结论
        throw if (tried > 0 && notFound404 > 0 && otherFailures == 0) {
            MirrorNotFound(url)
        } else {
            IOException("镜像全部失败（尝试 $tried 个）：${lastErr.message}", lastErr)
        }
    }

    /** 会话/持久化探测：TTL 内复用，否则并发轻探测一次并落盘 */
    private suspend fun ensureProbed(
        url: String,
        isRaw: Boolean,
    ): List<Mirror> {
        val now = System.currentTimeMillis()
        val state = sessionState ?: stateStore.load()
        if (state.probedAt > 0 && now - state.probedAt < PROBE_TTL_MS) {
            sessionState = state
            return orderCandidates(state, isRaw)
        }
        DebugLog.i("Download", "[v2] 会话首次探测全部镜像（Range 0-0，轻量）…")
        onStage?.invoke("正在探测镜像源…")
        val results =
            withContext(Dispatchers.IO) {
                coroutineScope {
                    mirrors.map { m -> async { m to probeLatency(m.prefix + url) } }.awaitAll()
                }
            }
        var updated = state.copy(probedAt = now)
        // 探测 URL 的通道决定本轮记录哪个可达性位（§6.5：release/raw 分别记录）
        results.forEach { (m, r) ->
            val prev = updated.mirrors[m.id] ?: MirrorStateEntry()
            updated =
                updated.copy(
                    mirrors =
                        updated.mirrors +
                            (
                                m.id to
                                    MirrorStateEntry(
                                        latencyMs = r.latencyMs,
                                        lastOkAt = if (r.ok) now else prev.lastOkAt,
                                        fails = if (r.ok) 0 else prev.fails,
                                        ewmaBps = prev.ewmaBps,
                                        rawOk = prev.rawOk || (r.ok && isRaw),
                                        releaseOk = prev.releaseOk || (r.ok && !isRaw),
                                    )
                            ),
                )
        }
        stateMutex.withLock {
            sessionState = updated
            stateStore.save(updated)
        }
        return orderCandidates(updated, isRaw)
    }

    /** 候选排序：曾成功优先、延迟升序；连续失败 ≥3 会话内降级；raw 通道偏好 rawOk、release 通道偏好 releaseOk（§6.5 双可达性位） */
    private fun orderCandidates(
        state: MirrorStateSnapshot,
        isRaw: Boolean,
    ): List<Mirror> {
        val entries = state.mirrors
        val sorted =
            mirrors.sortedWith(
                compareBy(
                    { if ((entries[it.id]?.lastOkAt ?: 0) > 0) 0 else 1 },
                    { entries[it.id]?.latencyMs ?: Long.MAX_VALUE },
                ),
            )
        val (bad, good) = sorted.partition { (entries[it.id]?.fails ?: 0) >= FAIL_DEMOTE }
        val ordered = good + bad
        // 通道可达性偏好：raw 对象优先 rawOk 镜像、release 对象优先 releaseOk 镜像（其余保留兜底）
        val capable = ordered.filter { entries[it.id]?.let { e -> if (isRaw) e.rawOk else e.releaseOk } == true }
        return if (capable.isNotEmpty()) capable + ordered.filter { it !in capable } else ordered
    }

    private data class ProbeResult(
        val ok: Boolean,
        val latencyMs: Long,
    )

    /** 单镜像可达性/延迟探测：Range 0-0（≈0 流量），快速失败 */
    private suspend fun probeLatency(fullUrl: String): ProbeResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val req =
                    Request
                        .Builder()
                        .url(fullUrl)
                        .header("Range", PROBE_RANGE)
                        .header("User-Agent", "AppStore/2.0 (Android)")
                        .build()
                probeClient().newCall(req).execute().use { resp ->
                    val ok = resp.isSuccessful || resp.code == 206
                    ProbeResult(ok = ok, latencyMs = System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                ProbeResult(ok = false, latencyMs = System.currentTimeMillis() - start)
            }
        }

    private sealed interface StreamResult {
        data class Completed(
            val bytesWritten: Long,
        ) : StreamResult

        data class Aborted(
            val reason: String,
            val code: Int? = null,
            val restartRequired: Boolean = false,
        ) : StreamResult
    }

    private class SourceException(
        message: String,
        val code: Int? = null,
        val restartRequired: Boolean = false,
    ) : IOException(message)

    /** 已建立连接并读到首字节的源（竞速胜者继续使用，败者取消） */
    private class SourceConnection(
        val call: Call,
        val response: Response,
        val src: BufferedSource,
        val buf: ByteArray,
        val firstLen: Int,
        val totalKnown: Long,
    ) {
        fun close() {
            runCatching { call.cancel() }
            runCatching { response.close() }
        }
    }

    private data class Winner(
        val conn: SourceConnection,
        val mirror: Mirror,
    )

    /**
     * 竞速式流式下载（hedged request，规格 §6.3）：
     * - 首选源先发；[HEDGE_FIRST_BYTE_MS]（400ms）内未出首字节 → 并发第二源；
     * - 首字节到达即定胜者，取消其余连接，仅胜者写盘（不会交叉写坏文件）；
     * - 全部源首字节均失败 → Aborted；胜者中途失速/失败 → Aborted（保留已写字节供续传）。
     *
     * @return (结果, 胜者镜像；首字节全部失败时为 null, 源级失败镜像列表)
     */
    private suspend fun streamFromSources(
        sources: List<Mirror>,
        objectUrl: String,
        dest: File,
        resumeFrom: Long,
        onProgress: (Float) -> Unit,
    ): Triple<StreamResult, Mirror?, List<Mirror>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                dest.parentFile?.mkdirs()
                val winner = CompletableDeferred<Winner?>()
                val finished = CompletableDeferred<StreamResult>()
                val pending = AtomicInteger(sources.size)
                val saw404 = AtomicBoolean(false)
                val sawRestartRequired = AtomicBoolean(false)
                val failed = Collections.synchronizedSet(mutableSetOf<Mirror>())
                val calls = Collections.synchronizedList(mutableListOf<Call>())

                fun raceDone() {
                    if (pending.decrementAndGet() == 0 && !winner.isCompleted) {
                        winner.complete(null)
                    }
                }

                sources.forEachIndexed { index, mirror ->
                    launch {
                        try {
                            if (index > 0) {
                                delay(HEDGE_FIRST_BYTE_MS * index)
                                if (winner.isCompleted) return@launch
                            }
                            val call = client.newCall(buildRequest(mirror.prefix + objectUrl, resumeFrom))
                            calls.add(call)
                            var conn: SourceConnection? = null
                            try {
                                conn = connect(call, resumeFrom)
                                if (winner.complete(Winner(conn, mirror))) {
                                    // 本源胜出：取消其余候选，继续流式下载
                                    calls.filter { it !== call }.forEach { it.cancel() }
                                    val result =
                                        try {
                                            streamRemainder(conn, dest, resumeFrom, onProgress)
                                        } catch (e: Exception) {
                                            StreamResult.Aborted(reason = e.message ?: e.javaClass.simpleName)
                                        }
                                    if (result is StreamResult.Aborted) failed.add(mirror)
                                    finished.complete(result)
                                    conn = null // 所有权移交 streamRemainder（已关闭）
                                } else {
                                    call.cancel()
                                }
                            } catch (e: SourceException) {
                                if (e.code == 404) saw404.set(true)
                                if (e.restartRequired) sawRestartRequired.set(true)
                                failed.add(mirror)
                                // 源级失败（404 / HTML / Range 忽略 / 无数据 / 首字节超时）
                            } catch (e: Exception) {
                                // 竞速败者连接被取消（IOException "Canceled"）或其它 IO 错误，忽略
                            } finally {
                                conn?.close()
                            }
                        } finally {
                            raceDone()
                        }
                    }
                }

                // 全部候选源首字节均失败 → 汇总 Aborted
                launch {
                    if (winner.await() == null) {
                        finished.complete(
                            StreamResult.Aborted(
                                reason = "所有候选源首字节均失败",
                                code = if (saw404.get()) 404 else null,
                                restartRequired = sawRestartRequired.get(),
                            ),
                        )
                    }
                }

                Triple(finished.await(), winner.await()?.mirror, failed.toList())
            }
        }

    private fun buildRequest(
        fullUrl: String,
        resumeFrom: Long,
    ): Request =
        Request
            .Builder()
            .url(fullUrl)
            .header("User-Agent", "AppStore/2.0 (Android)")
            .apply {
                if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-")
            }.build()

    /**
     * 连接并读取首字节：校验状态码 / HTML 挑战页 / Range 忽略，首字节超时快速失败。
     * 失败抛 [SourceException]；成功返回已含首块数据的 [SourceConnection]。
     */
    private suspend fun connect(
        call: Call,
        resumeFrom: Long,
    ): SourceConnection {
        var conn: SourceConnection? = null
        val done =
            withTimeoutOrNull(FIRST_BYTE_TIMEOUT_MS) {
                val resp = call.execute()
                if (!resp.isSuccessful && resp.code != 206) {
                    throw SourceException("HTTP ${resp.code}", resp.code)
                }
                val body = resp.body ?: throw SourceException("空响应体")
                if (resumeFrom > 0L && resp.code == 200) {
                    // 源忽略 Range 返回全量：继续追加会损坏文件，需从头重试
                    throw SourceException("Range 被忽略", resp.code, restartRequired = true)
                }
                val totalKnown = (body.contentLength().takeIf { it > 0 } ?: 0L) + resumeFrom
                val src = body.source()
                val buf = ByteArray(64 * 1024)
                val n = src.read(buf)
                if (n <= 0) throw SourceException("无数据")
                if (resumeFrom == 0L) {
                    val head = String(buf, 0, minOf(n, 512), Charsets.UTF_8).trimStart().lowercase()
                    if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                        throw SourceException("HTML 挑战页", resp.code)
                    }
                }
                conn = SourceConnection(call, resp, src, buf, n, totalKnown)
                true
            }
        if (done != true) {
            runCatching { call.cancel() }
            throw SourceException("首字节超时(>${FIRST_BYTE_TIMEOUT_MS}ms)")
        }
        return conn!!
    }

    /**
     * 胜者源继续流式下载：写已缓冲首块 + 后续块，失速看门狗按吞吐口径中断。
     * 中断时已写字节保留（供下一候选 Range 续传）。
     */
    private suspend fun streamRemainder(
        conn: SourceConnection,
        dest: File,
        resumeFrom: Long,
        onProgress: (Float) -> Unit,
    ): StreamResult =
        coroutineScope {
            val out = if (resumeFrom > 0L) dest.appendingSink().buffer() else dest.sink().buffer()
            val written = AtomicLong(resumeFrom)
            out.write(conn.buf, 0, conn.firstLen)
            written.addAndGet(conn.firstLen.toLong())
            if (conn.totalKnown > 0) onProgress((written.get().toFloat() / conn.totalKnown).coerceIn(0f, 1f))

            // 失速看门狗：每 500ms 采样一次已写字节，最近 5s 窗口吞吐 < 32KB/s 即判定失速
            val watchdog =
                launch {
                    val samples = ArrayDeque<Pair<Long, Long>>() // (时间戳, 已写字节)
                    while (true) {
                        delay(STALL_CHECK_MS)
                        val now = System.currentTimeMillis()
                        samples.addLast(now to written.get())
                        // 保留比 5s 窗口略宽（多一个采样周期）的样本，保证窗口长度可达到 5s
                        while (samples.isNotEmpty() && now - samples.first().first > STALL_GRACE_MS + STALL_CHECK_MS) {
                            samples.removeFirst()
                        }
                        if (samples.size >= 2) {
                            val oldest = samples.first()
                            val newest = samples.last()
                            val windowMs = newest.first - oldest.first
                            val bytesInWindow = newest.second - oldest.second
                            if (windowMs >= STALL_GRACE_MS && bytesInWindow < STALL_MIN_BPS * windowMs / 1000) {
                                conn.call.cancel()
                                break
                            }
                        }
                    }
                }
            try {
                while (true) {
                    val read = conn.src.read(conn.buf)
                    if (read == -1) break
                    written.addAndGet(read.toLong())
                    out.write(conn.buf, 0, read)
                    if (conn.totalKnown > 0) onProgress((written.get().toFloat() / conn.totalKnown).coerceIn(0f, 1f))
                }
                out.flush()
                StreamResult.Completed(bytesWritten = written.get())
            } catch (e: IOException) {
                StreamResult.Aborted(reason = e.message ?: e.javaClass.simpleName)
            } finally {
                watchdog.cancel()
                runCatching { conn.call.cancel() }
                runCatching { out.close() }
                runCatching { conn.response.close() }
            }
        }

    private suspend fun recordSuccess(
        mirror: Mirror,
        isRaw: Boolean,
        startedAt: Long,
        bytes: Long,
    ) {
        val now = System.currentTimeMillis()
        val elapsedMs = (now - startedAt).coerceAtLeast(1)
        val bps = bytes * 1000 / elapsedMs
        stateMutex.withLock {
            val st = sessionState ?: return@withLock
            val prev = st.mirrors[mirror.id] ?: MirrorStateEntry()
            val ewma = if (prev.ewmaBps > 0) (prev.ewmaBps * 7 + bps) / 8 else bps
            val updated =
                st.copy(
                    mirrors =
                        st.mirrors +
                            (
                                mirror.id to
                                    prev.copy(
                                        lastOkAt = now,
                                        fails = 0,
                                        ewmaBps = ewma,
                                        rawOk = prev.rawOk || isRaw,
                                        releaseOk = prev.releaseOk || !isRaw,
                                    )
                            ),
                )
            sessionState = updated
            stateStore.save(updated)
        }
    }

    private suspend fun recordFail(mirror: Mirror) {
        stateMutex.withLock {
            val st = sessionState ?: return@withLock
            val prev = st.mirrors[mirror.id] ?: MirrorStateEntry()
            val updated = st.copy(mirrors = st.mirrors + (mirror.id to prev.copy(fails = prev.fails + 1)))
            sessionState = updated
            stateStore.save(updated)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** 全部候选源均返回 404：用于 v2 协议探测（对方仍是 v1 形态） */
class MirrorNotFound(
    url: String,
) : IOException("资源不存在(404)：$url")
