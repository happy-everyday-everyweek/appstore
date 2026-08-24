package me.spica27.spicamusic.store

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内日志系统：环形缓冲 + 文件导出。
 * 关键链路（同步/下载/自更新/崩溃路径）埋点，设置页提供日志查看/复制/导出。
 */
object DebugLog {
    data class Entry(
        val time: String,
        val tag: String,
        val level: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 1000
    private val buffer = ArrayDeque<Entry>()

    @Synchronized
    fun i(
        tag: String,
        message: String,
    ) = push("I", tag, message)

    @Synchronized
    fun w(
        tag: String,
        message: String,
    ) = push("W", tag, message)

    @Synchronized
    fun e(
        tag: String,
        message: String,
    ) = push("E", tag, message)

    private fun push(
        level: String,
        tag: String,
        message: String,
    ) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        buffer.addLast(Entry(ts, tag, level, message))
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()

    @Synchronized
    fun text(): String = buffer.joinToString("\n") { "[${it.time}] ${it.level}/${it.tag} ${it.message}" }

    /** 导出日志到 cacheDir/logs/appstore-<ts>.log，返回文件 */
    fun exportFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "appstore-${System.currentTimeMillis()}.log")
        file.writeText(text())
        return file
    }
}
