package me.spica27.spicamusic.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.spica27.spicamusic.common.entity.appstore.AppIndex
import me.spica27.spicamusic.common.entity.appstore.AppIndexParser
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.common.entity.appstore.ChangeDetector
import me.spica27.spicamusic.common.entity.appstore.ChangeSet
import me.spica27.spicamusic.common.entity.appstore.PatchManifest
import me.spica27.spicamusic.common.entity.appstore.PatchManifestParser
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 市场同步引擎（全 GitLink 直链模式，零 GitHub API）：
 * - Release 发现 = "/releases/latest/download/patch.json" 直链（GitHub 官方 latest 重定向）
 * - 增量包 / 全量包 = 同域名直链资产
 * - 下载全部经 [Downloader]（GitLink 33 镜像测速、断点续传、空文件换源）
 * - 生命周期埋点进 [DebugLog]，失败携带 errorMessage（URL/镜像/解析详情）
 */
class SyncEngine(
    private val downloader: Downloader,
    private val store: SyncStore,
) {
    /** 下载进度回调（0f~1f；一次同步内多次资产下载会刷新） */
    var onProgress: ((Float) -> Unit)? = null

    /** 流程阶段回调（供首启引导/设置页展示具体步骤文案） */
    var onStage: ((String) -> Unit)? = null

    /** 桥接 GitLink 下载器的内部阶段（测速/逐镜像尝试） */
    init {
        (downloader as? me.spica27.spicamusic.store.gitlink.GitLinkDownloader)
            ?.onStage = { s -> onStage?.invoke(s) }
    }

    suspend fun sync(
        channel: SyncChannel,
        mode: SyncMode = SyncMode.Auto,
    ): SyncResult {
        val baseUrl = "https://github.com/${channel.repo}/releases/latest/download"
        val last = store.readVersion(channel)
        DebugLog.i("Sync", "[${channel.name}] 直链源=$baseUrl 本地版本=$last 模式=$mode")
        onStage?.invoke("正在获取更新清单 patch.json（${channel.name}）…")
        val patchText =
            try {
                downloadAsset(channel, "$baseUrl/patch.json", "patch.json").also {
                    DebugLog.i("Sync", "[${channel.name}] patch.json 已获取（${it.length} 字节）")
                }
            } catch (e: Exception) {
                val msg = "拉取更新解析清单失败（$baseUrl/patch.json）：${e.message ?: e::class.simpleName}"
                DebugLog.e("Sync", "[${channel.name}] $msg")
                return SyncResult(
                    changed = false,
                    applied = null,
                    error = SyncError.Network,
                    errorMessage = msg,
                )
            }
        val patch =
            runCatching { PatchManifestParser.parse(patchText) }.getOrNull()
                ?: run {
                    val preview = patchText.take(120).replace('\n', ' ')
                    val msg = "更新解析清单（patch.json）解析失败：前 120 字「$preview」"
                    DebugLog.e("Sync", "[${channel.name}] $msg")
                    return SyncResult(
                        changed = false,
                        applied = null,
                        error = SyncError.ManifestInvalid,
                        errorMessage = msg,
                    )
                }
        DebugLog.i(
            "Sync",
            "[${channel.name}] 清单: base=${patch.base} target=${patch.target} algo=${patch.algorithm}",
        )
        // target 为空（历史 Release 数据缺失）时不得判定“无更新”，必须走全量；
        // 另：即使版本一致，本地资产（图标/README）缺失时也强制全量补齐（历史增量包不带资产的兜底）
        val missingAssets = assetsMissing(channel)
        if (missingAssets) {
            DebugLog.w("Sync", "[${channel.name}] 本地资产缺失，忽略版本一致并强制全量补齐")
        }
        if (!patch.target.isNullOrBlank() && patch.target == last && store.cacheFile(channel).exists() && !missingAssets) {
            DebugLog.i("Sync", "[${channel.name}] 版本一致，无需更新")
            return SyncResult(changed = false, applied = PackageKind.None)
        }
        val canIncremental = patch.base == last && patch.algorithm == ALGORITHM
        if (canIncremental) {
            DebugLog.i("Sync", "[${channel.name}] 选择 增量包")
            onStage?.invoke("正在下载增量数据（${channel.name}）…")
            return applyIncremental(channel, baseUrl, baseUrl, patch)
        }
        // 直接增量不可用（latest.base 与本地版本不匹配）：解析增量路径，
        // 沿 base 逐级回溯历史 Release 的 patch.json，直到某级 base 与本地版本匹配，
        // 再按路径顺序（旧→新）逐个下载并应用增量包；任一级失败回退最新全量包。
        val chain = resolveIncrementalChain(channel, last, patch)
        if (chain != null) {
            DebugLog.i(
                "Sync",
                "[${channel.name}] 选择 链式增量包（${chain.size} 级：${chain.joinToString(" -> ") { it.target ?: "?" }}）",
            )
            return applyChain(channel, chain, baseUrl, patch)
        }
        DebugLog.i("Sync", "[${channel.name}] 选择 全量包")
        onStage?.invoke("正在下载全量数据（${channel.name}）…")
        return applyFull(channel, baseUrl, patch)
    }

    /** 解析增量路径：从 latest patch 沿 base 逐级回溯上一 Release 的 patch.json，
     *  直到某级 base 与本地版本匹配；返回按应用顺序（旧→新）的完整增量链。 */
    private suspend fun resolveIncrementalChain(
        channel: SyncChannel,
        last: String?,
        latest: PatchManifest,
    ): List<PatchManifest>? {
        if (latest.algorithm != ALGORITHM || latest.base.isNullOrBlank() || latest.base == "none") return null
        val chain = ArrayDeque<PatchManifest>()
        var cur = latest
        var guard = 0
        while (guard++ < MAX_CHAIN_LENGTH) {
            val curBase = cur.base ?: return null
            chain.addFirst(cur)
            if (curBase == last) return chain.toList()
            val prevText =
                try {
                    downloadAsset(
                        channel,
                        releaseBaseUrl(channel, curBase) + "/patch.json",
                        "patch-$curBase.json",
                    )
                } catch (e: Exception) {
                    DebugLog.i(
                        "Sync",
                        "[${channel.name}] 链式回溯 $curBase/patch.json 失败(${e.message})，改走全量",
                    )
                    return null
                }
            val prev = runCatching { PatchManifestParser.parse(prevText) }.getOrNull() ?: return null
            // 链连续性校验：上一级 target 必须等于本级 base，且算法一致
            if (prev.target != curBase ||
                prev.algorithm != ALGORITHM ||
                prev.base.isNullOrBlank() ||
                prev.base == "none"
            ) {
                DebugLog.i(
                    "Sync",
                    "[${channel.name}] 增量链断裂于 $curBase（prev.target=${prev.target}），改走全量",
                )
                return null
            }
            cur = prev
        }
        return null
    }

    /** 按增量路径顺序应用每一级增量包；任一级失败（已内部回退最新全量）即终止并返回其结果 */
    private suspend fun applyChain(
        channel: SyncChannel,
        chain: List<PatchManifest>,
        fullFallbackUrl: String,
        latestPatch: PatchManifest,
    ): SyncResult {
        chain.forEachIndexed { i, p ->
            val target = p.target ?: return applyFull(channel, fullFallbackUrl, latestPatch)
            DebugLog.i("Sync", "[${channel.name}] 链式应用第 ${i + 1}/${chain.size} 级增量（${p.base} -> $target）")
            onStage?.invoke("正在下载增量数据（${channel.name}，${i + 1}/${chain.size}）…")
            val r = applyIncremental(channel, releaseBaseUrl(channel, target), fullFallbackUrl, p)
            if (r.applied != PackageKind.Incremental) {
                // 某级增量失败且已回退最新全量成功：全量内容为最新，版本号需记录为最新 target
                if (r.applied == PackageKind.Full) {
                    store.writeVersion(channel, latestPatch.target ?: latestPatch.base ?: "")
                }
                return r
            }
        }
        return SyncResult(changed = true, applied = PackageKind.Incremental)
    }

    /** 指定 Release tag 的资产直链基址 */
    private fun releaseBaseUrl(
        channel: SyncChannel,
        tag: String,
    ): String = "https://github.com/${channel.repo}/releases/download/$tag"

    private suspend fun applyIncremental(
        channel: SyncChannel,
        releaseUrl: String,
        fullFallbackUrl: String,
        patch: PatchManifest,
    ): SyncResult {
        if (patch.incrementalSha256.isBlank()) return applyFull(channel, fullFallbackUrl, patch)
        return runCatching {
            val zip =
                downloader.download(
                    "$releaseUrl/incremental.zip",
                    tmpFile(channel, "inc.zip"),
                    patch.incrementalSha256.takeIf { it.isNotBlank() },
                    onProgress = { onProgress?.invoke(it) },
                )
            val entries = unzipAll(zip)
            val incrementalJson =
                entries["incremental.json"]
                    ?: throw IllegalStateException("增量包缺 incremental.json")
            val base = AppIndexParser.parse(store.readCachedText(channel) ?: "")
            val change = parseIncremental(incrementalJson.decodeToString())
            val merged = ChangeDetector.apply(base, change)
            if (merged.size < base.size - change.removed.size) {
                zip.delete()
                return applyFull(channel, fullFallbackUrl, patch)
            }
            store.writeCachedText(channel, serializeIndex(merged))
            // 兜底：增量包若不含任何资产（历史发布物），补齐资产必须走全量
            if (!hasAssetEntries(entries)) {
                zip.delete()
                DebugLog.i("Sync", "[${channel.name}] 增量包无资产，回退全量以补齐图标/README/文章/封面")
                return applyFull(channel, fullFallbackUrl, patch)
            }
            store.writeAssets(entries.filterKeys { it != "incremental.json" })
            store.writeVersion(channel, patch.target ?: patch.base ?: "")
            zip.delete()
            SyncResult(changed = true, applied = PackageKind.Incremental)
        }.getOrElse { e ->
            // 增量包获取/解析/校验失败一律兜底全量（保证同步不空转、数据一致）；
            // 若全量也失败，把增量原因一并透传，便于定位真实故障
            DebugLog.i("Sync", "[${channel.name}] 增量失败(${e.message})，回退全量")
            val full = applyFull(channel, fullFallbackUrl, patch)
            if (full.error == null) {
                full
            } else {
                full.copy(
                    errorMessage =
                        "增量包失败(${e.message ?: e::class.simpleName})，且全量包失败：${full.errorMessage}",
                )
            }
        }
    }

    private suspend fun applyFull(
        channel: SyncChannel,
        baseUrl: String,
        patch: PatchManifest,
    ): SyncResult =
        runCatching {
            val zip =
                downloader.download(
                    "$baseUrl/full.zip",
                    tmpFile(channel, "full.zip"),
                    patch.fullSha256.takeIf { it.isNotBlank() },
                    onProgress = { onProgress?.invoke(it) },
                )
            val entries = unzipAll(zip)
            val indexText =
                entries["index.json"]?.decodeToString()
                    ?: throw IllegalStateException("全量包缺 index.json")
            store.writeCachedText(channel, indexText)
            // 资产键可能带 assets/ 前缀（聚合包）或不带（历史推荐包），统一落盘兼容
            store.writeAssets(entries.filterKeys { it != "index.json" })
            store.writeVersion(channel, patch.target ?: patch.base ?: "")
            zip.delete()
            SyncResult(changed = true, applied = PackageKind.Full)
        }.getOrElse { e ->
            SyncResult(
                changed = false,
                applied = null,
                error = errorOf(e),
                errorMessage = "全量包处理失败：${e.message ?: e::class.simpleName}",
            )
        }

    private suspend fun downloadAsset(
        channel: SyncChannel,
        url: String,
        name: String,
    ): String {
        val f = downloader.download(url, tmpFile(channel, name), null, onProgress = { onProgress?.invoke(it) })
        val text = f.readText()
        f.delete()
        return text
    }

    private fun tmpFile(
        channel: SyncChannel,
        name: String,
    ): File = File(store.cacheFile(channel).parentFile, "${channel.name}-$name")

    private fun errorOf(e: Throwable): SyncError =
        when (e) {
            is java.io.IOException -> SyncError.Network
            is java.security.GeneralSecurityException -> SyncError.ChecksumMismatch
            is kotlinx.serialization.SerializationException -> SyncError.PackageInvalid
            else -> SyncError.PackageInvalid
        }

    private fun parseIncremental(text: String): ChangeSet {
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(text).jsonObject
        val added =
            (root["addedOrChanged"] as? kotlinx.serialization.json.JsonObject)
                ?.let { AppIndexParser.parse(it.toString()) } ?: emptyMap()
        val removed =
            (root["removed"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList()
        return ChangeSet(addedOrChanged = added, removed = removed)
    }

    /** 本地资产完整性检查：缓存内前 5 个应用的图标/README 引用文件缺失时需全量补齐 */
    private fun assetsMissing(channel: SyncChannel): Boolean {
        val text = store.readCachedText(channel) ?: return false
        val refs = mutableListOf<String>()
        runCatching {
            val root = Json.parseToJsonElement(text).jsonObject
            (root["apps"]?.jsonObject ?: root).values.take(5).forEach { v ->
                val o = v.jsonObject
                (o["icon"]?.jsonPrimitive?.contentOrNull)
                    ?.takeIf { it.startsWith(ASSETS_PREFIX) }
                    ?.let { refs += it.removePrefix(ASSETS_PREFIX) }
                (o["readme"]?.jsonPrimitive?.contentOrNull)
                    ?.takeIf { it.startsWith(ASSETS_PREFIX) }
                    ?.let { refs += it.removePrefix(ASSETS_PREFIX) }
            }
        }
        if (refs.isEmpty()) return false
        return refs.any { !store.assetFile(it).exists() }
    }

    private fun serializeIndex(index: AppIndex): String {
        val json = Json { ignoreUnknownKeys = true }
        return buildString {
            append('{')
            index.entries.forEachIndexed { i, (id, meta) ->
                if (i > 0) append(',')
                append('"').append(id).append("\":").append(json.encodeToString<AppMeta>(meta))
            }
            append('}')
        }
    }

    private fun unzipAll(zip: File): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                entry = zis.nextEntry
            }
        }
        return entries
    }

    companion object {
        const val ALGORITHM = "structured-json-v1"
        const val PATCH_JSON = "patch.json"
        const val FULL_ZIP = "full.zip"
        const val INCREMENTAL_ZIP = "incremental.zip"
        const val ASSETS_PREFIX = "assets/"

        /** 链式增量回溯最大级数（防异常链路死循环） */
        const val MAX_CHAIN_LENGTH = 20

        /** 解压条目中是否存在资产（index/incremental 之外的键，兼容带/不带 assets/ 前缀） */
        private fun hasAssetEntries(entries: Map<String, ByteArray>): Boolean =
            entries.keys.any { it != "index.json" && it != "incremental.json" && it != "patch.json" }
    }
}
