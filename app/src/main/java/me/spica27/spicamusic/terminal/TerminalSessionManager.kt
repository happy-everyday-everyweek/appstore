package me.spica27.spicamusic.terminal

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * 终端会话管理器。
 *
 * 管理唯一一个 PTY 终端会话（Termux terminal-emulator 驱动）：
 * - shell 优先使用 App 私有目录下的 Termux 环境（files/usr/bin/bash），
 *   不存在时回退到 Android 自带 sh（此时终端环境尚未部署）。
 * - dsh 及其依赖全部运行在该会话的进程树中（终端环境），与 App 进程隔离。
 */
object TerminalSessionManager {
    private const val TRANSCRIPT_ROWS = 10000

    @Volatile
    private var session: TerminalSession? = null

    @Volatile
    var shellPid: Int = -1
        private set

    /** 会话是否已结束（进程退出）。 */
    @Volatile
    var finished: Boolean = true
        private set

    private val client =
        object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}

            override fun onTitleChanged(changedSession: TerminalSession) {}

            override fun onSessionFinished(finishedSession: TerminalSession) {
                finished = true
                shellPid = -1
            }

            override fun onCopyTextToClipboard(
                session: TerminalSession,
                text: String,
            ) {}

            override fun onPasteTextFromClipboard(session: TerminalSession?) {}

            override fun onBell(session: TerminalSession) {}

            override fun onColorsChanged(session: TerminalSession) {}

            override fun onTerminalCursorStateChange(state: Boolean) {}

            override fun setTerminalShellPid(
                session: TerminalSession,
                pid: Int,
            ) {
                shellPid = pid
                finished = false
            }

            override fun getTerminalCursorStyle(): Int? = null

            override fun logError(
                tag: String?,
                message: String?,
            ) {}

            override fun logWarn(
                tag: String?,
                message: String?,
            ) {}

            override fun logInfo(
                tag: String?,
                message: String?,
            ) {}

            override fun logDebug(
                tag: String?,
                message: String?,
            ) {}

            override fun logVerbose(
                tag: String?,
                message: String?,
            ) {}

            override fun logStackTraceWithMessage(
                tag: String?,
                message: String?,
                e: Exception?,
            ) {}

            override fun logStackTrace(
                tag: String?,
                e: Exception?,
            ) {}
        }

    /** 终端环境前缀目录（Termux 风格：$PREFIX）。 */
    fun prefixDir(context: Context): File = File(context.filesDir, "usr")

    /** 终端环境 home 目录。 */
    fun homeDir(context: Context): File = File(context.filesDir, "home")

    /** 终端环境是否已部署（存在 bash 即视为已部署）。 */
    fun isEnvironmentInstalled(context: Context): Boolean = File(prefixDir(context), "bin/bash").exists()

    /** 获取当前会话；未创建或已退出时新建。 */
    fun getOrCreateSession(context: Context): TerminalSession {
        val current = session
        if (current != null && !finished) return current
        current?.finishIfRunning()

        val prefix = prefixDir(context)
        val home = homeDir(context).apply { mkdirs() }
        File(context.filesDir, "tmp").apply { mkdirs() }

        val shell =
            if (File(prefix, "bin/bash").exists()) {
                "$prefix/bin/bash"
            } else {
                "/system/bin/sh"
            }

        val env =
            buildList {
                add("HOME=${home.absolutePath}")
                add("PREFIX=$prefix")
                add("TERM=xterm-256color")
                add("TMPDIR=${File(context.filesDir, "tmp").absolutePath}")
                add(
                    "PATH=$prefix/bin:" +
                        File(prefix, "bin/applets").absolutePath +
                        ":/system/bin:/system/xbin",
                )
                add("LD_LIBRARY_PATH=${File(prefix, "lib").absolutePath}")
                add("LANG=en_US.UTF-8")
            }.toTypedArray()

        val newSession =
            TerminalSession(
                shell,
                home.absolutePath,
                emptyArray(),
                env,
                TRANSCRIPT_ROWS,
                client,
            )
        session = newSession
        return newSession
    }

    /** 向当前会话写入一行命令并回车。 */
    fun writeCommand(
        context: Context,
        command: String,
    ): Boolean {
        val s = getOrCreateSession(context)
        s.write(command.trimEnd('\n') + "\r")
        return true
    }

    /** 销毁当前会话（杀掉 shell 进程树）。 */
    fun destroySession() {
        session?.finishIfRunning()
        session = null
        shellPid = -1
        finished = true
    }
}
