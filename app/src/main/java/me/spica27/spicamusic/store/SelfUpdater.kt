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
 * 检查全程埋点进 [DebugLog]，任何失败不再静默吞掉。
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
            val url = "https://github.com/$ownRepo/releases/latest/download/patch.json"
            DebugLog.i("Update", "检查更新：$url 本地版本=$currentVersionName")
            try {
                val tmp = File(cacheDir, "self-update-patch.json")
                val f =
                    downloader.download(
                        url,
                        tmp,
                        null,
                    )
                val text = f.readText()
                f.delete()
                val patch = PatchManifestParser.parse(text)
                if (patch == null) {
                    DebugLog.e("Update", "patch.json 解析失败：前 120 字「${text.take(120).replace('\n', ' ')}」")
                    return@withContext null
                }
                DebugLog.i("Update", "patch.json: base=${patch.base} target=${patch.target} algo=${patch.algorithm}")
                val current = normalizeVersion(currentVersionName)
                val tag = normalizeVersion(patch.target.orEmpty())
                if (tag.isBlank() || tag == current) {
                    DebugLog.i("Update", "已是最新版本（$current），无更新")
                    return@withContext null
                }
                DebugLog.i("Update", "发现新版本 $tag（本地 $current）")
                UpdateInfo(
                    versionName = tag,
                    downloadUrl = "https://github.com/$ownRepo/releases/latest/download/app-release.apk",
                    releaseUrl = "https://github.com/$ownRepo/releases/tag/${patch.target}",
                )
            } catch (e: Exception) {
                DebugLog.e("Update", "检查更新失败：${e.message ?: e::class.simpleName}")
                null
            }
        }

    /** 版本号归一化：忽略 v 前缀与构建后缀（versionName 形如 v1.0.6 PRE / 1.0.6） */
    private fun normalizeVersion(raw: String): String =
        raw
            .trim()
            .removePrefix("v")
            .substringBefore(" ")
            .substringBefore("-")
            .substringBefore("+")
            .trim()
}
