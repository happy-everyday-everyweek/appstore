package me.spica27.spicamusic.store

import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.common.entity.appstore.BundleDetail
import me.spica27.spicamusic.common.entity.appstore.BundleDetailParser
import me.spica27.spicamusic.common.entity.appstore.ManifestObjectRef
import me.spica27.spicamusic.common.entity.appstore.ManifestV2Parser
import me.spica27.spicamusic.store.gitlink.ObjectFetcher
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 详情包懒加载器（v2 §4.3 / §6.1 FETCH_BUNDLE）。
 *
 * 详情层数据（README / permissions / upstream / source）随应用粒度打包为
 * bundles/<id>.bundle.zip，用户进入详情页时才下载：本地无缓存或 SHA 与 manifest
 * 不一致 → 走镜像调度器下载 → 校验 SHA → 解包到 store/assets/bundles/<id>/ →
 * 解析 detail.json 并与列表元数据合并，供详情页渲染。
 *
 * URL 解析规则（§5.1：bundle 实体按 tag 寻址，历史 URL 永久有效）：
 * - 完整 URL（http…）直接用；
 * - 相对当前 Release（bundles/…）→ <releaseTag>/<url>；
 * - 携带 tag（dist-…）→ <url>/bundles/<id>.bundle.zip；
 * - 其余兜底 → <releaseTag>/bundles/<id>.bundle.zip。
 */
class BundleLoader(
    private val fetcher: ObjectFetcher,
    private val store: SyncStore,
    private val channel: SyncChannel = SyncChannel.AppIndex,
) {
    /** 从 manifest 快照取某应用的 bundle 引用（未启用 v2 / 清单无该应用 → null） */
    fun bundleRef(id: String): ManifestObjectRef? =
        readManifest()
            ?.bundleById
            ?.get(id)

    /** bundle 是否已解包且 SHA 标记与清单一致（幂等，不逐文件哈希） */
    fun isBundleCurrent(
        id: String,
        ref: ManifestObjectRef,
    ): Boolean {
        val dir = store.bundleDir(id)
        val marker = File(dir, ".sha256")
        return dir.isDirectory &&
            marker.exists() &&
            marker.readText() == ref.sha256 &&
            File(dir, "detail.json").exists()
    }

    /**
     * 下载并解包 bundle，返回 detail.json 解析结果。
     * 已解包且 SHA 一致则直接读本地；失败抛 [IOException]（携带可定位原因）。
     */
    suspend fun loadBundle(
        id: String,
        onProgress: (Float) -> Unit = {},
    ): BundleDetail {
        val ref = bundleRef(id) ?: throw IOException("清单中无该应用的 bundle 条目（id=$id）")
        if (isBundleCurrent(id, ref)) {
            DebugLog.i("Bundle", "[v2] bundle ${ref.id} 已缓存且 SHA 一致，直接使用")
            return readDetail(id)
        }
        DebugLog.i("Bundle", "[v2] 懒加载 bundle ${ref.id}：${bundleUrl(ref)}（${ref.size}B）")
        val tmp = store.bundleTmpFile(id)
        tmp.parentFile?.mkdirs()
        try {
            fetcher.download(bundleUrl(ref), tmp, ref.sha256, onProgress)
            unpack(tmp, id)
        } finally {
            tmp.delete()
        }
        File(store.bundleDir(id), ".sha256").writeText(ref.sha256)
        return readDetail(id)
    }

    /** 已解包 bundle 内的 README.md（未解包返回 null） */
    fun readmeFile(id: String): File? {
        val f = File(store.bundleDir(id), "README.md")
        return f.takeIf { it.exists() && it.isFile }
    }

    /** 合并 bundle 详情到列表元数据：详情层字段（permissions/readme/upstream/source）补齐，
     *  列表层字段（name/icon/summary/grade/version…）原样保留。 */
    fun merge(
        app: AppMeta,
        detail: BundleDetail,
    ): AppMeta =
        app.copy(
            upstream = detail.upstream ?: app.upstream,
            permissions = detail.permissions.ifEmpty { app.permissions },
            readme = if (app.readme.isBlank()) "assets/bundles/${app.id}/README.md" else app.readme,
            license = detail.source.license ?: app.license,
            apkUrl = detail.source.apkUrl.ifBlank { app.apkUrl },
            apkSha256 = detail.source.sha256.ifBlank { app.apkSha256 },
            openSource = if (detail.source.openSourceVerified) true else app.openSource,
        )

    // ---- 内部 ----

    private fun readManifest() =
        store
            .readManifestSnapshot()
            ?.let { runCatching { ManifestV2Parser.parse(it) }.getOrNull() }

    private fun manifestReleaseTag(): String = readManifest()?.releaseTag?.takeIf { it.isNotBlank() } ?: "latest"

    private fun bundleUrl(ref: ManifestObjectRef): String {
        val u = ref.url
        val base = "https://github.com/${channel.repo}/releases/download"
        return when {
            u.startsWith("http") -> u
            u.startsWith("bundles/") -> "$base/${manifestReleaseTag()}/$u"
            u.startsWith("dist-") -> "$base/$u/bundles/${ref.id}.bundle.zip"
            else -> "$base/${manifestReleaseTag()}/bundles/${ref.id}.bundle.zip"
        }
    }

    private fun readDetail(id: String): BundleDetail {
        val f = File(store.bundleDir(id), "detail.json")
        if (!f.exists()) throw IOException("bundle 解包缺 detail.json（id=$id）")
        return BundleDetailParser.parse(f.readText())
    }

    /** 解包 zip 到 store/assets/bundles/<id>/；目录先整删再重建保证原子替换语义 */
    private fun unpack(
        zip: File,
        id: String,
    ) {
        val dir = store.bundleDir(id)
        dir.deleteRecursively()
        dir.mkdirs()
        val base = dir.canonicalPath
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val target = File(dir, entry.name)
                    if (!target.canonicalPath.startsWith(base + File.separator)) {
                        throw IOException("bundle 内非法路径：${entry.name}")
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out) }
                }
                entry = zis.nextEntry
            }
        }
        if (!File(dir, "detail.json").exists()) {
            throw IOException("bundle 缺 detail.json（id=$id）")
        }
    }
}
