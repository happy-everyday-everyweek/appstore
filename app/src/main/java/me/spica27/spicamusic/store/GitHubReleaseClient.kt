package me.spica27.spicamusic.store

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub Release 查询客户端：获取最新 Release 的 tag 与资产清单。
 * 全链路唯一对外 API 面（测试用 in-memory fake 替换）。
 */
interface GitHubReleaseClient {
    suspend fun latestRelease(repo: String): ReleaseInfo?
}

class GitHubReleaseClientImpl(
    private val client: OkHttpClient = OkHttpClient(),
) : GitHubReleaseClient {
    override suspend fun latestRelease(repo: String): ReleaseInfo? {
        val request =
            Request
                .Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            return parseRelease(body)
        }
    }

    private fun parseRelease(body: String): ReleaseInfo? =
        runCatching {
            val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(body).jsonObject
            val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
            val assets =
                (root["assets"]?.jsonArray ?: emptyList())
                    .mapNotNull { asset ->
                        val obj = asset.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val url = obj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ReleaseAsset(name = name, downloadUrl = url)
                    }
            ReleaseInfo(tag = tag, assets = assets)
        }.getOrNull()
}
