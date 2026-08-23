package me.spica27.spicamusic.common.entity.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * patch.json 解析与聚合包变更判定测试（TDD 红色先行）。
 * patch.json 契约见工作流 4 / 推荐仓库 build_release.py。
 */
class PatchManifestParserTest {

    @Test
    fun `parse patch manifest with full fields`() {
        val json = """
        {
          "base": "aggregate-20260823000000",
          "target": "aggregate-20260823040000",
          "algorithm": "structured-json-v1",
          "incrementalSha256": "aaa",
          "fullSha256": "bbb",
          "fullSize": 12345
        }
        """.trimIndent()
        val patch = PatchManifestParser.parse(json)
        assertEquals("aggregate-20260823000000", patch.base)
        assertEquals("aggregate-20260823040000", patch.target)
        assertEquals("structured-json-v1", patch.algorithm)
        assertEquals("aaa", patch.incrementalSha256)
        assertEquals("bbb", patch.fullSha256)
    }

    @Test
    fun `parse sparse patch manifest`() {
        val patch = PatchManifestParser.parse("""{"target":"t1","algorithm":"structured-json-v1"}""")
        assertEquals("t1", patch.target)
        assertNull(patch.base)
    }
}

class ChangeDetectorTest {

    @Test
    fun `detect added changed removed`() {
        val old = mapOf(
            "1" to appMeta("1", "A"),
            "2" to appMeta("2", "B"),
            "3" to appMeta("3", "C"),
        )
        val new = mapOf(
            "1" to appMeta("1", "A"),
            "2" to appMeta("2", "B2"), // changed
            "4" to appMeta("4", "D"),  // added
            // 3 removed
        )
        val change = ChangeDetector.diff(old, new)
        assertTrue(change.addedOrChanged.containsKey("2"))
        assertTrue(change.addedOrChanged.containsKey("4"))
        assertFalse(change.addedOrChanged.containsKey("1"))
        assertEquals(listOf("3"), change.removed)
    }

    @Test
    fun `no change when identical`() {
        val old = mapOf("1" to appMeta("1", "A"))
        val change = ChangeDetector.diff(old, old)
        assertTrue(change.addedOrChanged.isEmpty())
        assertTrue(change.removed.isEmpty())
    }

    @Test
    fun `apply incremental yields new index`() {
        val base = mapOf("1" to appMeta("1", "A"), "2" to appMeta("2", "B"))
        val inc = ChangeSet(
            addedOrChanged = mapOf("2" to appMeta("2", "B2"), "9" to appMeta("9", "Z")),
            removed = listOf("1"),
        )
        val merged = ChangeDetector.apply(base, inc)
        assertEquals(setOf("2", "9"), merged.keys)
        assertEquals("B2", merged["2"]!!.name)
    }

    private fun appMeta(id: String, name: String) = AppMeta(id = id, name = name, packageName = "pkg.$id")
}