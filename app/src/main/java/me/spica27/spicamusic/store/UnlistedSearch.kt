package me.spica27.spicamusic.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * 「搜索未收录应用」——移植承载仓库 WF8（closed-sources）的采集算法到客户端。
 *
 * WF8 做法：巡扫/搜索 APK 采集源（APKVision），解析候选 → 取其 packageName →
 * 用承载仓库 indexed_packages()（apps/&lt;owner&gt;/&lt;repo&gt;/app-info.json 的 packageName 集合）
 * 判定是否已收录。客户端复用同一判定键（packageName）：搜索 APKVision，命中的候选
 * 按 packageName 对照本地索引剔除已收录项，剩下的即「未收录」。
 *
 * 判定键是 packageName 而非仓库名——与 GitHub 无关（客户端整体仍是 GitLink 零 API，
 * APKVision 是普通 HTML 源、非 GitHub API）。
 */
data class UnlistedApp(
    val detailUrl: String,
    val name: String,
    val packageName: String,
    val version: String,
    val iconUrl: String,
    val source: String = "apkvision",
)

/** APKVision 搜索/分类页卡片（详情未展开时的轻量信息）。 */
data class ApkCard(
    val url: String,
    val name: String,
    val version: String,
    val iconUrl: String,
)

/**
 * 纯解析逻辑（移植 scraper_apkvision.py 的 parse_cards / Package name 行提取），
 * 不触网、可单测。
 */
object ApkVisionHtml {
    const val ORIGIN = "https://apkvision.org"
    const val MIN_QUERY = 2 // 源脚本强制搜索词 ≥2 字符

