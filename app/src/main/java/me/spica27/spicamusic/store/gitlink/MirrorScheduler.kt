package me.spica27.spicamusic.store.gitlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.spica27.spicamusic.store.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.appendingSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 镜像调度器（v2：会话级选择 + 下载中自适应，替代 v1 每文件全量测速）。
 *
 * 与 v1 [SpeedTester] 12MB/6s 全量测速的关键差异：
 * - 测速退化为「会话首次请求」的一次 Range 0-0 轻探测（HEAD 等价，流量≈0），
 *   结果持久化，TTL 24h 内复用，命中缓存为 0 次探测；
 * - 对象下载按「top-3 + GitHub 直连」候选顺序，首字节超时快速失败、
 *   下载中失速（吞吐过低持续 5s）看门狗切换下一源并断点续传；
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
        const val STALL_CHECK_MS = 500L
        const val STALL_GRACE_MS = 5000L
        const val TOP_N = 3
        const val MAX_ATTEMPTS = 5
        const val FAIL_DEMOTE = 3
        const val DIRECT_ID = "github"
    }

    /** 会话内缓存的状态快照（含持久化结果） */
    @Volatile
    private var sessionState: MirrorStateSnapshot? = null

    /** 阶段回调用（供 UI 展示具体流程） */
    var onStage: ((String) -> Unit)? = null

    private fun probeClient(): OkHttpClient =
        client
            .newBuilder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .callTimeout(1500, TimeUnit.MILLISECONDS)
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
        val ranked = ensureProbed(url)
        val candidates =
            buildList {
                addAll(ranked.take(TOP_N))
                mirrors.firstOrNull { it.id == DIRECT_ID }?.takeIf { it !in this }?.let { add(it) }
            }
        DebugLog.i("Download", "[v2] 候选源：${candidates.joinToString { it.name }}")
        var lastErr: Throwable = IOException("无可用镜像")
        var saw404 = false
        var attempt = 0
        for (m in candidates) {
            if (attempt >= MAX_ATTEMPTS) break
            attempt++
            val full = m.prefix + url
            try {
                val started = System.currentTimeMillis()
                val result = streamFromSource(full, dest, dest.length(), onProgress)
                when (result) {
                    is StreamResult.Completed -> {
                        if (dest.length() < 1) {
                            lastErr = IOException("下载文件为空（0B），疑似空响应")
                            recordFail(m)
                            dest.delete()
                            continue
                        }
                        if (expectedSha256 != null && sha256(dest) != expectedSha256) {
                            lastErr = IOException("SHA-256 不一致：期望 ${expectedSha256.take(8)}…")
                            recordFail(m)
                            dest.delete()
                            continue
                        }
                        recordSuccess(m, isRaw, started, dest.length())
                        val cost = System.currentTimeMillis() - t0
                        DebugLog.i("Download", "[v2] 镜像「${m.name}」成功 ${dest.length()} 字节，耗时 ${cost}ms")
                        return dest
                    }
                    is StreamResult.Aborted -> {
                        if (result.code == 404) saw404 = true
                        lastErr = IOException("镜像「${m.name}」失败：${result.reason}")
                        if (result.restartRequired) dest.delete()
                        recordFail(m)
                    }
                }
            } catch (e: Exception) {
                lastErr = e
                recordFail(m)
                runCatching { dest.delete() }
            }
        }
        DebugLog.e("Download", "[v2] 全部候选源失败（尝试 $attempt 个）：${lastErr.message}")
        throw if (saw404) MirrorNotFound(url) else IOException("镜像全部失败（尝试 $attempt 个）：${lastErr.message}", lastErr)
    }

    /** 会话/持久化探测：TTL 内复用，否则并发轻探测一次并落盘 */
    private suspend fun ensureProbed(url: String): List<Mirror> {
        val now = System.currentTimeMillis()
        val state = sessionState ?: stateStore.load()
        if (state.probedAt > 0 && now - state.probedAt < PROBE_TTL_MS) {
            sessionState = state
            return orderCandidates(state, url)
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
                                        rawOk = prev.rawOk,
                                    )
                            ),
                )
        }
        sessionState = updated
        stateStore.save(updated)
        return orderCandidates(updated, url)
    }

    /** 候选排序：曾成功优先、延迟升序；连续失败 ≥3 会话内降级；raw 路由偏好 rawOk 镜像 */
    private fun orderCandidates(
        state: MirrorStateSnapshot,
        url: String,
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
        if (!url.startsWith("https://raw.")) return ordered
        val rawCapable = ordered.filter { entries[it.id]?.rawOk == true }
        return if (rawCapable.isNotEmpty()) rawCapable + ordered.filter { it !in rawCapable } else ordered
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
    ) : IOException(message)

    /**
     * 单源流式下载：首字节超时快速失败、HTML 挑战页嗅探、失速看门狗中断。
     * 中断时已写入 dest 的部分字节保留（供下一源 Range 续传）。
     */
    private suspend fun streamFromSource(
        sourceUrl: String,
        dest: File,
        resumeFrom: Long,
        onProgress: (Float) -> Unit,
    ): StreamResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                dest.parentFile?.mkdirs()
                val start = System.currentTimeMillis()
                val request =
                    Request
                        .Builder()
                        .url(sourceUrl)
                        .header("User-Agent", "AppStore/2.0 (Android)")
                        .apply {
                            if (resumeFrom > 0L) header("Range", "bytes=$resumeFrom-")
                        }.build()
                val call = client.newCall(request)
                var response: Response? = null
                var totalKnown = 0L
                var written = resumeFrom
                // 续传（resumeFrom>0）必须追加写，否则 sink() 会先截断已下好的部分字节
                val out = if (resumeFrom > 0L) dest.appendingSink().buffer() else dest.sink().buffer()
                val lastActivity = AtomicLong(System.currentTimeMillis())
                val watchdog =
                    launch {
                        // 失速看门狗：超时未活动即中断（finally 中 watchdog.cancel() 保证终止）
                        while (true) {
                            delay(STALL_CHECK_MS)
                            if (System.currentTimeMillis() - lastActivity.get() >= STALL_GRACE_MS) {
                                call.cancel()
                                break
                            }
                        }
                    }
                try {
                    val first =
                        withTimeoutOrNull(FIRST_BYTE_TIMEOUT_MS) {
                            val resp = call.execute()
                            response = resp
                            if (!resp.isSuccessful && resp.code != 206) {
                                throw SourceException("HTTP ${resp.code}", resp.code)
                            }
                            val body = resp.body ?: throw SourceException("空响应体")
                            if (resumeFrom > 0L && resp.code == 200) {
                                // 源忽略 Range 返回全量：继续追加会损坏文件，需从头重试
                                throw SourceException("Range 被忽略", resp.code)
                            }
                            totalKnown = (body.contentLength().takeIf { it > 0 } ?: 0L) + resumeFrom
                            val src = body.source()
                            val buf = ByteArray(64 * 1024)
                            val n = src.read(buf)
                            if (n <= 0) throw SourceException("无数据")
                            lastActivity.set(System.currentTimeMillis())
                            if (resumeFrom == 0L) {
                                val head = String(buf, 0, minOf(n, 512), Charsets.UTF_8).trimStart().lowercase()
                                if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                                    throw SourceException("HTML 挑战页", resp.code)
                                }
                            }
                            out.write(buf, 0, n)
                            written += n
                            if (totalKnown > 0) onProgress((written.toFloat() / totalKnown).coerceIn(0f, 1f))
                            Pair(src, buf)
                        } ?: throw SourceException("首字节超时(>${FIRST_BYTE_TIMEOUT_MS}ms)")
                    val (src, buf) = first
                    while (true) {
                        val read = src.read(buf)
                        if (read == -1) break
                        lastActivity.set(System.currentTimeMillis())
                        out.write(buf, 0, read)
                        written += read
                        if (totalKnown > 0) onProgress((written.toFloat() / totalKnown).coerceIn(0f, 1f))
                    }
                    out.flush()
                    StreamResult.Completed(bytesWritten = written)
                } catch (e: SourceException) {
                    StreamResult.Aborted(
                        reason = e.message ?: "源失败",
                        code = e.code,
                        restartRequired = e.code == 200 && resumeFrom > 0L,
                    )
                } catch (e: Exception) {
                    StreamResult.Aborted(
                        reason = e.message ?: e.javaClass.simpleName,
                        restartRequired = false,
                    )
                } finally {
                    watchdog.cancel()
                    runCatching { call.cancel() }
                    runCatching { out.close() }
                    runCatching { response?.close() }
                }
            }
        }

    private fun recordSuccess(
        mirror: Mirror,
        isRaw: Boolean,
        startedAt: Long,
        bytes: Long,
    ) {
        val st = sessionState ?: return
        val prev = st.mirrors[mirror.id] ?: MirrorStateEntry()
        val now = System.currentTimeMillis()
        val elapsedMs = (now - startedAt).coerceAtLeast(1)
        val bps = bytes * 1000 / elapsedMs
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
                                )
                        ),
            )
        sessionState = updated
        stateStore.save(updated)
    }

    private fun recordFail(mirror: Mirror) {
        val st = sessionState ?: return
        val prev = st.mirrors[mirror.id] ?: MirrorStateEntry()
        val updated = st.copy(mirrors = st.mirrors + (mirror.id to prev.copy(fails = prev.fails + 1)))
        sessionState = updated
        stateStore.save(updated)
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
