package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.Serializable

/** 评级 A-E */
@Serializable
enum class AppGrade(val label: String) {
    A("A"), B("B"), C("C"), D("D"), E("E")
}

/** 最低特殊权限（Shizuku 经 ADB 激活，归入 ADB 档） */
@Serializable
enum class SpecialPermission(val label: String) {
    NONE("无特殊权限"), ADB("ADB"), ROOT("Root")
}

/** 应用元数据（对应承载仓库 app-info.json） */
@Serializable
data class AppMeta(
    val packageName: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val summary: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val releaseTag: String = "",
    val repoOwner: String = "",
    val repoName: String = "",
    val isOpenSource: Boolean = false,
    val openSourceVerified: Boolean = false,
    val apkUrl: String = "",
    val apkSha256: String = "",
    val permissions: List<String> = emptyList(),
    val grade: AppGrade = AppGrade.E,
    val specialPermissions: List<SpecialPermission> = emptyList(),
    val uploader: String = "",
    val readmeText: String? = null,
    val updatedAt: String = "",
)

/** 聚合包：全市场应用元数据集合 */
@Serializable
data class AppIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val apps: List<AppMeta> = emptyList(),
)

/** 补丁解析清单（patch.json） */
@Serializable
data class PatchManifest(
    val baseVersion: String = "",
    val targetVersion: String = "",
    val patchUrl: String = "",
    val fullUrl: String = "",
    val patchAlgorithm: String = "binary",
    val patchSha256: String = "",
    val fullSha256: String = "",
)

/** 推荐卡片（推荐页卡片流） */
@Serializable
data class StoreCard(
    val type: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconUrl: String? = null,
    val link: String? = null,
    val packageNames: List<String> = emptyList(),
)

/** 推荐页数据包 */
@Serializable
data class DiscoverIndex(
    val schemaVersion: Int = 1,
    val generatedAt: String = "",
    val cards: List<StoreCard> = emptyList(),
)