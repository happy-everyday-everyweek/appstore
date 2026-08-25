package me.spica27.spicamusic.store

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志等级：与 Android Logcat 对齐，覆盖冗余/调试/信息/警告/错误五档 */
enum class LogLevel(
    val code: String,
    val label: String,
    val androidLevel: Int,
) {
    VERBOSE("V", "冗余", android.util.Log.VERBOSE),
    DEBUG("D", "调试", android.util.Log.DEBUG),
    INFO("I", "信息", android.util.Log.INFO),
    WARN("W", "警告", android.util.Log.WARN),
    ERROR("E", "错误", android.util.Log.ERROR),
    ;

    companion object {
        fun fromCode(code: String): LogLevel = entries.firstOrNull { it.code == code } ?: INFO
    }
}

/**
 * 应用内日志系统：环形缓冲 + 文件导出。
 * 每条日志都带等级（信息/警告/错误等），同步输出到 Logcat 便于 adb 排查；
 * 关键链路（同步/下载/自更新/崩溃路径）埋点，设置页提供日志查看/复制/导出。
 */
object DebugLog {
    data class Entry(
        val time: String,
        val tag: String,
        val level: String,
        val label: String,
        val message: String,
    )

    private const val MAX_ENTRIES = 1000
    private val buffer = ArrayDeque<Entry>()

    @Synchronized
    fun i(
        tag: String,
        message: String,
    ) = push(LogLevel.INFO, tag, message)

    @Synchronized
    fun w(
        tag: String,
        message: String,
    ) = push(LogLevel.WARN, tag, message)

    @Synchronized
    fun e(
        tag: String,
        message: String,
    ) = push(LogLevel.ERROR, tag, message)

    @Synchronized
    fun d(
        tag: String,
        message: String,
    ) = push(LogLevel.DEBUG, tag, message)

    @Synchronized
    fun v(
        tag: String,
        message: String,
    ) = push(LogLevel.VERBOSE, tag, message)

    private fun push(
        level: LogLevel,
        tag: String,
        message: String,
    ) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        buffer.addLast(Entry(ts, tag, level.code, level.label, message))
        while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        // 同步输出 Logcat（Tag 前缀 AppStore/），便于 adb 无线调试抓取
        android.util.Log.println(level.androidLevel, "AppStore/$tag", message)
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
