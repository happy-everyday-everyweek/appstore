package me.spica27.spicamusic.store.gitlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class SpeedSample(
    val timeMs: Long,
    val bytes: Long,
)

data class MirrorProbeResult(
    val mirror: Mirror,
    val ok: Boolean,
    val sustainedBps: Long = 0, // 持续稳定速度（后期稳定段）
    val peakBps: Long = 0, // 峰值速度
    val earlyBps: Long = 0, // 早期速度
    val lateBps: Long = 0, // 后期速度
    val unstable: Boolean = false, // 是否"先快后慢"
    val rampUp: Boolean = false, // 是否"先慢后快"
    val latencyMs: Long = 0,
    val error: String? = null,
) {
    val score: Double
        get() {
            if (!ok) return 0.0
            var s = sustainedBps.toDouble()
            if (unstable) s *= 0.45
            if (rampUp) s *= 1.08
            return s
        }
}

/**
 * 智能测速器。
 *
 * 测速窗口 6 秒（给慢启动镜像爬升到峰值的时间），分三段分析：
 * 早期/中期/后期，有效速度取"中期+后期"稳定段，不被开头爆发欺骗。
 *
 * 支持"达标即停"：当某个镜像的持续速度 >= fastBps（如 2MB/s），
 * 立即终止其余测速并选定该镜像。
 */
