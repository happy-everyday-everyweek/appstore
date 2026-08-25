package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.common.entity.appstore.StoreCard
import me.spica27.spicamusic.ui.components.AppIcon
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 文章阅读页：正文 Markdown 随推荐包下发（store/assets/articles/<slug>.md），
 * 离线可读，显示行为绝不从 GitHub 拉取（规格书 v0.4）。
 * 第一版采用轻量 Markdown 转纯文本渲染。
 */
class ArticleScene(
    private val card: StoreCard,
) : StackScene() {
    @Composable
    override fun Content() {
        val path = LocalNavigationPath.current
        val context = LocalContext.current
        val viewModel: StoreViewModel = koinActivityViewModel()
        val apps by viewModel.apps.collectAsStateWithLifecycle()
        val task by viewModel.downloadTask.collectAsStateWithLifecycle()
        val downloading by viewModel.downloading.collectAsStateWithLifecycle()
        val content =
            remember(card.slug, card.article) {
                val rel =
                    card.article?.removePrefix("articles/")
                        ?: "${card.slug}.md"
                me.spica27.spicamusic.store.StoreAssets
                    .file("articles/$rel")
                    ?.readText()
                    ?: "（正文尚未随包同步，请稍后重试）"
            }
        val relatedApps = remember(card.appIds) { card.appIds.mapNotNull { apps[it] } }

        // 全面屏适配：全屏文章页避开状态栏与系统导航条；实色背景避免转场露出黑边
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            IconButton(
                onClick = { path.popTop() },
                modifier = Modifier.padding(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "back",
                )
            }
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                card.subtitle.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                androidx.compose.material3.HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                me.spica27.spicamusic.ui.components
                    .MarkdownContent(md = content)
                // 关联应用：文章内嵌卡片（圆角 16 卡片 + 下载按钮；与详情页黑色长条区分用途）
                if (relatedApps.isNotEmpty()) {
                    Text(
                        text = "关联应用（${relatedApps.size}）",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                    relatedApps.forEach { meta ->
                        RelatedAppCard(
                            app = meta,
                            task = task,
                            downloading = downloading,
                            onOpen = {
                                path.push(
                                    me.spica27.spicamusic.ui.detail
                                        .DetailScene(meta),
                                )
                            },
                            onDownload = { viewModel.downloadApk(context, meta) },
                            onReinstall = { viewModel.reinstallLastDownload(context) },
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp)) {}
            }
        }
    }
}

/** 文章关联应用卡片：圆角 16 卡片 + 图标/名称/简介 + 状态式下载按钮（卡片形态仅用于文章） */
@Composable
private fun RelatedAppCard(
    app: AppMeta,
    task: StoreViewModel.DownloadTaskUi?,
    downloading: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onReinstall: () -> Unit,
) {
    val isThisTask = task?.appId == app.id
    val completed = isThisTask && task.done && !task.status.startsWith("下载失败") && task.lastFile != null
    val failed = isThisTask && task.done && task.status.startsWith("下载失败")
    val busyHere = downloading && isThisTask

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(app = app, size = 56.dp)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.name.ifBlank { app.packageName },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = app.summary.ifBlank { app.repo },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = { if (completed) onReinstall() else onDownload() },
                enabled = !busyHere,
                shape = RoundedCornerShape(12.dp),
            ) {
                when {
                    busyHere -> Text("${(task.progress * 100).toInt()}%")
                    completed -> Text("安装")
                    failed -> Text("重试")
                    else -> Text("下载")
                }
            }
        }
    }
}

/** 轻量 Markdown → 纯文本（#、-、**、> 等符号剥离） */
object MarkdownPlain {
    fun render(md: String): String =
        md
            .lineSequence()
            .joinToString("\n") { line ->
                line
                    .trimStart()
                    .removePrefix("#")
                    .trimStart()
                    .removePrefix(">")
                    .trimStart()
                    .removePrefix("-")
                    .trimStart()
                    .removePrefix("*")
                    .trimStart()
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
                    .replace(Regex("`(.+?)`"), "$1")
            }
}
