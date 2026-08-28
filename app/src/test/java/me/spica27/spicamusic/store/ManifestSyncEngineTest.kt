package me.spica27.spicamusic.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.spica27.spicamusic.common.entity.appstore.ManifestIndexRef
import me.spica27.spicamusic.common.entity.appstore.ManifestObjectRef
import me.spica27.spicamusic.common.entity.appstore.ManifestV2
import me.spica27.spicamusic.common.entity.appstore.ManifestV2Parser
import me.spica27.spicamusic.store.gitlink.MirrorNotFound
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ManifestSyncEngine 单测（v2 §6.1）：
 * - 协议探测：manifest.v2.json 全 404 → usedV2=false（仓库层回退 v1）
 * - 首次同步：index + 全部图标下载、sha256 标记、快照落盘
 * - 幂等：无更新返回 PackageKind.None 且不重复下载
 * - 差集驱动：仅下载新增图标；下架图标与过期 bundle 被清理
 * - partial 续传：图标失败留到下次同步自动补齐
 */
class ManifestSyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val manifestRaw =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/dist/manifest.v2.json"
    private val indexRaw =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/dist/index.v2.json"
    private val indexRelease =
        "https://github.com/${SyncChannel.AppIndex.repo}/releases/latest/download/index.v2.json"

    private fun iconUrl(id: String): String =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/apps/o/r/icon.png".replace("/o/r/", "/$id/")

    private class FakeObjectFetcher(
        private val files: Map<String, ByteArray>,
        private val failUrls: Set<String> = emptySet(),
    ) : ObjectFetcher {
        val downloaded = mutableListOf<String>()

        override suspend fun download(
            url: String,
            dest: File,
            expectedSha256: String?,
            onProgress: (Float) -> Unit,
            isRaw: Boolean,
        ): File {
            downloaded += url
            if (url in failUrls) throw IOException("模拟失败:$url")
            // 未提供的资源视为 404（v2 协议探测 / 通道兜底依赖 MirrorNotFound）
            val data = files[url] ?: throw MirrorNotFound(url)
            dest.parentFile?.mkdirs()
            dest.writeBytes(data)
            onProgress(1f)
            return dest
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    private val iconData = mapOf("1048" to "icon-bytes-1048".toByteArray(), "1049" to "icon-bytes-1049".toByteArray())

    private val indexText =
        """{"1048":{"id":"1048","name":"Operit AI","icon":"1048"},"1049":{"id":"1049","name":"Other","icon":"1049"}}"""

    private fun manifestOf(
        icons: List<ManifestObjectRef>,
        bundles: List<ManifestObjectRef> = emptyList(),
        indexSha: String = sha256(indexText.toByteArray()),
    ): String {
        val m =
            ManifestV2(
                version = 2,
                channel = "app-index",
                generatedAt = "2026-08-27T04:00:17Z",
                releaseTag = "dist-20260827040017",
                index = ManifestIndexRef(sha256 = indexSha, size = indexText.length.toLong(), count = icons.size),
                icons = icons,
                bundles = bundles,
            )
        return Json { ignoreUnknownKeys = true }.encodeToString(ManifestV2.serializer(), m)
    }

    private fun iconRef(id: String): ManifestObjectRef =
        ManifestObjectRef(id = id, path = "apps/$id/icon.png", sha256 = sha256(iconData[id]!!))

    private fun bundleRef(id: String): ManifestObjectRef =
        ManifestObjectRef(id = id, url = "dist-20260827040017/bundles/$id.bundle.zip", sha256 = "bundle-sha-$id")

    private fun filesFor(manifest: String): Map<String, ByteArray> =
        buildMap {
            put(manifestRaw, manifest.toByteArray())
            put(indexRaw, indexText.toByteArray())
            put(indexRelease, indexText.toByteArray()) // index 经 release 通道优先（§6.5）
            iconData.forEach { (id, bytes) -> put(iconUrl(id), bytes) }
        }

    @Test
    fun `manifest v2 全 404 时返回 usedV2=false`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val engine = ManifestSyncEngine(FakeObjectFetcher(emptyMap()), store)
            val r = engine.sync()
            assertFalse("探测到 v1 形态", r.usedV2)
            assertNull("无错误（由仓库层回退 v1）", r.error)
            assertTrue("不应落任何 v2 快照", store.readManifestSnapshot() == null)
        }

    @Test
    fun `首次同步下载 index 与全部图标并落盘快照与标记`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val icons = listOf(iconRef("1048"), iconRef("1049"))
            val manifest = manifestOf(icons, bundles = listOf(bundleRef("1048")))
            val fetcher = FakeObjectFetcher(filesFor(manifest))
            val engine = ManifestSyncEngine(fetcher, store)

            val r = engine.sync()

            assertTrue(r.isOk)
            assertEquals("应用了清单包", PackageKind.Manifest, r.applied)
            assertTrue(r.usedV2)
            assertEquals("index.v2.json 落盘", indexText, store.readIndexV2())
            icons.forEach { ref ->
                assertTrue("图标 ${ref.id} 落盘", store.iconFile(ref.id).exists())
                assertEquals("图标 ${ref.id} sha256 标记", ref.sha256, store.iconMarker(ref.id).readText())
            }
            val snapshot = ManifestV2Parser.parse(store.readManifestSnapshot()!!)
            assertEquals("快照保留图标清单", icons.size, snapshot.icons.size)
            assertTrue("bundle 仅记录 SHA 不下载", fetcher.downloaded.none { it.contains("bundle") })
        }

    @Test
    fun `无更新时返回 None 且仅重拉 manifest`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val icons = listOf(iconRef("1048"))
            val manifest = manifestOf(icons)
            val fetcher = FakeObjectFetcher(filesFor(manifest))
            val engine = ManifestSyncEngine(fetcher, store)

            engine.sync()
            val first = fetcher.downloaded.size
            val r2 = engine.sync()

            assertEquals("第二次无更新", PackageKind.None, r2.applied)
            assertTrue(r2.isOk)
            assertTrue(r2.usedV2)
            // manifest 每次同步都需重新拉取作为对比基准（v2 §6.1 步骤 1），
            // index / 图标不应重复下载。
            assertEquals("仅新增 1 次 manifest 拉取", first + 1, fetcher.downloaded.size)
            assertEquals("第二次仅拉 manifest", listOf(manifestRaw), fetcher.downloaded.drop(first))
            assertEquals("index 未重复下载", 1, fetcher.downloaded.count { it.contains("index.v2") })
            assertEquals("图标未重复下载", 1, fetcher.downloaded.count { it.contains("/icon.png") })
        }

    @Test
    fun `仅新增图标时只下载缺失图标`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val fetcher = FakeObjectFetcher(filesFor(manifestOf(listOf(iconRef("1048")))))
            ManifestSyncEngine(fetcher, store).sync()

            // 第二版：新增 1049 图标
            val fetcher2 = FakeObjectFetcher(filesFor(manifestOf(listOf(iconRef("1048"), iconRef("1049")))))
            val engine2 = ManifestSyncEngine(fetcher2, store)
            val r = engine2.sync()

            assertTrue(r.isOk)
            assertTrue("新增图标已下载", store.iconMarker("1049").exists())
            val iconsDownloaded = fetcher2.downloaded.filter { it.contains("/icon.png") }
            assertEquals("仅下载新增图标，1048 复用本地", listOf(iconUrl("1049")), iconsDownloaded)
        }

    @Test
    fun `下架图标与过期 bundle 缓存被清理`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            // 预置旧状态：图标 1048 + bundle 2000 已缓存
            store.iconFile("1048").parentFile?.mkdirs()
            store.iconFile("1048").writeBytes(iconData["1048"]!!)
            store.iconMarker("1048").writeText(sha256(iconData["1048"]!!))
            val oldBundleDir = store.bundleDir("2000")
            oldBundleDir.mkdirs()
            File(oldBundleDir, ".sha256").writeText("bundle-sha-2000")
            File(oldBundleDir, "detail.json").writeText("{}")

            // 新清单：图标只剩 1049，bundle 只剩 2001
            val manifest = manifestOf(listOf(iconRef("1049")), bundles = listOf(bundleRef("2001")))
            val engine = ManifestSyncEngine(FakeObjectFetcher(filesFor(manifest)), store)
            engine.sync()

            assertFalse("下架图标文件被删", store.iconFile("1048").exists())
            assertFalse("下架图标标记被删", store.iconMarker("1048").exists())
            assertFalse("过期 bundle 目录被删", oldBundleDir.exists())
            assertTrue("新图标已下载", store.iconFile("1049").exists())
        }

    @Test
    fun `图标失败标记 partial 下次同步自动补齐`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val icons = listOf(iconRef("1048"), iconRef("1049"))
            val manifest = manifestOf(icons)
            val fetcher = FakeObjectFetcher(filesFor(manifest), failUrls = setOf(iconUrl("1049")))
            val engine = ManifestSyncEngine(fetcher, store)

            val r1 = engine.sync()
            assertTrue("1049 失败仍返回成功（partial）", r1.isOk)
            assertTrue("1048 已同步", store.iconMarker("1048").exists())
            assertFalse("1049 未同步（无标记）", store.iconMarker("1049").exists())

            // 下次同步（镜像恢复）：只补齐缺失图标
            val fetcher2 = FakeObjectFetcher(filesFor(manifest))
            val engine2 = ManifestSyncEngine(fetcher2, store)
            val r2 = engine2.sync()
            assertTrue(r2.isOk)
            assertTrue("1049 补齐", store.iconMarker("1049").exists())
            assertEquals("幂等：仅下载缺失图标", listOf(iconUrl("1049")), fetcher2.downloaded.filter { it.contains("/icon.png") })
        }

    @Test
    fun `index 未变且全部图标失败时返回错误而非误报成功`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            // 预置 index 与 manifest 声明 SHA 一致，隔离「仅图标失败」场景
            store.writeIndexV2(indexText)
            val icons = listOf(iconRef("1048"), iconRef("1049"))
            val manifest = manifestOf(icons)
            val fetcher = FakeObjectFetcher(filesFor(manifest), failUrls = setOf(iconUrl("1048"), iconUrl("1049")))
            val engine = ManifestSyncEngine(fetcher, store)

            val r = engine.sync()

            assertFalse("无任何进展应报错而非误报成功", r.isOk)
            assertEquals("无进展归为网络错误", SyncError.Network, r.error)
            assertNull("不应落快照（未产生任何进展）", store.readManifestSnapshot())
            assertFalse("失败图标不应残留标记", store.iconMarker("1048").exists())
        }
}
