package me.spica27.spicamusic.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SyncEngine 测试（预商定接缝：SyncEngine.sync 的返回值；
 * fake GitHubReleaseClient + fake Downloader + 临时目录）。
 */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val indexJson =
        """
        {"1001":{"id":"1001","name":"Operit","packageName":"com.ai.assistance.operit",
          "grade":"D","version":{"releaseTag":"v1"},"source":{}},
         "1002":{"id":"1002","name":"Music","packageName":"me.spica27.spicamusic",
          "grade":"D","version":{"releaseTag":"v1"},"source":{}}}
        """.trimIndent()

    private val incJson =
        """
        {"addedOrChanged":{"1001":{"id":"1001","name":"Operit v2","packageName":"com.ai.assistance.operit",
          "grade":"D","version":{"releaseTag":"v2"},"source":{}}},
         "removed":["1002"]}
        """.trimIndent()

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            files.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun makeRelease(
        tag: String,
        full: ByteArray,
        inc: ByteArray?,
        base: String?,
    ): ReleaseInfo {
        val assets =
            mutableListOf(
                ReleaseAsset("full.zip", "url://full-$tag"),
                ReleaseAsset("patch.json", "url://patch-$tag"),
            )
        if (inc != null) assets.add(ReleaseAsset("incremental.zip", "url://inc-$tag"))
        return ReleaseInfo(tag = tag, assets = assets)
    }

    /** fake 下载器：按 URL 从预置 map 取字节写入目标文件 */
    private class FakeDownloader(
        private val files: MutableMap<String, ByteArray>,
    ) : Downloader {
        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
        ): File {
            val bytes = files[url] ?: throw java.io.IOException("not found: $url")
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            return dest
        }
    }

    private class FakeGitHub(
        var release: ReleaseInfo?,
    ) : GitHubReleaseClient {
        override suspend fun latestRelease(repo: String): ReleaseInfo? = release
    }

    @Test
    fun `first launch downloads full package and caches index`() =
        runTest {
            val root = tmp.newFolder()
            val full = zipOf("index.json" to indexJson)
            val files = mutableMapOf("url://full-v1" to full, "url://patch-v1" to "{}".toByteArray())
            val github = FakeGitHub(makeRelease("v1", full, null, null))
            val engine = SyncEngine(github, FakeDownloader(files), SyncStore(root))

            val result = engine.sync(SyncChannel.AppIndex)

            assertTrue(result.isOk)
            assertEquals(PackageKind.Full, result.applied)
            assertTrue(result.changed)
            assertNotNull(SyncStore(root).readCachedText(SyncChannel.AppIndex))
            assertEquals("v1", SyncStore(root).readVersion(SyncChannel.AppIndex))
        }

    @Test
    fun `incremental patch applies merged index`() =
        runTest {
            val root = tmp.newFolder()
            val store = SyncStore(root)
            // 预置旧状态：v1 全量已应用
            store.writeCachedText(SyncChannel.AppIndex, indexJson)
            store.writeVersion(SyncChannel.AppIndex, "v1")

            val full = zipOf("index.json" to indexJson)
            val inc = zipOf("incremental.json" to incJson)
            val patch = """{"base":"v1","target":"v2","algorithm":"structured-json-v1",
            "incrementalSha256":"","fullSha256":""}"""
            val files =
                mutableMapOf(
                    "url://full-v2" to full,
                    "url://inc-v2" to inc,
                    "url://patch-v2" to patch.toByteArray(),
                )
            val github = FakeGitHub(makeRelease("v2", full, inc, "v1"))
            val engine = SyncEngine(github, FakeDownloader(files), store)

            val result = engine.sync(SyncChannel.AppIndex)

            assertEquals(PackageKind.Incremental, result.applied)
            assertTrue(result.changed)
            val cached = store.readCachedText(SyncChannel.AppIndex)!!
            assertTrue("Operit v2" in cached)
            assertFalse("Music" in cached)
            assertEquals("v2", store.readVersion(SyncChannel.AppIndex))
        }

    @Test
    fun `no new release returns none`() =
        runTest {
            val root = tmp.newFolder()
            val store = SyncStore(root)
            store.writeCachedText(SyncChannel.AppIndex, indexJson)
            store.writeVersion(SyncChannel.AppIndex, "v1")

            val full = zipOf("index.json" to indexJson)
            val github = FakeGitHub(makeRelease("v1", full, null, null))
            val engine = SyncEngine(github, FakeDownloader(mutableMapOf()), store)

            val result = engine.sync(SyncChannel.AppIndex)

            assertEquals(PackageKind.None, result.applied)
            assertFalse(result.changed)
        }

    @Test
    fun `network failure returns readable error`() =
        runTest {
            val root = tmp.newFolder()
            val engine = SyncEngine(FakeGitHub(null), FakeDownloader(mutableMapOf()), SyncStore(root))
            val result = engine.sync(SyncChannel.AppIndex)
            assertNull(result.applied)
            assertEquals(SyncError.Network, result.error)
        }
}
