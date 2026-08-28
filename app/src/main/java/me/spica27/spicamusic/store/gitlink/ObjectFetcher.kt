package me.spica27.spicamusic.store.gitlink

import java.io.File

/**
 * v2 对象获取器：manifest / index / icon / bundle 的通用下载入口。
 *
 * 与 v1 [me.spica27.spicamusic.store.Downloader] 的差异：
 * - 额外携带 isRaw（raw.githubusercontent.com 直链 → 仅路由支持 raw 的镜像）；
 * - 内部走 [MirrorScheduler] 的会话级测速 + 下载中换源，不再每文件全量测速。
 * 抽象为接口便于单测注入内存 Fake。
 */
interface ObjectFetcher {
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
        isRaw: Boolean = false,
    ): File
}
