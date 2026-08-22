package me.spica27.spicamusic.crash

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Logcat 守护：周期性抓取系统日志（优先 su/root 读 crash buffer），
 * 持续落盘到 /sdcard/Download/DSH-logs/ 与私有目录。
 *
 * 用途：native 崩溃（SIGSEGV 等）不经过 Java UncaughtExceptionHandler，
 * 但 crash buffer 里有完整的 Fatal signal backtrace；本守护把崩溃前最后
 * 一段日志保留下来，供事后定位。
 */
object LogcatKeeper {

    private const val TAG = "DSH_LOGCAT"
    private const val INTERVAL_MS = 3000L
    private const val TAIL_LINES = 400

    @Volatile
    private var running = false

    fun start(context: Context) {
        if (running) return
        running = true
        Thread({ loop(context.applicationContext) }, "dsh-logcat-keeper").apply {
            isDaemon = true
            start()
        }
    }

    private fun loop(context: Context) {
        val privateDir = File(context.filesDir, "logcat").apply { mkdirs() }
        val publicFile = File("/sdcard/Download/DSH-logs/logcat.log")
        val privateFile = File(privateDir, "logcat.log")
        var lastTail: String? = null

        while (running) {
            try {
                val output = dumpLogcat()
                if (output != null && output != lastTail) {
                    lastTail = output
                    // 私有目录（始终可写）
                    runCatching { privateFile.writeText(output) }
                    // 公共目录（MANAGE_EXTERNAL_STORAGE 授权后可写；失败静默）
                    runCatching {
                        if (!publicFile.parentFile?.exists()!!) {
                            publicFile.parentFile?.mkdirs()
                        }
                        publicFile.writeText(output)
                    }
                    // 双份滚动，防止单文件被截断
                    runCatching {
                        File(privateDir, "logcat-prev.log").writeText(
                            if (privateFile.exists()) privateFile.readText() else "",
                        )
                    }
                    Log.i(TAG, "logcat snapshot ${output.length} chars")
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** 抓取日志：先试 su(root)，失败回退普通 logcat（仅自己进程可见）。 */
    private fun dumpLogcat(): String? {
        var out = exec(
            listOf("su", "-c", "logcat -d -b crash -b main -b system -v threadtime -t $TAIL_LINES"),
        )
        if (out == null) {
            out = exec(listOf("logcat", "-d", "-b", "main", "-v", "threadtime", "-t", "$TAIL_LINES"))
        }
        return out
    }

    private fun exec(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy()
            }
            text
        } catch (e: IOException) {
            null
        } catch (_: Exception) {
            null
        }
}