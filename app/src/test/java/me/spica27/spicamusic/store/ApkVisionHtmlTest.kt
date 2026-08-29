package me.spica27.spicamusic.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APKVision 采集源纯解析逻辑单测（移植 scraper_apkvision.py，不触网）。
 * 覆盖：搜索 URL、卡片解析（main-news）、详情页 Package name 提取、按包名判未收录。
 */
class ApkVisionHtmlTest {
    @Test
    fun `搜索URL为 apkvision 斜杠 s 参数`() {
        val url = ApkVisionHtml.searchUrl("hello world")
        assertTrue(url, url.startsWith("https://apkvision.org/?s="))
        assertTrue(url, url.contains("hello") && url.contains("world"))
        assertEquals("https://apkvision.org/?s=foo/page/2/", ApkVisionHtml.searchUrl("foo", page = 2))
    }

    @Test
    fun `解析 main-news 卡片得到 名称 版本 图标 与详情URL`() {
        val html =
            """
            <a href="/app/com.foo.bar-1-2-3/" class="col main-news">
              <div class="main-news-title">Foo Bar APK</div>
              <div class="main-news-cat">1.2.3 · Tools</div>
              <img data-src="/icons/foo.png" alt="foo">
            </a>
            """.trimIndent()
        val cards = ApkVisionHtml.parseCards(html)
        assertEquals(1, cards.size)
        val c = cards[0]
        assertEquals("Foo Bar", c.name) // 结尾 " APK" 被剥离
        assertEquals("1.2.3", c.version)
        assertEquals("https://apkvision.org/app/com.foo.bar-1-2-3/", c.url)
        assertEquals("https://apkvision.org/icons/foo.png", c.iconUrl)
    }

    @Test
    fun `忽略非 apkvision 域名链接`() {
        val html =
            """
            <a href="https://evil.example/x" class="main-news">
              <div class="main-news-title">Evil</div>
            </a>
            """.trimIndent()
        assertTrue(ApkVisionHtml.parseCards(html).isEmpty())
    }

    @Test
    fun `详情页提取 Package name 行`() {
        val html =
            """
            <table class="appinfo">
              <tr><th>Version</th><td>1.2.3</td></tr>
              <tr><th>Package name</th><td>com.foo.bar</td></tr>
              <tr><th>Downloads</th><td>1M</td></tr>
            </table>
            """.trimIndent()
        assertEquals("com.foo.bar", ApkVisionHtml.parsePackageName(html))
    }

    @Test
    fun `按包名对照已收录集合判定未收录 大小写不敏感`() {
        val indexed = setOf("com.existing.app")
        assertTrue(ApkVisionHtml.isUnlisted("com.new.app", indexed))
        assertFalse("命中已收录包名（忽略大小写）应判为已收录", ApkVisionHtml.isUnlisted("COM.EXISTING.APP", indexed))
        assertTrue("包名缺失无法确认，保守保留为候选", ApkVisionHtml.isUnlisted("", indexed))
    }
}
