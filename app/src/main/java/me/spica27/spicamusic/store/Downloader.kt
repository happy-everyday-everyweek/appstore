package me.spica27.spicamusic.store

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import java.io.File
import java.security.MessageDigest

/**
 * 下载器（GitLink 移植的最小底座式实现）：
 * 分块流式写入、SHA-256 校验、<10KB 空文件防护重试（最多 3 轮）、断点续传（Range）。
 */
interface Downloader {
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): File
}

class OkHttpDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) : Downloader {
    override suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
    ): File {
        var attempts = 0
        while (attempts < 3) {
            attempts++
            try {
                val file = downloadOnce(url, dest, onProgress)
                // <10KB 空文件/短读防护
                if (file.length() < 10 * 1024) {
                    throw IllegalStateException("下载文件过小（${file.length()}B），疑似空响应")
                }
                if (expectedSha256 != null) {
                    val actual = sha256(file)
                    if (actual != expectedSha256) {
                        throw IllegalStateException("SHA-256 不一致：期望 $expectedSha256，实际 $actual")
                    }
                }
                return file
            } catch (e: Exception) {
                if (attempts >= 3) throw e
                dest.delete()
            }
        }
        error("unreachable")
    }

    private fun downloadOnce(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ): File {
        dest.parentFile?.mkdirs()
        // 断点续传：已有部分则以 Range 续传
        val existing = dest.length()
        val request =
            Request
                .Builder()
                .url(url)
                .apply {
                    if (existing > 0) header("Range", "bytes=$existing-")
                }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val total =
                response.body
                    ?.contentLength()
                    ?.takeIf { it > 0 }
                    ?.plus(existing) ?: existing
            val input = response.body?.source() ?: throw IllegalStateException("空响应体")
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
                    if (dest.length() <= existing && total > 0) {
                        throw IllegalStateException("无新数据写入")
                    }
                }
            }
            return dest
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
