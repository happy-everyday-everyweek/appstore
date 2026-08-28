package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * manifest.v2.json —— v2 同步入口清单。
 *
 * 设计要点（见《AppStore 同步机制 v2 设计规格》§4.1）：
 * - manifest 是唯一"版本真相"，客户端不比对时间戳，只比对 SHA-256；
 * - index 引用列表索引的 SHA；icons 为列表页必需资产（独立小文件）；
 *   bundles 为详情页懒加载资产（应用粒度，URL 内容稳定，镜像缓存可长期命中）；
 * - 删除集不需要显式字段：本地清单与 manifest 的差集即删除集。
 */
@Serializable
data class ManifestV2(
    val version: Int = 2,
    val channel: String = "",
    val generatedAt: String = "",
    val commit: String = "",
    val releaseTag: String = "",
    val index: ManifestIndexRef = ManifestIndexRef(),
    val icons: List<ManifestObjectRef> = emptyList(),
    val bundles: List<ManifestObjectRef> = emptyList(),
) {
    val iconById: Map<String, ManifestObjectRef> get() = icons.associateBy { it.id }
    val bundleById: Map<String, ManifestObjectRef> get() = bundles.associateBy { it.id }
}

/** manifest.index —— index.v2.json 引用 */
@Serializable
data class ManifestIndexRef(
    val sha256: String = "",
    val size: Long = 0,
    val count: Int = 0,
)

/** manifest.icons[] / manifest.bundles[] 通用对象条目 */
@Serializable
data class ManifestObjectRef(
    val id: String = "",
    /** icons：仓库内相对路径（apps/<owner>/<repo>/icon.png）；bundles：相对资产名 */
    val path: String = "",
    /** bundles：携带 tag 限定的相对 URL（dist-<ts>/bundles/<id>.bundle.zip），或完整 URL */
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
)

object ManifestV2Parser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): ManifestV2 =
        json.decodeFromString(ManifestV2.serializer(), text)
}

/**
 * bundle 内 detail.json —— 详情层元数据（permissions / readme / upstream / source）。
 * 列表索引 index.v2.json 不含这些字段（详情页点开才经 bundle 懒加载补齐）。
 */
@Serializable
data class BundleDetail(
    val upstream: Int? = null,
    val permissions: List<String> = emptyList(),
    val readme: String = "README.md",
    val source: BundleSource = BundleSource(),
) {
    val upstreamId: String? get() = upstream?.toString()
}

@Serializable
data class BundleSource(
    val license: String? = null,
    val apkUrl: String = "",
    val sha256: String = "",
    val openSourceVerified: Boolean = false,
)

object BundleDetailParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): BundleDetail =
        json.decodeFromString(BundleDetail.serializer(), text)
}
