package me.spica27.spicamusic.store

/**
 * 客户端自身更新（独立于商店收录）：
 * 内置自身仓库地址，静默比较最新 Release tag 与本地版本。
 */
interface SelfUpdater {
    suspend fun check(): UpdateInfo?
}

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String,
)

class SelfUpdaterImpl(
    private val github: GitHubReleaseClient,
    private val currentVersionName: String,
    private val ownRepo: String = "happy-everyday-everyweek/appstore",
) : SelfUpdater {
    override suspend fun check(): UpdateInfo? {
        val release = github.latestRelease(ownRepo) ?: return null
        val tag = release.tag.removePrefix("v")
        if (tag == currentVersionName || tag.isBlank()) return null
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return UpdateInfo(
            versionName = tag,
            downloadUrl = apk.downloadUrl,
            releaseUrl = "https://github.com/$ownRepo/releases/tag/${release.tag}",
        )
    }
}
