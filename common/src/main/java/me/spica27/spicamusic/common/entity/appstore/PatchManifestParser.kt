package me.spica27.spicamusic.common.entity.appstore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** patch.json 解析器（工作流 4 / 推荐仓库 build_release.py 生成） */
object PatchManifestParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PatchManifest {
        val obj = json.parseToJsonElement(text).jsonObject
        return PatchManifest(
            base = obj["base"]?.jsonPrimitive?.contentOrNull,
            target = obj["target"]?.jsonPrimitive?.contentOrNull,
            algorithm = obj["algorithm"]?.jsonPrimitive?.contentOrNull ?: "structured-json-v1",
            incrementalSha256 = obj["incrementalSha256"]?.jsonPrimitive?.contentOrNull ?: "",
            fullSha256 = obj["fullSha256"]?.jsonPrimitive?.contentOrNull ?: "",
            fullSize = obj["fullSize"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    }
}

/** 聚合包变更判定：新旧 AppIndex 对比，产出结构化增量 */
object ChangeDetector {

    fun diff(old: AppIndex, new: AppIndex): ChangeSet {
        val addedOrChanged = new.filter { (id, meta) -> old[id] != meta }
        val removed = old.keys.filterNot { it in new }
        return ChangeSet(addedOrChanged = addedOrChanged, removed = removed)
    }

    /** 应用结构化增量：base + ChangeSet → 新 AppIndex */
    fun apply(base: AppIndex, change: ChangeSet): AppIndex {
        val merged = base.toMutableMap()
        merged.putAll(change.addedOrChanged)
        change.removed.forEach { merged.remove(it) }
        return merged
    }
}