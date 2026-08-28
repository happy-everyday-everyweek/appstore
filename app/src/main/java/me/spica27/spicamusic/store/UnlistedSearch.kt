package me.spica27.spicamusic.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 未收录应用搜索结果：来自 GitHub Search、尚未进入本市场索引的仓库。
 *
 * 设计参照承载仓库 `verify_scan.py` 的收录核验链（仓库可达 → 真开源 → 最新 Release
 * 含 APK），但搜索阶段不做逐条重核验（会打爆 API 限额），仅用 GitHub 返回的
 * 轻量信号做可用性标注：有 license 视为开源；其余留给用户点进仓库自行判断。
 */
data class UnlistedApp(
    val fullName: String,
    val name: String,
    val description: String,
    val stars: Int,
    val language: String?,
    val htmlUrl: String,
    val ownerAvatar: String?,
    val openSource: Boolean,
)

/** 纯解析 + 过滤逻辑（不触网，可单测）。 */
object UnlistedSearchParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析 GitHub Search Repositories 响应，过滤掉已收录仓库（[excludeRepos]，
     * 大小写不敏感），按 full_name 去重，星标降序。
     */
    fun parse(
        searchJson: String,
        excludeRepos: Set<String>,
    ): List<UnlistedApp> {
        val excluded = excludeRepos.map { it.lowercase() }.toSet()
        val items =
            runCatching { json.parseToJsonElement(searchJson).jsonObject["items"]?.jsonArray }
                .getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<UnlistedApp>()
        for (el in items) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val fullName = obj["full_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val key = fullName.lowercase()
            if (key in excluded || !seen.add(key)) continue
            out.add(
                UnlistedApp(
                    fullName = fullName,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: fullName.substringAfter('/'),
                    description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                    stars = obj["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    language = obj["language"]?.jsonPrimitive?.contentOrNull,
                    htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$fullName",
                    ownerAvatar =
                        (obj["owner"] as? JsonObject)
                            ?.get("avatar_url")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    openSource = obj["license"] is JsonObject,
                ),
            )
        }
        return out.sortedByDescending { it.stars }
    }
}

/** 未收录应用搜索源。 */
interface UnlistedSearchSource {
    /** 搜索 GitHub 上匹配 [query]、且不在 [excludeRepos]（已收录）中的仓库。失败返回空列表。 */
    suspend fun search(
        query: String,
        excludeRepos: Set<String>,
    ): List<UnlistedApp>
}

/**
 * GitHub Search API 实现。客户端整体是「零 GitHub API」的 GitLink 直链设计，
 * 搜索未收录是唯一例外：搜索端点未认证限额 10 次/分钟，按需触发、结果去抖。
 */
class GitHubUnlistedSearchSource(
    private val client: OkHttpClient,
) : UnlistedSearchSource {
    override suspend fun search(
        query: String,
        excludeRepos: Set<String>,
    ): List<UnlistedApp> =
        withContext(Dispatchers.IO) {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://api.github.com/search/repositories?q=$q&per_page=30&sort=stars"
            try {
                val req =
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "Only-AppStore")
                        .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        DebugLog.w("UnlistedSearch", "GitHub 搜索 HTTP ${resp.code}（可能触发限流）")
                        return@withContext emptyList()
                    }
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    UnlistedSearchParser.parse(body, excludeRepos)
                }
            } catch (e: Exception) {
                DebugLog.w("UnlistedSearch", "搜索未收录应用失败：${e.message ?: e::class.simpleName}")
                emptyList()
            }
        }
}
