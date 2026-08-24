package me.spica27.spicamusic.store

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.spica27.spicamusic.common.entity.appstore.PatchManifestParser
import java.io.File

/**
 * 客户端自身更新（GitLink 直链模式，零 GitHub API）：
 * 每个发行版都带 patch.json（更新路径解析）——直接经 GitLink 下载它，
 * 读 target 与本地版本比较即可判定是否有新版本；下载更新同样走 GitLink 镜像。
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
    private val downloader: Downloader,
    appContext: Context,
    private val currentVersionName: String,
    private val ownRepo: String = "happy-everyday-everyweek/appstore",
) : SelfUpdater {
    private val cacheDir: File = appContext.cacheDir

    override suspend fun check(): UpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(cacheDir, "self-update-patch.json")
                val f =
                    downloader.download(
                        "https://github.com/$ownRepo/releases/latest/download/patch.json",
                        tmp,
                        null,
                    )
                val patch = PatchManifestParser.parse(f.readText()) ?: return@runCatching null
                f.delete()
                val tag = patch.target?.removePrefix("v").orEmpty()
                if (tag.isBlank() || tag == currentVersionName) return@runCatching null
                UpdateInfo(
                    versionName = tag,
                    downloadUrl = "https://github.com/$ownRepo/releases/latest/download/app-release.apk",
                    releaseUrl = "https://github.com/$ownRepo/releases/tag/${patch.target}",
                )
            }.getOrNull()
        }
}
