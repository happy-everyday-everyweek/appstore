package me.spica27.spicamusic.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppIcon
import me.spica27.spicamusic.ui.components.OpenSourceTag
import me.spica27.spicamusic.ui.components.SpeedChart
import me.spica27.spicamusic.ui.components.TagChip
import me.spica27.spicamusic.ui.components.gradeColors
import me.spica27.spicamusic.ui.home.StoreViewModel
import me.spica27.spicamusic.ui.home.StoreViewModel.DownloadTaskUi
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 应用详情页
 * 布局：图标模糊背景；顶部图标/名称/简介；标签行（评级/开闭源/权限）；
 * README 内容；权限列表；悬浮下载按钮。
 */
class DetailScene(
    private val app: AppMeta,
) : StackScene() {
    @Composable
    override fun Content() {
        DetailScreen(app = app)
    }
}

@Composable
fun DetailScreen(app: AppMeta) {
    val path = LocalNavigationPath.current
    val context = LocalContext.current
    val viewModel: StoreViewModel = koinActivityViewModel()

    // 下载结果提示（一次性消费）
    val lastDownload by viewModel.lastDownload.collectAsStateWithLifecycle()
    LaunchedEffect(lastDownload) {
        lastDownload?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeDownloadMessage { }
        }
    }
    // 上游应用（upstream 指向的系统 ID 解析）
    val upstreamApp = app.upstreamId?.let { viewModel.appById(it) }

    // 全面屏适配：全屏详情页背景铺满含状态栏（图标模糊打底），顶部渐变遮罩压平状态栏色差
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        // 背景：图标模糊铺底（延伸到状态栏下方，无白条）
        if (app.icon.isNotBlank()) {
            me.spica27.spicamusic.ui.components
                .StoreAsyncIconBackground(url = app.icon)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
        )
        // 顶部渐变遮罩（黑→透明），压平状态栏区域与内容的过渡
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                        ),
                    ),
        )

        // 悬浮返回按钮（独立于滚动内容，避让状态栏）
        androidx.compose.material3.IconButton(
            onClick = { path.popTop() },
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White,
            )
        }

        // 内容
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            // 顶部占位（状态栏 + 悬浮返回按钮高度，内容滚动时不遮挡）
            Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars))
            Spacer(modifier = Modifier.height(56.dp))

            // 图标
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    app = app,
                    size = 96.dp,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)),
                )
            }

            // 名称
            Text(
                text = app.name.ifBlank { app.packageName },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )

            // 简介
            if (app.summary.isNotBlank()) {
                Text(
                    text = app.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }

            // 标签行：评级 / 开源闭源 / 最低权限
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(
                    text = app.grade.label,
                    background = gradeColors[app.grade] ?: Color.Gray,
                    contentColor = Color.White,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OpenSourceTag(isOpenSource = app.openSource)
                app.specialPermissions.forEach { perm ->
                    Spacer(modifier = Modifier.size(8.dp))
                    TagChip(
                        text = perm.label,
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // 来源仓库 + 完整校验信息（用户故事 17：校验和与来源 Release 可见）
            if (app.repo.isNotBlank()) {
                Text(
                    text =
                        "${app.repo} · ${app.license ?: "无 License"}" +
                            if (app.version.releaseTag.isNotBlank()) " · ${app.version.releaseTag}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                )
            }
            if (app.packageName.isNotBlank() || app.apkSha256.isNotBlank()) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (app.packageName.isNotBlank()) {
                        Text(
                            text = "包名：${app.packageName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (app.apkSha256.isNotBlank()) {
                        Text(
                            text = "APK SHA-256：${app.apkSha256.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (app.apkUrl.isNotBlank()) {
                        androidx.compose.foundation.text.ClickableText(
                            text =
                                androidx.compose.ui.text.buildAnnotatedString {
                                    append("APK 来源直链：${app.apkUrl}")
                                    addStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            color = Color.White.copy(alpha = 0.85f),
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                        ),
                                        0,
                                        length,
                                    )
                                },
                            style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                            modifier = Modifier.padding(top = 4.dp),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(app.apkUrl),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            // 上游应用入口（upstream）
            if (upstreamApp != null) {
                Surface(
                    onClick = { path.push(DetailScene(upstreamApp)) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.14f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(app = upstreamApp, size = 40.dp)
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        ) {
                            Text(
                                text = "上游应用",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.7f),
                            )
                            Text(
                                text = upstreamApp.name.ifBlank { upstreamApp.packageName },
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 介绍（README）：优先读随包资产 assets/readmes/<id>.md（Markdown 轻渲染）
            SectionCard(title = stringResource(R.string.detail_readme)) {
                val readmeText =
                    remember(app.readme) {
                        if (app.readme.isBlank()) {
                            null
                        } else {
                            me.spica27.spicamusic.store.StoreAssets
                                .file(app.readme.removePrefix("assets/"))
                                ?.readText()
                        }
                    }
                if (readmeText != null) {
                    me.spica27.spicamusic.ui.components
                        .MarkdownContent(md = readmeText)
                } else {
                    Text(
                        text =
                            buildString {
                                append("README 已随应用同步，正在补齐内容；也可前往开发者仓库查看：\n")
                                append("github.com/").append(app.repo.ifBlank { "仓库信息同步中" })
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 权限区：最低特殊权限标签 + APK 解包提取的完整权限清单
            SectionCard(title = stringResource(R.string.detail_permissions)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (app.specialPermissions.isEmpty()) {
                        Text(
                            text = "无特殊权限",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        app.specialPermissions.forEach { perm ->
                            Text(
                                text = "• ${perm.label}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (app.permissions.isNotEmpty()) {
                        Text(
                            text = "完整权限清单：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        app.permissions.forEach { permission ->
                            Text(
                                text = "• $permission",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }

        // 底部下载栏（参考工程下载交互：状态式按钮 + 下载中实时进度/速度折线图）
        val task by viewModel.downloadTask.collectAsStateWithLifecycle()
        val downloading by viewModel.downloading.collectAsStateWithLifecycle()
        val lastDownloadedApk by viewModel.lastDownloadedApk.collectAsStateWithLifecycle()
        DownloadBar(
            app = app,
            task = task,
            downloading = downloading,
            lastDownloadedApk = lastDownloadedApk,
            onDownload = { viewModel.downloadApk(context, app) },
            onReinstall = { viewModel.reinstallLastDownload(context) },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** 底部下载栏：应用名 + 状态按钮（下载/下载中进度/安装/重试）；下载中展开速度折线图 */
@Composable
private fun DownloadBar(
    app: AppMeta,
    task: StoreViewModel.DownloadTaskUi?,
    downloading: Boolean,
    lastDownloadedApk: String?,
    onDownload: () -> Unit,
    onReinstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val completed = task?.done == true && !task.status.startsWith("下载失败") && task.lastFile != null
    val failed = task?.done == true && task.status.startsWith("下载失败")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app = app, size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name.ifBlank { app.packageName },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            when {
                                downloading -> task?.status ?: "下载中…"
                                completed -> "已下载 · 点击右侧按钮安装"
                                failed -> task?.status ?: "下载失败，点击重试"
                                else -> "开发者 Release 直连 · SHA-256 校验"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Button(
                    onClick = { if (completed) onReinstall() else onDownload() },
                    enabled = !downloading,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    when {
                        downloading -> Text("${(task?.progress?.times(100))?.toInt() ?: 0}%")
                        completed -> Text("安装")
                        failed -> Text("重试")
                        else -> Text("下载")
                    }
                }
            }
            // 下载中：进度条 + 实时速度 + 高采样速度折线图（反映镜像质量与下载进行状态）
            if (downloading) {
                val history = task?.speedHistory.orEmpty()
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { task?.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "实时 ${formatSpeed(history.lastOrNull())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (history.size >= 2) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "平均 ${formatSpeed(history.average().toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatSpeed(history.maxOrNull()) + " 峰值",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                SpeedChart(
                    history = history,
                    color = MaterialTheme.colorScheme.primary,
                    gridColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

/** 瞬时速度格式化：B/s -> KB/s -> MB/s */
private fun formatSpeed(speedBps: Long?): String {
    val v = speedBps ?: 0L
    return when {
        v >= 1024L * 1024L -> String.format("%.1f MB/s", v / 1024f / 1024f)
        v >= 1024L -> String.format("%.1f KB/s", v / 1024f)
        else -> "$v B/s"
    }
}

/** 白底圆角内容卡片区块 */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
