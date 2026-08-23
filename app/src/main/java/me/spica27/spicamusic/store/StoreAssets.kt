package me.spica27.spicamusic.store

import java.io.File

/**
 * 推荐包资产访问入口（SyncStore 落盘的 assets/ 目录）。
 * UI 层通过这里读取封面图与文章正文；文件不存在时返回 null。
 */
object StoreAssets {
    @Volatile
    var rootDir: File? = null

    fun file(relPath: String): File? {
        val root = rootDir ?: return null
        val f = File(root, "assets/$relPath")
        return if (f.exists()) f else null
    }
}
