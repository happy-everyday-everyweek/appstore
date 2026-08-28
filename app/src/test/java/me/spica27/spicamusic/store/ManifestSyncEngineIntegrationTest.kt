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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * v2 同步引擎集成测试 —— 验证规格《AppStore 同步机制 v2 设计规格》§9 验收标准中客户端可测部分：
 *
 * - 验收 1：模拟“落后 12~18 版”（本地为旧清单、仅个别应用变化）→ 同步总下载量 < 100KB（不含懒加载 bundle）。
 *   本质：下载量只随“实际变化量”增长，与落后版本数解耦（增量 = 清单对比结果，而非构建产物）。
 * - 验收 2：首次安装（空 store，~100 应用）→ 列表数据 + 全部图标 < 1.5MB（详情全部懒加载）。
 * - 验收 5：两个连续版本间图标 SHA 不变 → 不重复下载（镜像缓存可命中）。
 *
 * 与纯单测的差异：使用真实 [SyncStore] + 真实 [ManifestSyncEngine]，仅替换网络层为
 * [MeteredObjectFetcher]（字节计量），端到端度量同步下载流量。
 */
class ManifestSyncEngineIntegrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val manifestRaw =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/dist/manifest.v2.json"
    private val indexRaw =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/dist/index.v2.json"
    private val indexRelease =
        "https://github.com/${SyncChannel.AppIndex.repo}/releases/latest/download/index.v2.json"

    /** 字节计量的假网络层：记录每个 URL 与累计下载字节数 */
    private class MeteredObjectFetcher(
        private val files: Map<String, ByteArray>,
        private val failUrls: Set<String> = emptySet(),
    ) : ObjectFetcher {
        val downloaded = mutableListOf<String>()
        var downloadedBytes = 0L

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
            downloadedBytes += data.size
            onProgress(1f)
            return dest
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun iconUrl(id: Int): String =
        "https://raw.githubusercontent.com/${SyncChannel.AppIndex.repo}/main/apps/o/r/icon.png".replace("/o/r/", "/$id/")

    /** 构造 N 个应用的列表索引；图标内容固定 10KB（模拟真实图标量级） */
    private fun makeIndex(n: Int): String {
        val entries =
            (1..n).joinToString(",") { id ->
                """{"id":"$id","name":"App $id","summary":"s","openSource":true,"grade":"D","version":{"versionName":"1.0","versionCode":$id},"icon":"$id"}"""
            }
        return "{$entries}"
    }

    private fun makeIconBytes(id: Int): ByteArray = ByteArray(10 * 1024) { (id % 251).toByte() }

    private fun makeManifest(
        ids: List<Int>,
        indexText: String,
        releaseTag: String,
    ): String {
        val m =
            ManifestV2(
                version = 2,
                channel = "app-index",
                generatedAt = "2026-08-27T04:00:17Z",
                releaseTag = releaseTag,
                index = ManifestIndexRef(sha256 = sha256(indexText.toByteArray()), size = indexText.length.toLong(), count = ids.size),
                icons = ids.map { id -> ManifestObjectRef(id = "$id", path = "apps/$id/icon.png", sha256 = sha256(makeIconBytes(id))) },
                bundles =
                    ids.map { id ->
                        ManifestObjectRef(id = "$id", url = "$releaseTag/bundles/$id.bundle.zip", sha256 = "bundle-$id")
                    },
            )
        return Json { ignoreUnknownKeys = true }.encodeToString(ManifestV2.serializer(), m)
    }

    private fun filesFor(
        manifest: String,
        indexText: String,
        ids: List<Int>,
    ): Map<String, ByteArray> =
        buildMap {
            put(manifestRaw, manifest.toByteArray())
            put(indexRaw, indexText.toByteArray())
            put(indexRelease, indexText.toByteArray()) // index 经 release 通道优先（§6.5）
            ids.forEach { id -> put(iconUrl(id), makeIconBytes(id)) }
        }

    @Test
    fun `验收1 落后多版本仅下载变化量 总量小于100KB`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val allIds = (1..100).toList()
            val oldIndex = makeIndex(100)
            val oldManifest = makeManifest(allIds, oldIndex, "dist-20260820000000")

            // 先模拟一次历史同步：本地已有旧清单 + 100 图标（落后 12~18 版的状态）
            val f0 = MeteredObjectFetcher(filesFor(oldManifest, oldIndex, allIds))
            assertTrue(ManifestSyncEngine(f0, store).sync().isOk)
            assertEquals("历史同步下载全部 100 图标", 100, f0.downloaded.count { it.contains("/icon.png") })
            assertTrue("本地图标标记齐全（幂等续传基准）", (1..100).all { store.iconMarker("$it").exists() })

            // 新版本：仅 1 个应用变更（新增 id=101 图标），其余 100 个 SHA 不变
            val newIds = allIds + 101
            val newIndex = makeIndex(101)
            val newManifest = makeManifest(newIds, newIndex, "dist-20260827120000")
            val f1 = MeteredObjectFetcher(filesFor(newManifest, newIndex, newIds))

            val r = ManifestSyncEngine(f1, store).sync()

            assertTrue(r.isOk)
            assertTrue(r.usedV2)
            assertEquals("应用了清单包", PackageKind.Manifest, r.applied)
            // 下载 = manifest + index + 新增 1 图标；必须远小于 100KB
            val limit = 100 * 1024L
            assertTrue(
                "落后多版本下载量应 < 100KB，实际 ${f1.downloadedBytes}B（URLs=${f1.downloaded}）",
                f1.downloadedBytes < limit,
            )
            assertEquals("仅下载 1 个新增图标，100 个复用本地", 1, f1.downloaded.count { it.contains("/icon.png") })
        }

    @Test
    fun `验收2 首次安装 列表数据加全部图标总量小于1500KB`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val ids = (1..100).toList()
            val indexText = makeIndex(100)
            val manifest = makeManifest(ids, indexText, "dist-20260827000000")
            val fetcher = MeteredObjectFetcher(filesFor(manifest, indexText, ids))

            val r = ManifestSyncEngine(fetcher, store).sync()

            assertTrue(r.isOk)
            assertTrue(r.usedV2)
            val limit = 1500L * 1024L
            assertTrue(
                "首次安装下载量应 < 1.5MB，实际 ${fetcher.downloadedBytes}B",
                fetcher.downloadedBytes < limit,
            )
            assertEquals("详情包不参与首次同步（懒加载）", 0, fetcher.downloaded.count { it.contains("bundle") })
        }

    @Test
    fun `验收5 连续两版图标SHA不变 则不重复下载`() =
        runTest {
            val store = SyncStore(tmp.newFolder())
            val ids = (1..10).toList()
            val indexText = makeIndex(10)
            val v1 = makeManifest(ids, indexText, "dist-20260826000000")
            val f1 = MeteredObjectFetcher(filesFor(v1, indexText, ids))
            ManifestSyncEngine(f1, store).sync()
            val firstCount = f1.downloaded.count { it.contains("/icon.png") }
            assertEquals("首版下载全部图标", 10, firstCount)

            // 第二版：仅 releaseTag 变化（模拟一次无资产变更的新发布），图标 SHA 完全不变
            val v2 = makeManifest(ids, indexText, "dist-20260827000000")
            val f2 = MeteredObjectFetcher(filesFor(v2, indexText, ids))
            val r = ManifestSyncEngine(f2, store).sync()

            assertEquals(PackageKind.None, r.applied)
            assertEquals("SHA 不变则图标零下载（镜像缓存可命中）", 0, f2.downloaded.count { it.contains("/icon.png") })
            val snapshot = ManifestV2Parser.parse(store.readManifestSnapshot()!!)
            assertEquals("快照推进到最新 tag", "dist-20260827000000", snapshot.releaseTag)
        }
}
