package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** 推荐包解析器：full.zip/index.json（卡片数组）→ DiscoverIndex */
object DiscoverIndexParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): DiscoverIndex {
        val root = json.parseToJsonElement(text).jsonArray
        return root.mapNotNull { it.jsonObjectOrNull()?.toCard() }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonObject.toCard(): StoreCard {
        val bg = get("background")?.jsonObjectOrNull()?.toCardBackground() ?: CardBackground()
        return StoreCard(
            type = get("type")?.jsonPrimitive?.contentOrNull ?: "",
            slug = get("slug")?.jsonPrimitive?.contentOrNull ?: "",
            label = get("label")?.jsonPrimitive?.contentOrNull ?: "",
            title = get("title")?.jsonPrimitive?.contentOrNull ?: "",
            subtitle = get("subtitle")?.jsonPrimitive?.contentOrNull ?: "",
            background = bg,
            publishDate = get("publish_date")?.jsonPrimitive?.contentOrNull,
            apps = (get("apps")?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.doubleOrNull?.toInt() },
            article = get("article")?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun JsonObject?.toCardBackground(): CardBackground {
        if (this == null) return CardBackground()
        return CardBackground(
            color = get("color")?.jsonPrimitive?.contentOrNull,
            gradient = (get("gradient")?.jsonArray ?: emptyList())
                .mapNotNull { it.jsonPrimitive.contentOrNull },
            cover = get("cover")?.jsonPrimitive?.contentOrNull,
        )
    }
}