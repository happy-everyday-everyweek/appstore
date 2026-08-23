package me.spica27.spicamusic.common.entity.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 推荐包 index.json 解析器测试（TDD 红色先行）。
 * 契约：index.json 为卡片数组（build_release.py 生成），字段见规格书 v0.4。
 */
class DiscoverIndexParserTest {

    private val sampleIndexJson = """
    [
      {
        "type": "collection",
        "slug": "ai-agent-picks",
        "label": "编辑精选",
        "title": "把 AI 装进口袋",
        "subtitle": "4 个应用",
        "background": { "gradient": ["#2B3A67", "#0F1B30"] },
        "publish_date": "8 月 23 日 周日",
        "apps": [1001, 1002]
      },
      {
        "type": "article",
        "slug": "smartisan-design",
        "label": "文章",
        "title": "让 Smartisan OS 的设计美学重生",
        "subtitle": "作者",
        "background": { "color": "#5C4A31" },
        "article": "smartisan-design.md"
      },
      {
        "type": "collection",
        "slug": "music",
        "title": "音乐",
        "subtitle": "1 个应用",
        "background": { "cover": "music.png" },
        "apps": [1002]
      }
    ]
    """.trimIndent()

    @Test
    fun `parse three cards with background variants`() {
        val cards = DiscoverIndexParser.parse(sampleIndexJson)

        assertEquals(3, cards.size)
        val collection = cards[0]
        assertEquals("collection", collection.type)
        assertEquals("ai-agent-picks", collection.slug)
        assertEquals(listOf("#2B3A67", "#0F1B30"), collection.background.gradient)
        assertEquals(listOf(1001, 1002), collection.apps)

        val article = cards[1]
        assertEquals("article", article.type)
        assertEquals("#5C4A31", article.background.color)
        assertEquals("smartisan-design.md", article.article)

        val coverCard = cards[2]
        assertEquals("music.png", coverCard.background.cover)
    }

    @Test
    fun `card without background falls back to default`() {
        val cards = DiscoverIndexParser.parse("""[{"type":"collection","slug":"x","title":"T","subtitle":"S","apps":[]}]""")
        assertNull(cards[0].background.color)
        assertNull(cards[0].background.cover)
        assertEquals(0, cards[0].background.gradient.size)
    }

    @Test
    fun `parse empty card list`() {
        assertEquals(0, DiscoverIndexParser.parse("[]").size)
    }
}