    /** 搜索 URL：https://apkvision.org/?s=<q>（WordPress 搜索，翻页用 &paged=N）。 */
    fun searchUrl(
        query: String,
        page: Int = 1,
    ): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if (page > 1) "$ORIGIN/?s=$q&paged=$page" else "$ORIGIN/?s=$q"
    }

    private fun denode(s: String): String =
        s
            .replace("&#8211;", "-")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&#039;", "'")
            .replace("&#8217;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")

    private fun clean(v: String): String = denode(v).replace(Regex("\\s+"), " ").trim()

    private fun attr(
        tag: String,
        name: String,
    ): String {
        val m =
            Regex(
                "\\b" + Regex.escape(name) + "\\s*=\\s*([\"'])([\\s\\S]*?)\\1",
                RegexOption.IGNORE_CASE,
            ).find(tag)
        return if (m != null) denode(m.groupValues[2]).trim() else ""
    }

    private fun absurl(u: String): String {
        val v = clean(u)
        return when {
            v.startsWith("//") -> "https:$v"
            v.startsWith("/") -> ORIGIN + v
            else -> v
        }
    }

    private fun stripApk(title: String): String =
        clean(title)
            .replace(ORIGIN, "")
            .replace(" - Download Free for Android", "")
            .replace(Regex("\\s+[-–]?\\s*apk\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()

    private fun extractVersion(value: String): String {
        val m =
            Regex("\\bv?\\d+(?:\\.\\d+)+(?:\\s+[A-Za-z][\\w-]*)?", RegexOption.IGNORE_CASE)
                .find(clean(value))
        return m?.value ?: ""
    }

    private fun imageUrl(imgTag: String): String {
        for (name in listOf("src", "data-src", "data-lazy-src", "data-original")) {
            val v = attr(imgTag, name)
            if (v.isNotEmpty()) return absurl(v)
        }
        return ""
    }

    /** 搜索/分类页卡片：main-news 优先，mainb-item 兜底（与官方 apkvision.js 同构）。 */
    fun parseCards(html: String): List<ApkCard> {
        val primary = parseCardsWith(html, "main-news", "main-news-title", "main-news-cat")
        if (primary.isNotEmpty()) return primary
        return parseCardsWith(html, "mainb-item", "mainb-title", "mainb-cat")
    }

    private fun parseCardsWith(
        html: String,
        anchor: String,
        title: String,
        meta: String,
    ): List<ApkCard> {
        val result =
            Regex(
                "<a\\b([^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\b" + Regex.escape(anchor) +
                    "\\b[^\"']*[\"'][^>]*)>([\\s\\S]*?)</a>",
                RegexOption.IGNORE_CASE,
            )
        val titleRe =
            Regex(
                "<div\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\b" + Regex.escape(title) +
                    "\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
                RegexOption.IGNORE_CASE,
            )
        val metaRe =
            Regex(
                "<div\\b[^>]*\\bclass\\s*=\\s*[\"'][^\"']*\\b" + Regex.escape(meta) +
                    "\\b[^\"']*[\"'][^>]*>([\\s\\S]*?)</div>",
                RegexOption.IGNORE_CASE,
            )
        val verRe = Regex("\\bv?\\d+(?:\\.\\d+)+")
        val apkvisionUrl = Regex("^https://(?:[^/]+\\.)?apkvision\\.org/", RegexOption.IGNORE_CASE)
        val imgRe = Regex("<img\\b[^>]*>", RegexOption.IGNORE_CASE)
        val out = ArrayList<ApkCard>()
        val seen = HashSet<String>()
        for (m in result.findAll(html)) {
            val opening = "<a${m.groupValues[1]}>"
            val uid = absurl(attr(opening, "href"))
            if (!apkvisionUrl.containsMatchIn(uid)) continue
            val block = m.groupValues[2]
            val tm = titleRe.find(block)
            val metas = metaRe.findAll(block).map { clean(it.groupValues[1]) }.toList()
            val foundVersion = metas.firstOrNull { verRe.containsMatchIn(it) } ?: ""
            val img = imgRe.find(block)
            var name = if (tm != null) stripApk(clean(tm.groupValues[1].replace("\n", " "))) else ""
            if (name.isEmpty()) name = stripApk(clean(Regex("<[^>]+>").replace(block, " ")))
            if (name.isEmpty()) continue
            if (!seen.add(uid)) continue
            out.add(
                ApkCard(
                    url = uid,
                    name = name,
                    version = extractVersion(foundVersion),
                    iconUrl = img?.let { imageUrl(it.value) } ?: "",
                ),
            )
        }
        return out
    }

    /** 详情页 .appinfo 表格里 "Package name" 行的值。 */
    fun parsePackageName(html: String): String {
        val rowRe = Regex("<th[^>]*>([\\s\\S]*?)</th>\\s*<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
        for (r in rowRe.findAll(html)) {
            if (clean(r.groupValues[1]).equals("Package name", ignoreCase = true)) return clean(r.groupValues[2])
        }
        return ""
    }

    /** packageName 命中已收录集合则视为已收录；空 packageName 无法确认，保守保留为候选。 */
    fun isUnlisted(
        packageName: String,
        indexedPackages: Set<String>,
    ): Boolean {
        if (packageName.isBlank()) return true
        return packageName.lowercase() !in indexedPackages.map { it.lowercase() }.toSet()
    }
}

/** 未收录应用搜索源。 */
interface UnlistedSearchSource {
    /** 搜索 APK 源、返回按 packageName 对照本地索引后确认未收录的应用。失败返回空列表。 */
    suspend fun search(
        query: String,
        indexedPackages: Set<String>,
    ): List<UnlistedApp>
}

/**
 * APKVision 实现：/?s=<q> 搜索页解析卡片 → 逐条取详情页 packageName → 剔除已收录。
 * 与 WF8 同源同判定；抓取失败/被限流静默降级返回空。
 */
class ApkVisionUnlistedSearchSource(
    private val client: OkHttpClient,
    private val maxDetails: Int = 10,
) : UnlistedSearchSource {
    override suspend fun search(
        query: String,
        indexedPackages: Set<String>,
    ): List<UnlistedApp> =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive() // 重打关键词会取消旧搜索：尽早停止
            val q = query.trim()
            if (q.length < ApkVisionHtml.MIN_QUERY) return@withContext emptyList()
            val html = fetch(ApkVisionHtml.searchUrl(q)) ?: return@withContext emptyList()
            val cards = ApkVisionHtml.parseCards(html).take(maxDetails)
            coroutineScope {
                cards
                    .map { card ->
                        async(Dispatchers.IO) {
                            val pkg = fetch(card.url)?.let { ApkVisionHtml.parsePackageName(it) } ?: ""
                            // 取不到包名无法判定收录状态 → 丢弃（避免源被拦截时输出整屏假结果）
                            if (pkg.isBlank() || !ApkVisionHtml.isUnlisted(pkg, indexedPackages)) {
                                null
                            } else {
                                UnlistedApp(card.url, card.name, pkg, card.version, card.iconUrl)
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        }

    private fun fetch(url: String): String? =
        try {
            val req =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .header("Referer", "${ApkVisionHtml.ORIGIN}/")
                    .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    DebugLog.w("UnlistedSearch", "APKVision HTTP ${r.code}")
                    null
                } else {
                    val len = r.body.contentLength()
                    if (len > 2_000_000) {
                        DebugLog.w("UnlistedSearch", "响应体过大($len) 放弃：$url")
                        null
                    } else {
                        r.body.string()
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.w("UnlistedSearch", "APKVision 抓取失败：${e.message ?: e::class.simpleName}")
            null
        }
}
