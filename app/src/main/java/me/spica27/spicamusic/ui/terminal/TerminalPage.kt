package me.spica27.spicamusic.ui.terminal

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch
import me.spica27.spicamusic.dsh.DshManager
import me.spica27.spicamusic.terminal.TerminalSessionManager

/**
 * 终端页：完整 PTY 终端（Termux terminal-view 渲染）。
 *
 * 职责：
 * - 展示托管 DeepSeek Harness 的终端环境（PTY 会话由 [TerminalSessionManager] 管理）。
 * - dsh 的部署、启停、更新、插件等全部在此环境内以 shell 命令完成，App 不干预。
 * - 首次进入时触发自动初始化（环境部署 + dsh 启动），过程输出直接显示在本终端。
 */
@Composable
fun TerminalPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember { TerminalSessionManager.getOrCreateSession(context) }

    // 首次进入：确保环境就绪（幂等，已在 App 启动时触发过）
    LaunchedEffect(Unit) {
        DshManager.ensureReady(context)
    }

    // 会话意外退出时自动重建（如进程被杀后重进页面）
    LaunchedEffect(session) {
        if (TerminalSessionManager.finished) {
            // 等待上一会话完全释放后重建
            scope.launch {
                kotlinx.coroutines.delay(300)
                TerminalSessionManager.getOrCreateSession(context)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setTerminalViewClient(
                        object : TerminalViewClient {
                            override fun onScale(scale: Float): Float = scale

                            override fun onSingleTapUp(e: MotionEvent) {
                                // 单击聚焦终端，弹出输入法
                                requestFocus()
                            }

                            override fun shouldBackButtonBeMappedToEscape(): Boolean = false

                            override fun shouldEnforceCharBasedInput(): Boolean = false

                            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

                            override fun isTerminalViewSelected(): Boolean = true

                            override fun copyModeChanged(copyMode: Boolean) {}

                            override fun onKeyDown(
                                keyCode: Int,
                                e: KeyEvent,
                                session: TerminalSession,
                            ): Boolean = false

                            override fun onKeyUp(
                                keyCode: Int,
                                e: KeyEvent,
                            ): Boolean = false

                            override fun onLongPress(event: MotionEvent): Boolean =
                                // 禁用文本选择模式（该链路在嵌入场景易触发渲染/坐标崩溃），长按不做任何事
                                true

                            override fun readControlKey(): Boolean = false

                            override fun readAltKey(): Boolean = false

                            override fun readShiftKey(): Boolean = false

                            override fun readFnKey(): Boolean = false

                            override fun onCodePoint(
                                codePoint: Int,
                                ctrlDown: Boolean,
                                session: TerminalSession,
                            ): Boolean = false

                            override fun onEmulatorSet() {}

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
                        },
                    )
                    attachSession(session)
                    setTextSize(14)
                }
            },
            update = { view ->
                val current = view.getCurrentSession()
                if (current == null || current !== TerminalSessionManager.getOrCreateSession(context)) {
                    view.attachSession(TerminalSessionManager.getOrCreateSession(context))
                }
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // 页面离开时仅分离视图，不杀会话（终端进程保持，dsh 继续运行）
            // TerminalView 由 AndroidView 自动销毁，这里无需额外处理
        }
    }
}
