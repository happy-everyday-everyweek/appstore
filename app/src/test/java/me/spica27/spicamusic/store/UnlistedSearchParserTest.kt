package me.spica27.spicamusic.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnlistedSearchParser] 解析/过滤逻辑单测（不触网）。
 * 覆盖：排除已收录仓库（大小写不敏感）、full_name 去重、星标降序、开源标注。
 */
class UnlistedSearchParserTest {
    private fun item(
        fullName: String,
        stars: Int,
        hasLicense: Boolean,
        description: String = "",
        language: String? = null,
    ): String =
        """
        {
          "full_name": "$fullName",
          "name": "${fullName.substringAfter('/')}",
          "description": "$description",
          "stargazers_count": $stars,
          "language": ${if (language == null) "null" else "\"$language\""},
          "html_url": "https://github.com/$fullName",
          "owner": {"avatar_url": "https://avatars.example/$fullName"},
          "license": ${if (hasLicense) """{"key": "mit"}""" else "null"}
        }
        """.trimIndent()

    private fun wrap(vararg items: String): String = """{"total_count": ${items.size}, "items": [${items.joinToString(",")}]}"""

    @Test
    fun `排除已收录仓库并按星标降序`() {
        val json =
            wrap(
                item("alice/lowstar", stars = 10, hasLicense = true),
                item("bob/highstar", stars = 500, hasLicense = false, language = "Kotlin"),
                item("carol/mid", stars = 100, hasLicense = true),
            )
        val result = UnlistedSearchParser.parse(json, excludeRepos = setOf("alice/lowstar"))
        assertEquals(listOf("bob/highstar", "carol/mid"), result.map { it.fullName })
        assertEquals(500, result[0].stars)
        assertFalse("无 license 应标注为闭源", result[0].openSource)
        assertTrue("有 license 应标注为开源", result[1].openSource)
    }

    @Test
    fun `排除已收录大小写不敏感且去重`() {
        val json =
            wrap(
                item("Alice/AppA", stars = 9, hasLicense = false),
                item("alice/appa", stars = 3, hasLicense = false),
                item("dave/keep", stars = 1, hasLicense = true),
            )
        val result = UnlistedSearchParser.parse(json, excludeRepos = setOf("ALICE/APPA"))
        assertEquals(listOf("dave/keep"), result.map { it.fullName })
    }

    @Test
    fun `非法或缺字段条目被跳过`() {
        // 缺 full_name 的条目应被跳过，不抛异常
        val json = """{"items": [{"name": "nofull", "stargazers_count": 5}, ${item("ok/one", 7, true)}]}"""
        val result = UnlistedSearchParser.parse(json, excludeRepos = emptySet())
        assertEquals(listOf("ok/one"), result.map { it.fullName })
    }

    @Test
    fun `空响应或解析失败返回空列表`() {
        assertTrue(UnlistedSearchParser.parse("", emptySet()).isEmpty())
        assertTrue(UnlistedSearchParser.parse("not json", emptySet()).isEmpty())
        assertTrue(UnlistedSearchParser.parse("""{"items": "oops"}""", emptySet()).isEmpty())
    }
}
