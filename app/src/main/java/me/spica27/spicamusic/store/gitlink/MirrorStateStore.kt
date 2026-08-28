package me.spica27.spicamusic.store.gitlink

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 镜像调度器会话状态持久化（v2：会话级测速，不再每文件全量测速）。
 *
 * 记录每个镜像的可达性/首字节延迟/连续失败/EWMA 吞吐，供下一次会话
 * 跳过探测直接复用 top-N（TTL 24h 后自动重新探测，连续失败镜像自动复活）。
 * 用 kotlinx.serialization 而非 org.json，保证 JVM 单测可用。
 */
@Serializable
data class MirrorStateEntry(
    val latencyMs: Long = 0,
    val lastOkAt: Long = 0,
    val fails: Int = 0,
    val ewmaBps: Long = 0,
    /** 是否成功下载过 raw.githubusercontent.com 直链（raw 通道仅路由到 rawOk 镜像） */
    val rawOk: Boolean = false,
)

@Serializable
data class MirrorStateSnapshot(
    val probedAt: Long = 0,
    val mirrors: Map<String, MirrorStateEntry> = emptyMap(),
)

class MirrorStateStore(
    private val rootDir: File,
) {
    private val file: File = File(rootDir, "mirrors.state.json")

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): MirrorStateSnapshot =
        runCatching {
            if (file.exists()) {
                json.decodeFromString(MirrorStateSnapshot.serializer(), file.readText())
            } else {
                MirrorStateSnapshot()
            }
        }.getOrDefault(MirrorStateSnapshot())

    fun save(snapshot: MirrorStateSnapshot) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(snapshot))
        }
    }
}
