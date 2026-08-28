package me.spica27.spicamusic.store

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.spica27.spicamusic.common.entity.appstore.ManifestIndexRef
import me.spica27.spicamusic.common.entity.appstore.ManifestObjectRef
import me.spica27.spicamusic.common.entity.appstore.ManifestV2
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * BundleLoader URL 解析回归测试（规格 §5.1：manifest.bundles[].url 携带完整 tag 限定路径，
 * 历史 URL 永久有效）：
 * - dist- 前缀：已是 tag 限定完整相对路径 → 直接拼接 release base，绝不二次追加 /bundles/；
 * - bundles/ 前缀：相对当前 Release → <releaseTag>/<url>；
 * - 完整 http URL：原样使用。
 */
class BundleLoaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val base = "https://github.com/${SyncChannel.AppIndex.repo}/releases/download"

    /** 记录请求 URL 并回放一个含 detail.json 的最小 bundle zip */
    private class RecordingFetcher : ObjectFetcher {
        var lastUrl: String? = null
        var downloadedBundles = 0

        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
            isRaw: Boolean,
        ): File {
            lastUrl = url
            downloadedBundles++
            dest.parentFile?.mkdirs()
            val zip =
                ByteArrayOutputStream().use { bos ->
                    ZipOutputStream(bos).use { zos ->
                        zos.putNextEntry(ZipEntry("detail.json"))
                        zos.write("""{"source":{"apkUrl":"https://example.com/a.apk"}}""".toByteArray())
                        zos.closeEntry()
                    }
                    bos.toByteArray()
                }
            dest.writeBytes(zip)
            onProgress(1f)
            return dest
        }
    }

    private fun manifestWith(
        releaseTag: String,
        url: String,
    ): String {
        val m =
            ManifestV2(
                version = 2,
                channel = "app-index",
                releaseTag = releaseTag,
                index = ManifestIndexRef(sha256 = "index-sha", size = 1, count = 1),
                bundles = listOf(ManifestObjectRef(id = "1048", url = url, sha256 = "bundle-sha")),
            )
        return Json { ignoreUnknownKeys = true }.encodeToString(ManifestV2.serializer(), m)
    }

    @Test
    fun `dist 前缀 bundle URL 直接拼接 release base 不二次追加`() =
        runBlocking {
            val store = SyncStore(tmp.newFolder())
            store.writeManifestSnapshot(
                manifestWith("dist-20260827040017", "dist-20260827040017/bundles/1048.bundle.zip"),
            )
            val fetcher = RecordingFetcher()
            val loader = BundleLoader(fetcher, store)

            loader.loadBundle("1048")

            assertEquals(
                "dist- 前缀应直接拼接（§5.1 完整 tag 限定路径）",
                "$base/dist-20260827040017/bundles/1048.bundle.zip",
                fetcher.lastUrl,
            )
            assertTrue("bundle 已解包并写 sha256 标记", File(store.bundleDir("1048"), ".sha256").exists())
        }

    @Test
    fun `bundles 相对前缀拼接到当期 release tag`() =
        runBlocking {
            val store = SyncStore(tmp.newFolder())
            store.writeManifestSnapshot(
                manifestWith("dist-20260827040017", "bundles/1048.bundle.zip"),
            )
            val fetcher = RecordingFetcher()
            val loader = BundleLoader(fetcher, store)

            loader.loadBundle("1048")

            assertEquals(
                "bundles/ 相对前缀应拼 releaseTag",
                "$base/dist-20260827040017/bundles/1048.bundle.zip",
                fetcher.lastUrl,
            )
        }

    @Test
    fun `完整 http bundle URL 原样使用`() =
        runBlocking {
            val store = SyncStore(tmp.newFolder())
            val full = "https://example.com/static/1048.bundle.zip"
            store.writeManifestSnapshot(
                manifestWith("dist-20260827040017", full),
            )
            val fetcher = RecordingFetcher()
            val loader = BundleLoader(fetcher, store)

            loader.loadBundle("1048")

            assertEquals("完整 URL 原样使用", full, fetcher.lastUrl)
        }

    @Test
    fun `已解包且 sha 一致时不重复下载`() =
        runBlocking {
            val store = SyncStore(tmp.newFolder())
            store.writeManifestSnapshot(
                manifestWith("dist-20260827040017", "dist-20260827040017/bundles/1048.bundle.zip"),
            )
            val fetcher = RecordingFetcher()
            val loader = BundleLoader(fetcher, store)

            loader.loadBundle("1048")
            val first = fetcher.downloadedBundles
            loader.loadBundle("1048")

            assertEquals("SHA 一致则复用本地缓存", first, fetcher.downloadedBundles)
        }
}
