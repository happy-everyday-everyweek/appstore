package me.spica27.spicamusic.store

import kotlinx.coroutines.test.runTest
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * StoreRepository 同步状态回路：
 * - 失败 → lastError 为详细错误（含 URL/镜像原因）
 * - 再次成功 → lastError 必须清空（防止“一点进页面就显示旧错误”）
 */
class StoreRepositorySyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDownloader(
        private val files: Map<String, ByteArray>,
        private val failUrls: Set<String> = emptySet(),
    ) : Downloader {
        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
        ): File {
            if (url in failUrls) throw java.io.IOException("模拟网络失败:$url")
            val data = files[url] ?: throw java.io.IOException("no asset $url")
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            onProgress(1f)
            return dest
        }
    }

    /** v2 探测期 Fake：manifest.v2.json 全部 404 → 引擎返回 usedV2=false，触发 v1 回退 */
    private class FakeV2Fetcher : ObjectFetcher {
        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
            isRaw: Boolean,
        ): File = throw java.io.IOException("v2 未启用（404）:$url")
    }

    private fun repository(
        v1: SyncEngine,
        store: SyncStore,
    ): StoreRepository = StoreRepository(v1, ManifestSyncEngine(FakeV2Fetcher(), store), store)

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (n, c) ->
                zos.putNextEntry(ZipEntry(n))
                zos.write(c.toByteArray())
            }
        }
        return bos.toByteArray()
    }

    private fun patchJson(target: String): String =
        """{"base":"none","target":"$target","algorithm":"structured-json-v1","incrementalSha256":"abc","fullSha256":"def"}"""

    private fun indexJson(vararg ids: String): String = "{" + ids.joinToString(",") { "\"$it\":{\"id\":\"$it\",\"name\":\"a$it\"}" } + "}"

    private fun baseUrl(channel: SyncChannel): String = "https://github.com/${channel.repo}/releases/latest/download"

    private fun allOkPatches(): Map<String, ByteArray> =
        mapOf(
            baseUrl(SyncChannel.AppIndex) + "/patch.json" to patchJson("aggregate-1").toByteArray(),
            baseUrl(SyncChannel.Discover) + "/patch.json" to patchJson("disc-1").toByteArray(),
            baseUrl(SyncChannel.AppIndex) + "/full.zip" to zipOf("index.json" to indexJson("1001", "1002")),
            baseUrl(SyncChannel.Discover) + "/full.zip" to zipOf("index.json" to "[]"),
        )

    @Test
    fun `sync 失败时 lastError 为详细错误`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val patches = allOkPatches()
            val downloader =
                FakeDownloader(patches, failUrls = setOf(baseUrl(SyncChannel.AppIndex) + "/patch.json"))
            val repo = repository(SyncEngine(downloader, store), store)
            repo.bootstrap()
            val err = repo.lastError.value
            assertNotNull("失败后必须记录错误", err)
            assertTrue("错误需含可定位详情（URL/原因）", err!!.contains("patch.json"))
        }

    @Test
    fun `再次同步成功后 lastError 必须清空`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val failUrl = baseUrl(SyncChannel.AppIndex) + "/patch.json"
            val repo = repository(SyncEngine(FakeDownloader(allOkPatches(), failUrls = setOf(failUrl)), store), store)
            repo.bootstrap()
            assertNotNull("先制造一次失败", repo.lastError.value)

            val store2 = SyncStore(tmp.newFolder())
            val repo2 = repository(SyncEngine(FakeDownloader(allOkPatches()), store2), store2)
            repo2.bootstrap()
            val cached = store2.readCachedText(SyncChannel.AppIndex)
            if (cached == null) {
                throw AssertionError("缓存未写入；lastError=${repo2.lastError.value}")
            }
            assertNull("成功后错误必须清空，实际: ${store2.readCachedText(SyncChannel.AppIndex)?.take(80)}", repo2.lastError.value)
            assertTrue("apps 空；缓存=$cached", repo2.apps.value.isNotEmpty())
        }
}
