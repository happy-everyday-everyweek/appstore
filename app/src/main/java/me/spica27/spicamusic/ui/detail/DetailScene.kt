package me.spica27.spicamusic.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppIcon
import me.spica27.spicamusic.ui.components.OpenSourceTag
import me.spica27.spicamusic.ui.components.TagChip
import me.spica27.spicamusic.ui.components.gradeColors
import me.spica27.spicamusic.ui.discover.MarkdownPlain
import me.spica27.spicamusic.ui.home.StoreViewModel
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

    // 全面屏适配：全屏详情页避开状态栏与系统导航条；实色背景避免转场露出黑边
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
    ) {
        // 背景：图标模糊铺底
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

        // 内容
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            // 返回按钮
            IconButton(
                onClick = { path.popTop() },
                modifier =
                    Modifier
                        .padding(8.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f)),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }

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
                    Text(
                        text = MarkdownPlain.render(readmeText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text =
                            buildString {
                                append("README 已随应用收录并存档于承载仓库（随包同步中）；全文可前往开发者仓库查看：\n")
                                append("github.com/").append(app.repo.ifBlank { "（仓库信息同步中）" })
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
                            text = "无特殊权限（none）",
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
                            text = "完整权限清单（取自 APK 解包）：",
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

        // 悬浮下载按钮（经 GitLink 下载底座直连开发者 Release）
        ExtendedFloatingActionButton(
            onClick = { viewModel.downloadApk(context, app) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            icon = {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                )
            },
            text = {
                Text(text = stringResource(R.string.detail_download))
            },
        )
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
