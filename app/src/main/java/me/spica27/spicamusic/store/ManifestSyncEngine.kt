package me.spica27.spicamusic.store

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import me.spica27.spicamusic.common.entity.appstore.ManifestObjectRef
import me.spica27.spicamusic.common.entity.appstore.ManifestV2
import me.spica27.spicamusic.common.entity.appstore.ManifestV2Parser
import me.spica27.spicamusic.store.gitlink.MirrorNotFound
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * v2 清单驱动同步引擎（替代 v1 链式增量/字节决策）：
 *
 * 1. FETCH_MANIFEST：拉取 manifest.v2.json（raw(main)/dist → release/latest 双通道）；
 *    双通道均 404 → 对方仍是 v1 形态，返回 usedV2=false 由仓库层回退旧引擎；
 *    网络故障 ≠ 404 → 返回 Network 错误（不清空本地数据）。
 * 2. COMPARE：仅比较 index SHA 与图标 SHA 差集；bundle 是懒加载对象，不参与“是否有更新”判定。
 * 3. SYNC_INDEX_AND_ICONS：index SHA 变化才拉 index.v2.json（release 通道优先 + raw 缓存滞后退避），
 *    校验 SHA 后原子替换；图标按 SHA 差集并发下载（≤6），校验通过写 sha256 标记（幂等续传）；
 *    清理下架图标与过期 bundle 缓存，最后原子提交 manifest 快照。
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

        /** raw 通道缓存滞后退避（§6.5：镜像 CDN 对 raw URL 缓存约 5 分钟，SHA 不符按 1s/5s/30s 重试） */
        val RAW_CACHE_BACKOFF_MS = listOf(1_000L, 5_000L, 30_000L)
    }

    /** 下载进度回调（0f~1f；一次同步内多个对象下载会刷新） */
    var onProgress: ((Float) -> Unit)? = null

    /** 流程阶段回调（供首启引导/设置页展示具体步骤文案，见规格 §6.4） */
    var onStage: ((String) -> Unit)? = null

    private val repo: String = channel.repo
    private val rawMainBase: String = "https://raw.githubusercontent.com/$repo/main"
    private val rawDistBase: String = "$rawMainBase/dist"
    private val releaseLatestBase: String = "https://github.com/$repo/releases/latest/download"

    suspend fun sync(): SyncResult {
        DebugLog.i("Sync", "[v2] 开始同步（${channel.name}）")
        onStage?.invoke("正在检查更新…")

        // 1. FETCH_MANIFEST
        val manifestText =
            try {
                fetchManifestText()
            } catch (e: IOException) {
                val msg = "manifest.v2.json 拉取失败（网络/镜像均不可达）：${e.message ?: e::class.simpleName}"
                DebugLog.e("Sync", "[v2] $msg")
                return networkError(msg)
            }
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
        // index 以本地文件实际 SHA 与 manifest 比对：缺失 / 内容损坏 / 版本滞后均视为需重拉
        val snapshot = readSnapshot()
        val indexChanged = indexV2Sha() != manifest.index.sha256
        val missingIcons = manifest.icons.filterNot { isIconCurrent(it) }
        if (!indexChanged && missingIcons.isEmpty()) {
            // 无资产变化：仍将快照推进到最新 manifest（manifest 是唯一版本真相，tag 可能已更新）
            if (snapshot?.releaseTag != manifest.releaseTag) {
                store.writeManifestSnapshot(manifestText)
            }
            if (manifest.releaseTag.isNotBlank()) store.writeVersion(channel, manifest.releaseTag)
            DebugLog.i("Sync", "[v2] ${channel.name} 无更新（index 一致、图标齐全）")
            return SyncResult(changed = false, applied = PackageKind.None, usedV2 = true)
        }

        // 3. SYNC_INDEX_AND_ICONS
        var indexUpdated = false
        if (indexChanged) {
            onStage?.invoke("正在同步应用列表…")
            val indexText =
                try {
                    fetchIndex(manifest.index.sha256)
                } catch (e: IOException) {
                    val msg = "index.v2.json 拉取失败（网络/镜像均不可达）：${e.message ?: e::class.simpleName}"
                    DebugLog.e("Sync", "[v2] $msg")
                    return networkError(msg)
                }
            if (indexText == null) {
                val msg = "index.v2.json 拉取失败（raw/release 双通道均不可达或 SHA 校验不过）"
                DebugLog.e("Sync", "[v2] $msg")
                return networkError(msg)
            }
            store.writeIndexV2(indexText)
            indexUpdated = true
            DebugLog.i("Sync", "[v2] index.v2.json 已更新（${indexText.length} 字节，SHA=${manifest.index.sha256.take(8)}…）")
        }
        var iconSynced = 0
        if (missingIcons.isNotEmpty()) {
            iconSynced = syncIcons(missingIcons)
        }
        // 无任何进展（index 未变且所有缺失图标均失败）→ 报错而非误报成功（partial 全败，下次自动续）
        if (!indexUpdated && missingIcons.isNotEmpty() && iconSynced == 0) {
            val msg = "v2 同步无进展：待同步图标（${missingIcons.size} 个）全部下载失败且 index 未变化"
            DebugLog.e("Sync", "[v2] $msg")
            return SyncResult(changed = false, applied = null, error = SyncError.Network, errorMessage = msg, usedV2 = true)
        }
        // 清理：下架图标 / 过期 bundle 缓存（bundle 仅对比 SHA 表，不下载）
        store.deleteStaleIcons(manifest.icons.map { it.id }.toSet())
        store.deleteStaleBundles(manifest.bundleById.mapValues { it.value.sha256 })

        // 4. 原子提交快照（对比基准 = 本次成功同步的清单）
        store.writeManifestSnapshot(manifestText)
        if (manifest.releaseTag.isNotBlank()) store.writeVersion(channel, manifest.releaseTag)
        return SyncResult(changed = true, applied = PackageKind.Manifest, usedV2 = true)
    }

    // ---- FETCH_MANIFEST ----

    /** 统一 Network 错误结果（usedV2=true：v2 协议已探测到，仅网络失败） */
    private fun networkError(msg: String): SyncResult =
        SyncResult(
            changed = false,
            applied = null,
            error = SyncError.Network,
            errorMessage = msg,
            usedV2 = true,
        )

    /**
     * 双通道拉取 manifest：raw 优先，404 回落 release；release 通道 404 → 返回 null（v1 回退）。
     * release 是协议判定权威（§4.4：releases/latest/download/manifest.v2.json 200→v2 / 404→v1）：
     * 即使 raw 通道网络故障，只要 release 明确 404 即按 v1 处理，不误报 Network。
     */
    private suspend fun fetchManifestText(): String? {
        var sawNetworkError: IOException? = null
        for ((url, isRaw) in listOf("$rawDistBase/manifest.v2.json" to true, "$releaseLatestBase/manifest.v2.json" to false)) {
            try {
                fetchValidated(url, isRaw, null)?.let { return it }
            } catch (e: MirrorNotFound) {
                if (!isRaw) return null // release 404 → 权威判定对方为 v1 形态
                // raw 404 → 试 release 通道
            } catch (e: IOException) {
                sawNetworkError = e
            }
        }
        if (sawNetworkError != null) throw sawNetworkError
        return null
    }

    /**
     * 拉取 index.v2.json 并校验 manifest 声明的 SHA。
     * 列表功能经 release 通道优先（§6.5：raw CDN 缓存滞后不影响列表）；
     * raw 通道在 SHA 缓存滞后时按 1s/5s/30s 退避重试（每次退避相当于换候选集），
     * 仍失败返回 null 由调用方转 Network 错误。
     */
    private suspend fun fetchIndex(expectedSha: String): String? {
        try {
            fetchValidated("$releaseLatestBase/index.v2.json", isRaw = false, expectedSha)?.let { return it }
        } catch (e: MirrorNotFound) {
            // release 通道无此资产，走 raw 兜底
        } catch (e: IOException) {
            // release 通道网络故障（镜像拒绝/超时/HTML 挑战页），同样走 raw 兜底（§6.5），
            // 而非直接冒泡成 Network 错误
        }
        return fetchRawWithBackoff("$rawDistBase/index.v2.json", expectedSha)?.toString(Charsets.UTF_8)
    }

    /**
     * raw 通道资产下载 + 缓存滞后退避（§6.5：manifest 已拿到新 SHA 而镜像 CDN 仍返回旧内容
     * → SHA 校验失败 → 按 1s/5s/30s 退避重试；最后仍失败返回 null）。404 视为通道不可用直接返回 null。
     * icons 与 index 的 raw 通道共用；调度器内置 SHA 校验，此处再防御性复核。
     */
    private suspend fun fetchRawWithBackoff(
        url: String,
        expectedSha: String,
    ): ByteArray? {
        for ((i, backoff) in (listOf(0L) + RAW_CACHE_BACKOFF_MS).withIndex()) {
            if (backoff > 0) delay(backoff)
            try {
                val bytes = fetchBytes(url, isRaw = true, expectedSha)
                if (sha256(bytes) == expectedSha) return bytes
                // SHA 不符（镜像 CDN 缓存滞后）→ 退避重试，§6.5
            } catch (e: MirrorNotFound) {
                return null // 404 → 通道不可用，无更多来源
            } catch (e: IOException) {
                // 网络抖动 → 退避重试
            }
            if (i >= RAW_CACHE_BACKOFF_MS.size) return null
        }
        return null
    }

    /** 经镜像调度器下载对象并读回字节；404 抛 [MirrorNotFound]，网络故障抛 [IOException] */
    private suspend fun fetchBytes(
        url: String,
        isRaw: Boolean,
        expectedSha: String?,
    ): ByteArray {
        val dir = store.bundleTmpFile("probe").parentFile
        dir?.mkdirs()
        val tmp = File.createTempFile("v2-", ".bin", dir)
        try {
            fetcher.download(url, tmp, expectedSha, onProgress = { p -> onProgress?.invoke(p) }, isRaw = isRaw)
            return tmp.readBytes()
        } finally {
            tmp.delete()
        }
    }

    /**
     * 下载并校验 SHA：符合预期（或无需校验）返回文本；404 抛 [MirrorNotFound]。
     * 调度器已内置 SHA 校验并降级不合格镜像，此处防御性复检，SHA 不符返回 null（缓存滞后）。
     */
    private suspend fun fetchValidated(
        url: String,
        isRaw: Boolean,
        expectedSha: String?,
    ): String? {
        val bytes = fetchBytes(url, isRaw, expectedSha)
        if (expectedSha == null || sha256(bytes) == expectedSha) return bytes.toString(Charsets.UTF_8)
        return null
    }

    // ---- COMPARE ----

    private fun readSnapshot(): ManifestV2? = store.readManifestSnapshot()?.let { runCatching { ManifestV2Parser.parse(it) }.getOrNull() }

    /** 本地 index.v2.json 的 SHA-256（缺失/损坏 → null，触发重拉） */
    private fun indexV2Sha(): String? = store.readIndexV2()?.let { sha256(it.toByteArray()) }

    /** 图标当前判定：文件存在 + sha256 标记与 manifest 一致（幂等，不逐文件哈希） */
    private fun isIconCurrent(ref: ManifestObjectRef): Boolean {
        val f = store.iconFile(ref.id)
        val marker = store.iconMarker(ref.id)
        return f.exists() && f.length() > 0 && marker.exists() && marker.readText() == ref.sha256
    }

    // ---- SYNC_INDEX_AND_ICONS ----

    /** 批量下载缺失图标（并发 ≤6），返回成功数（供 partial 无进展判定） */
    private suspend fun syncIcons(icons: List<ManifestObjectRef>): Int {
        var done = 0
        var ok = 0
        val total = icons.size
        onStage?.invoke("正在补齐图标（0/$total）…")
        icons.chunked(ICON_CONCURRENCY).forEach { batch ->
            coroutineScope { batch.map { ref -> async { if (downloadIcon(ref)) ok++ } }.awaitAll() }
            done += batch.size
            onStage?.invoke("正在补齐图标（$done/$total）…")
        }
        return ok
    }

    /**
     * 单个图标下载：raw 通道 + 缓存滞后退避（§6.5）。成功写 sha256 标记；
     * 失败返回 false（标记未写 → 下次同步差集逻辑幂等补齐），不抛异常中断整批。
     */
    private suspend fun downloadIcon(ref: ManifestObjectRef): Boolean {
        val url = "$rawMainBase/${ref.path}"
        val dest = store.iconFile(ref.id)
        try {
            val bytes = fetchRawWithBackoff(url, ref.sha256) ?: return false
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
            store.iconMarker(ref.id).writeText(ref.sha256)
            DebugLog.i("Sync", "[v2] 图标 ${ref.id} 已同步（${dest.length()}B）")
            return true
        } catch (e: Exception) {
            // 图标批量下载中途失败 → 本次同步标记 partial，下次自动续（标记未写，差集逻辑幂等）
            DebugLog.w("Sync", "[v2] 图标 ${ref.id} 同步失败：${e.message ?: e::class.simpleName}（partial，下次自动续）")
            runCatching { dest.delete() }
            return false
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
