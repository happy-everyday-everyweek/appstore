package me.spica27.spicamusic.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.spica27.spicamusic.common.entity.appstore.ManifestObjectRef
import me.spica27.spicamusic.common.entity.appstore.ManifestV2
import me.spica27.spicamusic.common.entity.appstore.ManifestV2Parser
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import java.io.File
import java.security.MessageDigest

/**
 * v2 清单驱动同步引擎（替代 v1 链式增量/字节决策）：
 *
 * 1. FETCH_MANIFEST：拉取 manifest.v2.json（raw(main)/dist → release/latest 双通道）；
 *    双通道均 404 → 对方仍是 v1 形态，返回 usedV2=false 由仓库层回退旧引擎。
 * 2. COMPARE：仅比较 index SHA 与图标 SHA 差集；bundle 是懒加载对象，不参与“是否有更新”判定。
 * 3. SYNC_INDEX_AND_ICONS：index SHA 变化才拉 index.v2.json；图标按 SHA 差集并发下载（≤6），
 *    校验通过写 sha256 标记（幂等续传）；清理下架图标与过期 bundle 缓存，最后原子提交 manifest 快照。
 *
 * 与落后版本数无关：落后 1 版与落后 100 版流程完全相同（增量 = 清单对比结果，而非构建产物）。
 */