class SpeedTester(
    private val client: OkHttpClient,
) {
    companion object {
        const val PROBE_BYTES = 12 shl 20 // 探测最多 12MB
        const val WINDOW_MS = 6000L // 测速窗口 6 秒
        const val SAMPLE_MS = 200L // 采样间隔
        const val CONCURRENCY = 4 // 同时测速的镜像数
    }

    private fun probeClient(): OkHttpClient =
        client
            .newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(9, TimeUnit.SECONDS)
            .build()

    suspend fun probe(
        mirror: Mirror,
        url: String,
    ): MirrorProbeResult =
        withContext(Dispatchers.IO) {
            val fullUrl = mirror.prefix + url
            val start = System.currentTimeMillis()
            val samples = ArrayList<SpeedSample>()
            var totalBytes = 0L
            var firstByteMs = -1L

            try {
                val req =
                    Request
                        .Builder()
                        .url(fullUrl)
                        .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                        .header("User-Agent", "GitLink/1.0 (Android)")
                        .build()

                probeClient().newCall(req).execute().use { resp ->
                    if (resp.code == 403 || resp.code == 404 || resp.code == 429 || resp.code >= 500) {
                        return@withContext MirrorProbeResult(mirror, false, error = "HTTP ${resp.code}")
                    }
                    val body = resp.body ?: return@withContext MirrorProbeResult(mirror, false, error = "空响应")
                    val buf = ByteArray(128 * 1024)
                    val source = body.source()
                    while (true) {
                        if (System.currentTimeMillis() - start > WINDOW_MS) break
                        val n =
                            try {
                                source.read(buf, 0, buf.size)
                            } catch (e: Exception) {
                                break
                            }
                        if (n <= 0) break
                        totalBytes += n
                        val now = System.currentTimeMillis()
                        if (firstByteMs < 0) firstByteMs = now - start
                        if (samples.isEmpty() || now - start - samples.last().timeMs >= SAMPLE_MS) {
                            samples.add(SpeedSample(now - start, totalBytes))
                        }
                        if (totalBytes >= PROBE_BYTES) break
                    }
                }
                analyze(mirror, samples, totalBytes, firstByteMs, null)
            } catch (e: Exception) {
                analyze(mirror, samples, totalBytes, firstByteMs, e.message ?: e.javaClass.simpleName)
            }
        }

    private fun analyze(
        mirror: Mirror,
        samples: List<SpeedSample>,
        totalBytes: Long,
        firstByteMs: Long,
        error: String?,
    ): MirrorProbeResult {
        val elapsed = samples.lastOrNull()?.timeMs ?: 0
        if (totalBytes <= 0) {
            return MirrorProbeResult(mirror, false, error = error ?: "无数据")
        }

        fun avgSpeed(range: IntRange): Long {
            if (range.isEmpty()) return 0
            val a = samples.getOrNull(range.first) ?: return 0
            val b = samples.getOrNull(range.last) ?: return 0
            val dt = (b.timeMs - a.timeMs).coerceAtLeast(50)
            return (b.bytes - a.bytes) * 1000 / dt
        }

        val n = samples.size
        val third = (n / 3).coerceAtLeast(1)
        val early = avgSpeed(0 until third)
        val mid = avgSpeed(third until (third * 2).coerceAtMost(n))
        val late = avgSpeed((third * 2).coerceAtMost(n - 1) until n)

        var peak = 0L
        for (i in 2 until n) {
            val dt = (samples[i].timeMs - samples[i - 1].timeMs).coerceAtLeast(50)
            val sp = (samples[i].bytes - samples[i - 1].bytes) * 1000 / dt
            if (sp > peak) peak = sp
        }

        val overall = totalBytes * 1000 / elapsed.coerceAtLeast(100)
        val sustained =
            if (mid > 0 || late > 0) {
                val list = listOf(mid, late).filter { it > 0 }
                if (list.isEmpty()) overall else list.average().toLong()
            } else {
                overall
            }

        val unstable = late > 0 && early > late * 2.5
        val rampUp = late > 0 && late > early * 1.5 && early > 0

        return MirrorProbeResult(
            mirror = mirror,
            ok = true,
            sustainedBps = sustained,
            peakBps = peak,
            earlyBps = early,
            lateBps = late,
            unstable = unstable,
            rampUp = rampUp,
            latencyMs = firstByteMs,
            error = null,
        )
    }

    /**
     * 对一批镜像测速（分小批并发，避免带宽争抢），按评分排序返回。
     *
     * @param fastBps 若某镜像持续速度达到该值（如 2MB/s），立即停止测速并返回该镜像
     *                （Pair.first 非 null 表示已达标选中）
     * @param onAvgSpeed 每完成一个镜像后回传"速度>0 镜像平均速度"，供 UI 折线图
     */
    suspend fun testAll(
        mirrors: List<Mirror>,
        url: String,
        fastBps: Long = 0L,
        onAvgSpeed: ((Long) -> Unit)? = null,
    ): Pair<Mirror?, List<MirrorProbeResult>> =
        coroutineScope {
            val shuffled = mirrors.shuffled()
            val resultsLock = Any()
            val results = mutableListOf<MirrorProbeResult>()
            val batches = shuffled.chunked(CONCURRENCY)

            fun avgOfPositive(): Long? {
                synchronized(resultsLock) {
                    val pos = results.filter { it.ok && it.sustainedBps > 0 }
                    if (pos.isEmpty()) return null
                    return pos.map { it.sustainedBps }.average().toLong()
                }
            }

            // 持续采样协程：从测速一开始就每隔 500ms 上报一次平均速度，折线图从头就在动
            val sampler =
                launch {
                    while (true) {
                        avgOfPositive()?.let { onAvgSpeed?.invoke(it) }
                        delay(500)
                    }
                }

            var fastPicked: Mirror? = null
            outer@ for (batch in batches) {
                val deferred = batch.map { m -> async(Dispatchers.Default) { probe(m, url) } }
                val pending = deferred.toMutableList()
                while (pending.isNotEmpty()) {
                    val done = pending.filter { it.isCompleted }
                    if (done.isEmpty()) {
                        delay(150)
                        continue
                    }
                    for (d in done) {
                        pending.remove(d)
                        val r = d.await()
                        synchronized(resultsLock) { results.add(r) }
                        // 达标即停
                        if (fastBps > 0 && r.ok && r.sustainedBps >= fastBps) {
                            fastPicked = r.mirror
                            deferred.forEach { it.cancel() }
                            break@outer
                        }
                    }
                }
            }
            sampler.cancel()
            // 最后一次上报
            avgOfPositive()?.let { onAvgSpeed?.invoke(it) }
            Pair(fastPicked, synchronized(resultsLock) { results.sortedByDescending { it.score } })
        }
}
