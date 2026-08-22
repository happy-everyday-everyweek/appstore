package me.spica27.spicamusic.ui.chat

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.spicamusic.dsh.DshManager
import me.spica27.spicamusic.dsh.WebViewHolder

/**
 * 对话页：WebView 展示 DeepSeek Harness 官方 Web UI（127.0.0.1:3080）。
 *
 * - 服务未就绪时自动等待（环境部署/服务启动由 [DshManager.ensureReady] 全自动执行），
 *   就绪后自动加载。
 * - 不做任何 UI 封装，交互全部发生在 dsh 原生界面内。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val envState by DshManager.envState.collectAsStateWithLifecycle()
    val serviceState by DshManager.serviceState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DshManager.ensureReady(context)
    }

    val webView =
        remember {
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString =
                        userAgentString
                            .replace("; wv", "")
                            .replace("Version/\\d+(\\.\\d+)*", "Version/4.0")
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                }
            }
        }

    // 注册到全局发送通道（底部输入栏 → dsh 会话）
    DisposableEffect(webView) {
        WebViewHolder.register(webView)
        onDispose {
            WebViewHolder.unregister(webView)
        }
    }

    val ready = envState is DshManager.EnvState.Ready && serviceState is DshManager.ServiceState.Running

    LaunchedEffect(ready) {
        if (ready) {
            webView.loadUrl(DshManager.DSH_URL)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { webView },
        )

        if (!ready) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = statusText(envState, serviceState),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "首次使用需要自动部署终端环境并安装 DeepSeek Harness，\n部署过程可在「终端」页查看",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(
    envState: DshManager.EnvState,
    serviceState: DshManager.ServiceState,
): String =
    when (envState) {
        DshManager.EnvState.NotInstalled -> "正在初始化终端环境…"
        is DshManager.EnvState.Downloading -> "正在下载终端环境 ${envState.percent}%…"
        DshManager.EnvState.Extracting -> "正在解压终端环境…"
        DshManager.EnvState.Installing -> "正在安装 DeepSeek Harness 依赖…"
        DshManager.EnvState.Ready -> {
            when (serviceState) {
                DshManager.ServiceState.Stopped -> "正在启动 DeepSeek Harness…"
                DshManager.ServiceState.Starting -> "正在启动 DeepSeek Harness…"
                DshManager.ServiceState.Running -> "正在连接…"
                is DshManager.ServiceState.Error -> "服务异常：${serviceState.message}"
            }
        }
        is DshManager.EnvState.Error -> "初始化失败：${envState.message}"
    }