class ManifestSyncEngine(
    private val fetcher: ObjectFetcher,
    private val store: SyncStore,
    private val channel: SyncChannel = SyncChannel.AppIndex,
) {
    companion object {
        const val ICON_CONCURRENCY = 6
    }

    /** 下载进度回调（0f~1f；一次同步内多个对象下载会刷新） */
    var onProgress: ((Float) -> Unit)? = null

    /** 流程阶段回调（供首启引导/设置页展示具体步骤文案，见规格 §6.4） */
    var onStage: ((String) -> Unit)? = null

    private val repo: String = channel.repo
    private val rawMainBase: String = "https://raw.githubusercontent.com/$repo/main"
    private val rawDistBase: String = "$rawMainBase/dist"
    private val releaseLatestBase: String = "https://github.com/$repo/releases/latest/download"

    suspend fun sync(mode: SyncMode = SyncMode.Auto): SyncResult {
        DebugLog.i("Sync", "[v2] 开始同步（${channel.name}）模式=$mode")
        onStage?.invoke("正在检查更新…")

        // 1. FETCH_MANIFEST
        val manifestText = fetchManifestText()
        if (manifestText == null) {
            DebugLog.i("Sync", "[v2] ${channel.name} 无 manifest.v2.json（对方仍为 v1 形态），回退旧引擎")
            return SyncResult(changed = false, applied = null, usedV2 = false)
        }
        val manifest =
            try {
                ManifestV2Parser.parse(manifestText)
            } catch (e: Exception) {
                val msg = "manifest.v2.json 解析失败：${e.message ?: e::class.simpleName}"
                DebugLog.e("Sync", "[v2] $msg")
                return SyncResult(
                    changed = false,
                    applied = null,
                    error = SyncError.ManifestInvalid,
                    errorMessage = msg,
                    usedV2 = true,
                )
            }
        DebugLog.i(
            "Sync",
            "[v2] manifest：${manifest.icons.size} 图标 / ${manifest.bundles.size} 详情包 / index=${manifest.index.count} 应用 / tag=${manifest.releaseTag}",
        )

        // 2. COMPARE（index + 图标；bundle 不参与更新判定）
        val snapshot = readSnapshot()
        val indexChanged = snapshot?.index?.sha256 != manifest.index.sha256 || store.readIndexV2() == null
        val missingIcons = manifest.icons.filterNot { isIconCurrent(it) }
        if (!indexChanged && missingIcons.isEmpty()) {
            DebugLog.i("Sync", "[v2] ${channel.name} 无更新（index 一致、图标齐全）")
            return SyncResult(changed = false, applied = PackageKind.None, usedV2 = true)
        }

        // 3. SYNC_INDEX_AND_ICONS
        if (indexChanged) {
            onStage?.invoke("正在同步应用列表…")
            val indexText = fetchIndex(manifest.index.sha256)
            if (indexText == null) {
                val msg = "index.v2.json 拉取失败（raw/release 双通道均不可达）"
                DebugLog.e("Sync", "[v2] $msg")
                return SyncResult(
                    changed = false,
                    applied = null,
                    error = SyncError.Network,
                    errorMessage = msg,
                    usedV2 = true,
                )
            }
            store.writeIndexV2(indexText)
            DebugLog.i("Sync", "[v2] index.v2.json 已更新（${indexText.length} 字节，SHA=${manifest.index.sha256.take(8)}…）")
        }
        if (missingIcons.isNotEmpty()) {
            syncIcons(missingIcons)
        }
        // 清理：下架图标 / 过期 bundle 缓存（bundle 仅对比 SHA 表，不下载）
        store.deleteStaleIcons(manifest.icons.map { it.id }.toSet())
        store.deleteStaleBundles(manifest.bundleById.mapValues { it.value.sha256 })

        // 4. 原子提交快照（对比基准 = 本次成功同步的清单）
        store.writeManifestSnapshot(manifestText)
        return SyncResult(changed = true, applied = PackageKind.Manifest, usedV2 = true)
    }

    // ---- FETCH_MANIFEST ----

    /** 双通道拉取 manifest：raw 优先，404/失败回落 release；均失败返回 null（v1 回退） */
    private suspend fun fetchManifestText(): String? {
        fetchBytes("$rawDistBase/manifest.v2.json", isRaw = true)?.let { return it.toString(Charsets.UTF_8) }
        return fetchBytes("$releaseLatestBase/manifest.v2.json", isRaw = false)?.toString(Charsets.UTF_8)
    }

    /** 拉取 index.v2.json 并校验 manifest 声明的 SHA；双通道，缓存滞后（SHA 不符）自动切通道 */
    private suspend fun fetchIndex(expectedSha: String): String? {
        fetchBytes("$rawDistBase/index.v2.json", isRaw = true)?.let {
            if (sha256(it) == expectedSha) return it.toString(Charsets.UTF_8)
        }
        fetchBytes("$releaseLatestBase/index.v2.json", isRaw = false)?.let {
            if (sha256(it) == expectedSha) return it.toString(Charsets.UTF_8)
        }
        return null
    }

    /** 经镜像调度器下载对象到临时文件并读回字节；任一元失败返回 null */
    private suspend fun fetchBytes(
        url: String,
        isRaw: Boolean,
    ): ByteArray? =
        runCatching {
            val dir = store.bundleTmpFile("probe").parentFile
            dir?.mkdirs()
            val tmp = File.createTempFile("v2-", ".bin", dir)
            try {
                fetcher.download(url, tmp, null, onProgress = { p -> onProgress?.invoke(p) }, isRaw = isRaw)
                tmp.readBytes()
            } finally {
                tmp.delete()
            }
        }.getOrNull()

    // ---- COMPARE ----

    private fun readSnapshot(): ManifestV2? = store.readManifestSnapshot()?.let { runCatching { ManifestV2Parser.parse(it) }.getOrNull() }

    /** 图标当前判定：文件存在 + sha256 标记与 manifest 一致（幂等，不逐文件哈希） */
    private fun isIconCurrent(ref: ManifestObjectRef): Boolean {
        val f = store.iconFile(ref.id)
        val marker = store.iconMarker(ref.id)
        return f.exists() && f.length() > 0 && marker.exists() && marker.readText() == ref.sha256
    }

    // ---- SYNC_INDEX_AND_ICONS ----

    private suspend fun syncIcons(icons: List<ManifestObjectRef>) {
        var done = 0
        val total = icons.size
        onStage?.invoke("正在补齐图标（0/$total）…")
        icons.chunked(ICON_CONCURRENCY).forEach { batch ->
            coroutineScope { batch.map { ref -> async { downloadIcon(ref) } }.awaitAll() }
            done += batch.size
            onStage?.invoke("正在补齐图标（$done/$total）…")
        }
    }

    private suspend fun downloadIcon(ref: ManifestObjectRef) {
        val url = "$rawMainBase/${ref.path}"
        val dest = store.iconFile(ref.id)
        try {
            fetcher.download(url, dest, ref.sha256, onProgress = { p -> onProgress?.invoke(p) }, isRaw = true)
            store.iconMarker(ref.id).writeText(ref.sha256)
            DebugLog.i("Sync", "[v2] 图标 ${ref.id} 已同步（${dest.length()}B）")
        } catch (e: Exception) {
            // 图标批量下载中途失败 → 本次同步标记 partial，下次自动续（标记未写，差集逻辑幂等）
            DebugLog.w("Sync", "[v2] 图标 ${ref.id} 同步失败：${e.message ?: e::class.simpleName}（partial，下次自动续）")
            runCatching { dest.delete() }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
