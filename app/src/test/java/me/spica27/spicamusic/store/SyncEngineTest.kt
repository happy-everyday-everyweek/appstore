package me.spica27.spicamusic.store

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * SyncEngine 测试（全 GitLink 直链模式）：
 * fake Downloader 按 URL 分发资产，验证 patch.target/base 驱动的
 * 全量 / 增量 / 幂等 / target 为空 / 网络失败五类路径。
 */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeDownloader(
        private val files: Map<String, ByteArray>,
    ) : Downloader {
        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
        ): File {
            val data = files[url] ?: throw java.io.IOException("no asset for $url")
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            onProgress(1f)
            return dest
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray())
            }
        }
        return bos.toByteArray()
    }

    private fun baseUrl(): String = "https://github.com/${SyncChannel.AppIndex.repo}/releases/latest/download"

    private fun downloadBase(tag: String): String = "https://github.com/${SyncChannel.AppIndex.repo}/releases/download/$tag"

    /** 增量 zip：incremental.json + 一个资产条目（与服务端形态一致，避免触发无资产回退全量） */
    private fun incZip(vararg ids: String): ByteArray =
        zipOf(
            "incremental.json" to """{"addedOrChanged":${indexJson(*ids)},"removed":[]}""",
            "icons/asset.png" to "asset-bytes",
        )

    private fun patchJson(
        base: String?,
        target: String?,
    ): String =
        """{"base":${base?.let {
            "\"$it\""
        } ?: "null"},"target":${target?.let {
            "\"$it\""
        } ?: "null"},"algorithm":"structured-json-v1","incrementalSha256":"abc","fullSha256":"def"}"""

    private fun indexJson(vararg ids: String): String =
        "{" + ids.joinToString(",") { "\"$it\":{\"id\":\"$it\",\"name\":\"app-$it\",\"repo\":\"owner/repo\"}" } + "}"

    @Test
    fun `patch 不可达时返回网络错误`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            val engine = SyncEngine(FakeDownloader(emptyMap()), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertEquals(SyncError.Network, result.error)
            assertNull(result.applied)
        }

    @Test
    fun `target 等于本地版本时幂等不更新`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-1")
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-0", "aggregate-1").toByteArray(),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertEquals(PackageKind.None, result.applied)
            assertEquals(false, result.changed)
        }

    @Test
    fun `base 等于本地版本时走增量包`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-0")
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-0", "aggregate-1").toByteArray(),
                    baseUrl() + "/incremental.zip" to incZip("1002"),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertTrue("detail: ${result.errorMessage}", result.applied == PackageKind.Incremental)
            assertTrue(result.changed)
            assertNotNull(root.readCachedText(SyncChannel.AppIndex))
        }

    @Test
    fun `base 不匹配时链式逐级回溯并应用增量包`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-0")
            // 最新：base=2 target=3；链：aggregate-0 -> 1 -> 2 -> 3
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-2", "aggregate-3").toByteArray(),
                    downloadBase("aggregate-2") + "/patch.json" to
                        patchJson("aggregate-1", "aggregate-2").toByteArray(),
                    downloadBase("aggregate-1") + "/patch.json" to
                        patchJson("aggregate-0", "aggregate-1").toByteArray(),
                    downloadBase("aggregate-1") + "/incremental.zip" to incZip("1002"),
                    downloadBase("aggregate-2") + "/incremental.zip" to incZip("1003"),
                    downloadBase("aggregate-3") + "/incremental.zip" to incZip("1004"),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertTrue("detail: ${result.errorMessage}", result.applied == PackageKind.Incremental)
            assertTrue(result.changed)
            assertEquals("aggregate-3", root.readVersion(SyncChannel.AppIndex))
            val cached = root.readCachedText(SyncChannel.AppIndex)
            assertNotNull(cached)
            assertTrue(cached!!.contains("1002"))
            assertTrue(cached.contains("1003"))
            assertTrue(cached.contains("1004"))
        }

    @Test
    fun `链式回溯失败时回退最新全量包`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-0")
            // aggregate-1 的 patch.json 不存在（404）→ 链断裂 → 全量
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-2", "aggregate-3").toByteArray(),
                    downloadBase("aggregate-2") + "/patch.json" to
                        patchJson("aggregate-1", "aggregate-2").toByteArray(),
                    baseUrl() + "/full.zip" to zipOf("index.json" to indexJson("2001", "2002")),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertEquals(PackageKind.Full, result.applied)
            assertTrue(result.changed)
            val cached = root.readCachedText(SyncChannel.AppIndex)
            assertNotNull(cached)
            assertTrue(cached!!.contains("2001"))
            assertEquals("aggregate-3", root.readVersion(SyncChannel.AppIndex))
        }

    @Test
    fun `链式中间某级增量失败时回退最新全量包`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-0")
            // aggregate-2 的 incremental.zip 缺失（456）→ 第 2 级失败 → 全量
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-2", "aggregate-3").toByteArray(),
                    downloadBase("aggregate-2") + "/patch.json" to
                        patchJson("aggregate-1", "aggregate-2").toByteArray(),
                    downloadBase("aggregate-1") + "/patch.json" to
                        patchJson("aggregate-0", "aggregate-1").toByteArray(),
                    downloadBase("aggregate-1") + "/incremental.zip" to incZip("1002"),
                    baseUrl() + "/full.zip" to zipOf("index.json" to indexJson("2001", "2002")),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertEquals(PackageKind.Full, result.applied)
            assertTrue(result.changed)
            val cached = root.readCachedText(SyncChannel.AppIndex)
            assertNotNull(cached)
            assertTrue(cached!!.contains("2001"))
            assertEquals("aggregate-3", root.readVersion(SyncChannel.AppIndex))
        }

    @Test
    fun `target 为空（历史 Release 数据）时不得跳过更新`() =
        runTest {
            val root = SyncStore(tmp.newFolder())
            root.writeCachedText(SyncChannel.AppIndex, indexJson("1001"))
            root.writeVersion(SyncChannel.AppIndex, "aggregate-旧")
            val files =
                mapOf(
                    baseUrl() + "/patch.json" to patchJson("aggregate-旧", null).toByteArray(),
                    baseUrl() + "/full.zip" to zipOf("index.json" to indexJson("2001", "2002")),
                )
            val engine = SyncEngine(FakeDownloader(files), root)
            val result = engine.sync(SyncChannel.AppIndex)
            assertEquals(PackageKind.Full, result.applied)
            val cached = root.readCachedText(SyncChannel.AppIndex)
            assertNotNull(cached)
            assertTrue(cached!!.contains("2001"))
        }
}
