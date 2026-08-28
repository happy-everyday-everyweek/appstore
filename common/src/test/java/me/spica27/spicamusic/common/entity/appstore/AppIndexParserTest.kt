package me.spica27.spicamusic.common.entity.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聚合包解析器测试（TDD 红色先行）。
 * 契约：full.zip/index.json 为 id → 应用元数据 的 JSON 对象（工作流 4 aggregate.py 生成的抽取集合）。
 */
class AppIndexParserTest {

    private val sampleIndexJson = """
    {
      "1001": {
        "id": "1001",
        "repo": "AAswordman/Operit",
        "name": "Operit",
        "packageName": "com.ai.assistance.operit",
        "icon": "https://example.com/icon.png",
        "summary": "Android AI Agent",
        "openSource": true,
        "specialPermissions": ["adb"],
        "upstream": null,
        "grade": "D",
        "version": { "versionName": "1.0.0", "versionCode": 10, "releaseTag": "v1.0.0" },
        "source": {
          "repo": "AAswordman/Operit",
          "license": "LGPL-3.0",
          "apkUrl": "https://example.com/app.apk",
          "sha256": "abcd"
        }
      },
      "1002": {
        "id": "1002",
        "repo": "yangSpica27/SPICaMusic_Android",
        "name": "柠檬音乐",
        "packageName": "me.spica27.spicamusic",
        "icon": "",
        "summary": "音乐播放器",
        "openSource": false,
        "specialPermissions": [],
        "upstream": 1001,
        "grade": "E",
        "version": { "versionName": "2.0", "versionCode": 20, "releaseTag": "v2.0" },
        "source": { "repo": "yangSpica27/SPICaMusic_Android", "license": "MIT", "apkUrl": "", "sha256": "" }
      }
    }
    """.trimIndent()

    @Test
    fun `parse index json with two apps and full fields`() {
        val index = AppIndexParser.parse(sampleIndexJson)

        assertEquals(2, index.size)
        val operit = index["1001"]!!
        assertEquals("Operit", operit.name)
        assertEquals("1001", operit.id)
        assertEquals("com.ai.assistance.operit", operit.packageName)
        assertTrue(operit.openSource)
        assertEquals(AppGrade.D, operit.grade)
        assertEquals(listOf(SpecialPermission.ADB), operit.specialPermissions)
        assertEquals("v1.0.0", operit.version.releaseTag)
        assertEquals("https://example.com/app.apk", operit.apkUrl)

        val music = index["1002"]!!
        assertEquals(1001, music.upstream)
        assertEquals(AppGrade.E, music.grade)
    }

    @Test
    fun `parse empty index`() {
        assertEquals(0, AppIndexParser.parse("{}").size)
    }

    @Test
    fun `missing version falls back to blanks`() {
        val index = AppIndexParser.parse("""{"1003":{"id":"1003","name":"X","packageName":"p.x"}}""")
        assertEquals("", index["1003"]!!.version.versionName)
    }

    @Test
    fun `parseV2 rewrites icon id to local asset path and reads iconBlurhash`() {
        val v2Index =
            """{
              "2001": {
                "id": "2001",
                "name": "V2App",
                "icon": "1048",
                "iconBlurhash": "UBIAXtP00000~q%L00IU0000x]",
                "summary": "s",
                "openSource": true,
                "grade": "D",
                "version": { "versionName": "1.0", "versionCode": 2 }
              }
            }"""
        val index = AppIndexParser.parseV2(v2Index)

        val app = index["2001"]!!
        assertEquals("图标 id 重写为本地资产路径", "assets/icons/1048.png", app.icon)
        assertEquals("iconBlurhash 解析", "UBIAXtP00000~q%L00IU0000x]", app.iconBlurhash)
    }

    @Test
    fun `parseV2 keeps http and assets prefixed icons untouched`() {
        val v2Index =
            """{
              "2001": {"id":"2001","name":"A","icon":"https://example.com/i.png","iconBlurhash":""},
              "2002": {"id":"2002","name":"B","icon":"assets/icons/9.png","iconBlurhash":""},
              "2003": {"id":"2003","name":"C","icon":"","iconBlurhash":""}
            }"""
        val index = AppIndexParser.parseV2(v2Index)

        assertEquals("http 图标原样保留", "https://example.com/i.png", index["2001"]!!.icon)
        assertEquals("assets 前缀原样保留", "assets/icons/9.png", index["2002"]!!.icon)
        assertEquals("空图标按 id 兜底", "assets/icons/2003.png", index["2003"]!!.icon)
    }
}