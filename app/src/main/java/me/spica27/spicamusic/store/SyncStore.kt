package me.spica27.spicamusic.store

import java.io.File

/**
 * 本地同步状态：缓存文件（聚合包/推荐包解析结果）+ 已应用版本 tag。
 * 目录布局：<root>/store/<cacheFileName> 与 <root>/store/<channel>.version
 *
 * v2 布局（与 v1 并存，双轨兼容）：
 * - manifest.snapshot.json：上次成功同步的 manifest（对比基准）
 * - index.v2.json：v2 列表索引（AppIndex 通道优先读取，缺省回落 v1 app-index.json）
 * - assets/icons/<id>.png（+.sha256 标记）：列表图标
 * - assets/bundles/<id>/…（含 .sha256 标记）：详情包解包内容（懒加载，可整目录清理）
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

    // ---- v2：manifest 快照（同步入口的版本真相） ----

    fun readManifestSnapshot(): String? {
        val f = File(dir, "manifest.snapshot.json")
        return if (f.exists()) f.readText() else null
    }

    fun writeManifestSnapshot(text: String) {
        atomicWrite(File(dir, "manifest.snapshot.json"), text)
    }

    // ---- v2：列表索引（index.v2.json，与 v1 缓存文件并存） ----

    fun readIndexV2(): String? {
        val f = File(dir, "index.v2.json")
        return if (f.exists()) f.readText() else null
    }

    /** 原子替换本地索引（§6.1.3a）：先写临时文件再 rename，崩溃/中断不会留下半截 index */
    fun writeIndexV2(text: String) {
        atomicWrite(File(dir, "index.v2.json"), text)
    }

    /** 原子写入：同目录临时文件 + rename（同文件系统 rename 为原子操作），失败兜底直接写 */
    private fun atomicWrite(
        target: File,
        text: String,
    ) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    // ---- v2：图标（assets/icons/<id>.png + SHA-256 标记，幂等续传） ----

    fun iconFile(id: String): File = File(dir, "assets/icons/$id.png")

    /** 图标 SHA-256 标记：标记存在且内容与 manifest 一致视为已同步（崩溃/中断后自动续） */
    fun iconMarker(id: String): File = File(dir, "assets/icons/.$id.sha256")

    /** 删除 manifest 中已不存在的 v2 图标文件（仅清理带 sha256 标记的，不误删 v1 遗留资产） */
    fun deleteStaleIcons(keepIds: Set<String>) {
        val iconsDir = File(dir, "assets/icons")
        if (!iconsDir.exists()) return
        iconsDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".png")) {
                val id = f.name.removeSuffix(".png")
                val marker = File(iconsDir, ".$id.sha256")
                if (marker.exists() && id !in keepIds) {
                    f.delete()
                    marker.delete()
                }
            }
        }
    }

    // ---- v2：详情包（assets/bundles/<id>/，懒加载，可整目录清理） ----

    fun bundleDir(id: String): File = File(dir, "assets/bundles/$id")

    /** bundle 下载临时文件（下载完成后解包即删） */
    fun bundleTmpFile(id: String): File = File(dir, "v2-tmp/$id.bundle.zip")

    /** 删除 SHA 已不在新 manifest 表中的 bundle 缓存目录（懒加载对象过期清理） */
    fun deleteStaleBundles(keepSha: Map<String, String>) {
        val bundlesRoot = File(dir, "assets/bundles")
        if (!bundlesRoot.exists()) return
        bundlesRoot.listFiles()?.forEach { d ->
            if (d.isDirectory) {
                val marker = File(d, ".sha256")
                val current = marker.exists() && keepSha[d.name] == marker.readText()
                if (!current) d.deleteRecursively()
            }
        }
    }
}
