package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/** 聚合包解析器：full.zip/index.json（id → 应用元数据 JSON 对象）→ AppIndex */
object AppIndexParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): AppIndex {
        val root = json.parseToJsonElement(text).jsonObject
        return root.mapValues { (_, entry) -> entry.toAppMeta() }
    }

    private fun kotlinx.serialization.json.JsonElement.toAppMeta(): AppMeta {
        val obj = jsonObject
        val version = obj["version"]?.jsonObject
        val source = obj["source"]?.jsonObject
        return AppMeta(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
            upstream = obj["upstream"]?.jsonPrimitive?.intOrNull(),
            repo = obj["repo"]?.jsonPrimitive?.contentOrNull ?: "",
            packageName = obj["packageName"]?.jsonPrimitive?.contentOrNull ?: "",
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            icon = obj["icon"]?.jsonPrimitive?.contentOrNull ?: "",
            summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: "",
            openSource =
                obj["openSource"]?.toBooleanCompat()
                    ?: obj["source"]?.jsonObject?.get("openSource")?.toBooleanCompat()
                    ?: obj["source"]?.jsonObject?.get("openSourceVerified")?.toBooleanCompat()
                    ?: false,
            specialPermissions = (obj["specialPermissions"]?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.contentOrNull }
                .map { SpecialPermission.fromRaw(it) }
                .filter { it != SpecialPermission.NONE },
            permissions = (obj["permissions"]?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.contentOrNull },
            readme = obj["readme"]?.jsonPrimitive?.contentOrNull ?: "",
            grade = AppGrade.fromRaw(obj["grade"]?.jsonPrimitive?.contentOrNull),
            version = AppVersion(
                versionName = version?.get("versionName")?.jsonPrimitive?.contentOrNull ?: "",
                versionCode = version?.get("versionCode")?.jsonPrimitive?.longOrNull ?: 0,
                releaseTag = version?.get("releaseTag")?.jsonPrimitive?.contentOrNull ?: "",
            ),
            license =
                source?.get("license")?.jsonPrimitive?.contentOrNull
                    ?: obj["license"]?.jsonPrimitive?.contentOrNull,
            apkUrl =
                source?.get("apkUrl")?.jsonPrimitive?.contentOrNull
                    ?: obj["apkUrl"]?.jsonPrimitive?.contentOrNull
                    ?: "",
            apkSha256 =
                source?.get("sha256")?.jsonPrimitive?.contentOrNull
                    ?: obj["apkSha256"]?.jsonPrimitive?.contentOrNull
                    ?: "",
        )
    }

    private fun JsonPrimitive.intOrNull(): Int? =
        doubleOrNull?.toInt() ?: contentOrNull?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** 布尔兼容解析：接受 JSON 布尔 true/false，也接受字符串 "true"/"false"（历史数据） */
    private fun kotlinx.serialization.json.JsonElement.toBooleanCompat(): Boolean? {
        val p = jsonPrimitive
        p.booleanOrNull?.let { return it }
        return p.contentOrNull?.toBooleanStrictOrNull()
    }
}
