package me.spica27.spicamusic.store

import java.io.File

/**
 * 本地同步状态：缓存文件（聚合包/推荐包解析结果）+ 已应用版本 tag。
 * 目录布局：<root>/store/<cacheFileName> 与 <root>/store/<channel>.version
 */
class SyncStore(
    private val rootDir: File,
) {
    private val dir: File = File(rootDir, "store").apply { mkdirs() }

    /** assets 根目录（与 StoreAssets 共享） */
    val assetsRoot: File get() = dir

    fun cacheFile(channel: SyncChannel): File = File(dir, channel.cacheFileName)

    fun readVersion(channel: SyncChannel): String? {
        val f = File(dir, "${channel.name}.version")
        return if (f.exists()) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun writeVersion(
        channel: SyncChannel,
        tag: String,
    ) {
        File(dir, "${channel.name}.version").writeText(tag)
    }

    fun readCachedText(channel: SyncChannel): String? {
        val f = cacheFile(channel)
        return if (f.exists()) f.readText() else null
    }

    fun writeCachedText(
        channel: SyncChannel,
        text: String,
    ) {
        cacheFile(channel).writeText(text)
    }

    /** 推荐包资产（articles/、covers/）落盘：key 形如 assets/articles/x.md */
    fun writeAssets(entries: Map<String, ByteArray>) {
        entries.forEach { (name, bytes) ->
            val rel = name.removePrefix("assets/")
            if (rel.isBlank() || rel.contains("..")) return@forEach
            val f = File(dir, "assets/$rel")
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
    }

    fun assetFile(relPath: String): File = File(dir, "assets/$relPath")
}
