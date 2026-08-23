package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.Serializable

/** 评级 A-E */
@Serializable
enum class AppGrade(val label: String) {
    A("A"), B("B"), C("C"), D("D"), E("E");

    companion object {
        fun fromRaw(raw: String?): AppGrade =
            entries.firstOrNull { it.name == raw } ?: AppGrade.E
    }
}

/** 最低特殊权限（Shizuku 经 ADB 激活，归入 ADB 档） */
@Serializable
enum class SpecialPermission(val label: String) {
    NONE("无特殊权限"), ADB("ADB"), ROOT("Root");

    companion object {
        fun fromRaw(raw: String?): SpecialPermission =
            when (raw?.lowercase()) {
                "adb" -> ADB
                "root" -> ROOT
                else -> NONE
            }
    }
}

/** 应用版本信息（app-info.json version） */
@Serializable
data class AppVersion(
    val versionName: String = "",
    val versionCode: Long = 0,
    val releaseTag: String = "",
)

/**
 * 应用元数据（对应聚合包 index.json 中单条抽取集合，
 * 抽取自承载仓库 app.json + app-info.json）
 */
@Serializable
data class AppMeta(
    val id: String = "",
    val upstream: Int? = null,
    val repo: String = "",
    val packageName: String = "",
    val name: String = "",
    val icon: String = "",
    val summary: String = "",
    val openSource: Boolean = false,
    val specialPermissions: List<SpecialPermission> = emptyList(),
    val grade: AppGrade = AppGrade.E,
    val version: AppVersion = AppVersion(),
    val license: String? = null,
    val apkUrl: String = "",
    val apkSha256: String = "",
) {
    val upstreamId: String? get() = upstream?.toString()
}

/** 聚合包解析结果：应用系统 ID → 元数据（顺序无关，列表页按评级排序） */
typealias AppIndex = Map<String, AppMeta>

/** 补丁解析清单（patch.json，工作流 4 / 推荐仓库 build_release.py 生成） */
@Serializable
data class PatchManifest(
    val base: String? = null,
    val target: String? = null,
    val algorithm: String = "structured-json-v1",
    val incrementalSha256: String = "",
    val fullSha256: String = "",
    val fullSize: Long = 0,
)

/** 卡片背景：color / gradient / cover 三形态，可空；缺省时客户端用默认深色渐变兜底 */
@Serializable
data class CardBackground(
    val color: String? = null,
    val gradient: List<String> = emptyList(),
    val cover: String? = null,
)

/** 推荐卡片（对应推荐仓库 cards/NN-slug.json，契约见规格书 v0.4） */
@Serializable
data class StoreCard(
    val type: String = "",
    val slug: String = "",
    val label: String = "",
    val title: String = "",
    val subtitle: String = "",
    val background: CardBackground = CardBackground(),
    val publishDate: String? = null,
    val apps: List<Int> = emptyList(),
    val article: String? = null,
) {
    val appIds: List<String> get() = apps.map { it.toString() }
}

/** 推荐包解析结果：卡片列表（顺序 = cards/ 文件名序号） */
typealias DiscoverIndex = List<StoreCard>

/** 结构化增量：addedOrChanged（完整条目）+ removed（id/slug 列表） */
data class ChangeSet(
    val addedOrChanged: Map<String, AppMeta>,
    val removed: List<String>,
)