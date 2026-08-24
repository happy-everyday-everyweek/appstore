package me.spica27.spicamusic.store.gitlink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import me.spica27.spicamusic.store.Downloader
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * GitLink 下载底座（移植自用户的 GitLink 应用）：
 * - 内置 33 个 GitHub 加速镜像（ghproxy 系列 / ghfast / 官方直连等，见 [Mirrors]）
 * - 下载前全镜像并发智能测速（6 秒分段采样，抗"先快后慢"，见 [SpeedTester]），按评分降序尝试
 * - Range 断点续传 + 空文件防护（镜像返回过小文件自动换下一源，最多 3 轮）
 * - 支持 SHA-256 强校验（聚合包/推荐包/APK 下载共用）
 * 针对国内网络：镜像全员可达性由测速自动淘汰，任意一源可用即可完成下载。
 */
class GitLinkDownloader(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build(),
) : Downloader {
    private val tester = SpeedTester(client)

    /** 阶段回调用（测速/尝试镜像等），供 UI 展示具体流程 */
    var onStage: ((String) -> Unit)? = null

    /** 下载失败时最多连续尝试的镜像数（测速排序后前几名足够；避免逐个 6s 超时拖死首启） */
    private val maxMirrorAttempts = 8

    override suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
    ): File {
        onStage?.invoke("正在对 33 个镜像测速挑选最快源…")
        val ranked = rankMirrors(url)
        val attempts = mutableListOf<String>()
        var lastErr: Throwable = IOException("无可用镜像")
        val total = minOf(ranked.size, maxMirrorAttempts)
        for (index in 0 until total) {
            val mirror = ranked[index]
            onStage?.invoke("正在尝试镜像「${mirror.name}」（${index + 1}/$total）…")
            try {
                val file = downloadVia(mirror.prefix + url, dest, onProgress)
                // 空响应防护：仅拒绝 0 字节（增量包可能只含 1~2 个应用的差异，体积可能不足 1KB）
                if (file.length() < 1) {
                    throw IOException("下载文件为空（0B），疑似空响应")
                }
                // HTML 挑战页嗅探：gh-proxy 系列风控/未登录时对文本资产返回 200 + HTML Loading 页，
                // 小文本（patch.json/README）会被误判为成功；嗅探到 <html 即视为失败换下一镜像
                if (file.length() <= 512 * 1024) {
                    val buf = ByteArray(512)
                    val n = file.inputStream().use { it.read(buf) }
                    val head = String(buf, 0, n, Charsets.UTF_8).trimStart().lowercase()
                    if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                        throw IOException("镜像返回 HTML 挑战页而非目标内容（前 ${head.take(40)}…），换源")
                    }
                }
                if (expectedSha256 != null) {
                    val actual = sha256(file)
                    if (actual != expectedSha256) {
                        throw IOException("SHA-256 不一致：期望 $expectedSha256，实际 $actual")
                    }
                }
                return file
            } catch (e: Exception) {
                lastErr = e
                attempts += "${mirror.name}:${e.message ?: e::class.simpleName}"
                dest.delete()
            }
        }
        // 聚合所有镜像失败原因，方便用户/诊断定位
        val detail = attempts.joinToString("；")
        throw IOException("镜像全部失败（尝试 $total 个）：$detail", lastErr)
    }

    /**
     * 全镜像并发测速（GitLink 智能测速），按评分降序；失败镜像沉底。
     * 测速窗口约 6 秒（小文件在探测字节数内提前结束）。
     */
    private suspend fun rankMirrors(url: String): List<Mirror> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                Mirrors.DEFAULT
                    .map { mirror -> async { mirror to tester.probe(mirror, url) } }
                    .awaitAll()
            }.sortedByDescending { (_, result) -> result.score }
                .map { (mirror, _) -> mirror }
        }

    /** 单源流式下载：Range 断点续传，进度回调 */
    private suspend fun downloadVia(
        fullUrl: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val existing = dest.length()
            val request =
                Request
                    .Builder()
                    .url(fullUrl)
                    .header("User-Agent", "GitLink/1.0 (Android)")
                    .apply {
                        if (existing > 0) header("Range", "bytes=$existing-")
                    }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("HTTP ${response.code}")
                }
                val total =
                    response.body
                        ?.contentLength()
                        ?.takeIf { it > 0 }
                        ?.plus(existing) ?: existing
                val input = response.body?.source() ?: throw IOException("空响应体")
                val output = dest.sink().buffer()
                input.use { src ->
                    output.use { out ->
                        val buffer = okio.Buffer()
                        var written = existing
                        while (true) {
                            val read = src.read(buffer, 64 * 1024)
                            if (read == -1L) break
                            out.write(buffer, read)
                            written += read
                            if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            dest
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
