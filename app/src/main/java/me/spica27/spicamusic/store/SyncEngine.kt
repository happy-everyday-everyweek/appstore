package me.spica27.spicamusic.store

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
 * 市场同步引擎（深层模块，单入口）：
 * 检查 Release → 解析清单 → 增量/全量下载 → 补丁应用 → 校验 → 缓存。
 *
 * 契约（与工作流 4 / 推荐仓库发布物对齐）：
 * - full.zip 内含 index.json
 * - incremental.zip 内含 incremental.json（addedOrChanged / removed）
 * - patch.json 记录 base/target/algorithm/校验和
 *
 * 不变量：Auto 模式由调用方在后台协程执行、绝不阻塞 UI；
 * Full 模式返回前数据已落盘；重复调用幂等（无变更返回 applied=None）；
 * 异常路径均以 SyncResult.error 返回可读错误，不抛未捕获异常。
 */
class SyncEngine(
    private val github: GitHubReleaseClient,
    private val downloader: Downloader,
    private val store: SyncStore,
) {
    suspend fun sync(
        channel: SyncChannel,
        mode: SyncMode = SyncMode.Auto,
    ): SyncResult {
        val release =
            github.latestRelease(channel.repo)
                ?: return SyncResult(changed = false, applied = null, error = SyncError.Network)
        val last = store.readVersion(channel)
        if (release.tag == last && store.cacheFile(channel).exists()) {
            return SyncResult(changed = false, applied = PackageKind.None)
        }

        val patchAsset = release.asset(PATCH_JSON)
        val patch: PatchManifest? =
            patchAsset?.let {
                runCatching { PatchManifestParser.parse(downloadAsset(channel, it.downloadUrl, "patch.json")) }
                    .getOrNull()
            }
        if (patch == null && patchAsset != null) {
            return SyncResult(changed = false, applied = null, error = SyncError.ManifestInvalid)
        }

        val canIncremental =
            patch != null &&
                patch.base == last &&
                patch.algorithm == ALGORITHM &&
                release.asset(INCREMENTAL_ZIP) != null
        return if (canIncremental) {
            applyIncremental(channel, release, patch!!)
        } else {
            applyFull(channel, release, patch)
        }
    }

    private suspend fun applyIncremental(
        channel: SyncChannel,
        release: ReleaseInfo,
        patch: PatchManifest,
    ): SyncResult {
        val incAsset = release.asset(INCREMENTAL_ZIP) ?: return applyFull(channel, release, patch)
        return runCatching {
            val zip = downloader.download(incAsset.downloadUrl, tmpFile(channel, "inc.zip"), patch.incrementalSha256)
            val entries = unzipAll(zip)
            val incrementalJson =
                entries["incremental.json"]
                    ?: throw IllegalStateException("增量包缺 incremental.json")
            val base = AppIndexParser.parse(store.readCachedText(channel) ?: "")
            val change = parseIncremental(incrementalJson.decodeToString())
            var merged = ChangeDetector.apply(base, change)
            // 若增量引用了缓存中不存在的 id（异常数据），回退全量
            if (merged.size < base.size - change.removed.size) {
                zip.delete()
                return applyFull(channel, release, patch)
            }
            store.writeCachedText(channel, serializeIndex(merged))
            store.writeAssets(entries.filterKeys { it.startsWith(ASSETS_PREFIX) })
            store.writeVersion(channel, release.tag)
            zip.delete()
            SyncResult(changed = true, applied = PackageKind.Incremental)
        }.getOrElse { e ->
            SyncResult(changed = false, applied = null, error = errorOf(e))
        }
    }

    private suspend fun applyFull(
        channel: SyncChannel,
        release: ReleaseInfo,
        patch: PatchManifest?,
    ): SyncResult {
        val fullAsset =
            release.asset(FULL_ZIP)
                ?: return SyncResult(changed = false, applied = null, error = SyncError.PackageInvalid)
        return runCatching {
            val zip =
                downloader.download(
                    fullAsset.downloadUrl,
                    tmpFile(channel, "full.zip"),
                    patch?.fullSha256,
                )
            val entries = unzipAll(zip)
            val indexText =
                entries["index.json"]?.decodeToString()
                    ?: throw IllegalStateException("全量包缺 index.json")
            store.writeCachedText(channel, indexText)
            store.writeAssets(entries.filterKeys { it.startsWith(ASSETS_PREFIX) })
            store.writeVersion(channel, release.tag)
            zip.delete()
            SyncResult(changed = true, applied = PackageKind.Full)
        }.getOrElse { e ->
            SyncResult(changed = false, applied = null, error = errorOf(e))
        }
    }

    private suspend fun downloadAsset(
        channel: SyncChannel,
        url: String,
        name: String,
    ): String {
        val f = downloader.download(url, tmpFile(channel, name), null)
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
    }
}
