package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import me.spica27.spicamusic.ui.detail.DetailScene
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 集合页（参考工程「主题合集详情」视觉）：
 * 全宽封面大卡（Today 同款背景/封面 + 底部渐变遮罩 + 白色大标题叠加 + 左上返回与 label），
 * 下方「包含应用（N 个）」标题 + 卡片化应用行（圆角 16、56dp 图标、名称/简介、状态式下载按钮）。
 */
class CollectionScene(
    private val card: StoreCard,
) : StackScene() {
    @Composable
    override fun Content() {
        val viewModel: StoreViewModel = koinActivityViewModel()
        val apps by viewModel.apps.collectAsStateWithLifecycle()
        val task by viewModel.downloadTask.collectAsStateWithLifecycle()
        val downloading by viewModel.downloading.collectAsStateWithLifecycle()
        val path = LocalNavigationPath.current
        val context = LocalContext.current

        // 按卡片 apps 顺序取元数据；缺失 id 不展示（数量由标题体现）
        val metas = card.appIds.mapNotNull { id -> apps[id] }

        // 全面屏适配：全屏集合页避开状态栏与系统导航条；实色背景避免转场露出黑边
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    CollectionHeroHeader(
                        card = card,
                        onBack = { path.popTop() },
                    )
                }
                item {
                    Text(
                        text = "包含应用（${metas.size} 个）",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier =
                            Modifier.padding(
                                start = 20.dp,
                                top = 20.dp,
                                end = 20.dp,
                                bottom = 8.dp,
                            ),
                    )
                }
                if (metas.isEmpty() && card.appIds.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "集合内应用尚未同步，请稍后重试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(metas, key = { it.id }) { app ->
                        CollectionAppCard(
                            app = app,
                            task = task,
                            downloading = downloading,
                            onOpen = { path.push(DetailScene(app)) },
                            onDownload = { viewModel.downloadApk(context, app) },
                            onReinstall = { viewModel.reinstallLastDownload(context) },
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

/** 集合页头部：全宽封面 + 返回键 + 左上 label + 底部渐变遮罩 + 白色大标题（参考工程 ThemeHeader） */
@Composable
private fun CollectionHeroHeader(
    card: StoreCard,
    onBack: () -> Unit,
) {
    val coverFile =
        card.background.cover?.let {
            me.spica27.spicamusic.store.StoreAssets
                .file("covers/$it")
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .background(CardBackgroundBrush(card.background)),
    ) {
        if (coverFile?.exists() == true) {
            TodayCoverImage(card.background.cover, contentDescription = null)
        }
        // 底部渐变遮罩，使白色标题在图片上可读
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        ),
                    ),
        )
        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "back",
                tint = Color.White,
            )
        }
        // 标题叠加在封面底部
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (card.label.isNotBlank()) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = card.title.ifBlank { card.slug },
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (card.subtitle.isNotBlank()) {
                Text(
                    text = card.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 集合内应用行：圆角 16 卡片 + 56dp 图标 + 名称/简介 + 状态式下载按钮（参考工程 ThemeAppRow） */
@Composable
private fun CollectionAppCard(
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
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
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
