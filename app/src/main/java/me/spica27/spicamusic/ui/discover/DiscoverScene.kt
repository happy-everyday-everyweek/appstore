package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.CardBackground
import me.spica27.spicamusic.common.entity.appstore.StoreCard
import me.spica27.spicamusic.ui.components.StoreSearchBar
import me.spica27.spicamusic.ui.components.gradeColors
import me.spica27.spicamusic.ui.detail.DetailScene
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 推荐页（App Store Today 布局，规格书 v0.4）：
 * 顶部搜索框入口 + 全宽沉浸大卡上下堆叠。
 * 卡片四要素：左上 label / 底部大标题 / 副标题 / 背景（color/gradient/cover/缺省默认渐变）。
 * 未知类型卡安全降级为占位卡（向前兼容）。
 */
@Composable
fun DiscoverScene(onOpenSearch: () -> Unit) {
    val viewModel: StoreViewModel = koinActivityViewModel()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val path = LocalNavigationPath.current

    // 全面屏适配：顶部避开状态栏；底部为常驻底栏避让（内容不被底栏与系统导航条遮挡）
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            // 页头："推荐"（与资料库页的"全部"标题统一）
            Text(
                text = stringResource(R.string.nav_tab_discover),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
        item {
            StoreSearchBar(
                onClick = onOpenSearch,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        if (cards.isEmpty()) {
            item {
                val syncing = viewModel.syncing.collectAsStateWithLifecycle().value
                val error = viewModel.lastSyncError.collectAsStateWithLifecycle().value
                val hint =
                    when {
                        syncing -> "推荐内容同步中…"
                        error != null -> "推荐内容同步失败：$error"
                        else -> "暂无推荐内容"
                    }
                Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(cards.size) { index ->
            val card = cards[index]
            TodayCard(
                card = card,
                appOf = { id -> viewModel.appById(id) },
                onOpenCard = {
                    when (card.type) {
                        "collection" -> path.push(CollectionScene(card))
                        "article" -> path.push(ArticleScene(card))
                        else -> Unit
                    }
                },
                onOpenApp = { meta -> path.push(DetailScene(meta)) },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Today 卡片总入口：按类型渲染（参考工程「信息流卡片」视觉） */
@Composable
fun TodayCard(
    card: StoreCard,
    appOf: (String) -> me.spica27.spicamusic.common.entity.appstore.AppMeta?,
    onOpenCard: () -> Unit,
    onOpenApp: (me.spica27.spicamusic.common.entity.appstore.AppMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownCollection = card.type == "collection"
    val knownArticle = card.type == "article"
    val knownSingle = card.type == "single_app"
    when {
        knownCollection -> {
            // 多应用卡（参考 MultiAppCard：圆角 16、大标题 + 应用行列表）
            val metas = card.appIds.mapNotNull { appOf(it) }
            MultiAppTodayCard(
                title = card.title.ifBlank { card.slug },
                subtitle = card.subtitle,
                label = card.label,
                metas = metas,
                onOpenCard = onOpenCard,
                onOpenApp = onOpenApp,
                modifier = modifier,
            )
        }
        knownSingle -> {
            val meta = card.appIds.firstNotNullOfOrNull { appOf(it) }
            if (meta != null) {
                SingleAppTodayCard(
                    card = card,
                    meta = meta,
                    onOpenCard = { onOpenApp(meta) },
                    modifier = modifier,
                )
            }
        }
        knownArticle -> {
            // 文章卡（封面大卡 + 标题 + 摘要 + 发布日期）
            ArticleTodayCard(
                card = card,
                onOpenCard = onOpenCard,
                modifier = modifier,
            )
        }
        else -> UnknownTodayCard(card = card, modifier = modifier)
    }
}

/** 多应用卡：圆角 16dp 表面色 + 大标题（参考 Apple Today 信息流） */
@Composable
fun MultiAppTodayCard(
    title: String,
    subtitle: String,
    label: String,
    metas: List<me.spica27.spicamusic.common.entity.appstore.AppMeta>,
    onOpenCard: () -> Unit,
    onOpenApp: (me.spica27.spicamusic.common.entity.appstore.AppMeta) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable { onOpenCard() },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (label.isNotBlank()) {
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (metas.isEmpty()) {
                    Text(
                        text = "应用数据待收录或正在同步，稍后自动出现",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    metas.forEach { meta ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenApp(meta) }
                                    .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            me.spica27.spicamusic.ui.components
                                .AppIcon(app = meta, size = 44.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = meta.name.ifBlank { meta.packageName },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = meta.summary.ifBlank { meta.repo },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = meta.grade.label,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MetaGradeColor(meta),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单应用卡：封面大卡 + 应用信息（参考 SingleAppCard） */
@Composable
fun SingleAppTodayCard(
    card: StoreCard,
    meta: me.spica27.spicamusic.common.entity.appstore.AppMeta,
    onOpenCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverFile =
        card.background.cover?.let {
            me.spica27.spicamusic.store.StoreAssets
                .file("covers/$it")
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(230.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CardBackgroundBrush(card.background))
                .clickable { onOpenCard() },
    ) {
        if (coverFile?.exists() == true) {
            TodayCoverImage(card.background.cover, contentDescription = null)
        }
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            if (card.label.isNotBlank()) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                me.spica27.spicamusic.ui.components
                    .AppIcon(app = meta, size = 56.dp)
                Column {
                    Text(
                        text = meta.name.ifBlank { meta.packageName },
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (meta.summary.isNotBlank()) {
                        Text(
                            text = meta.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 文章卡：封面大卡 + 标题 + 摘要 + 发布日期 */
@Composable
fun ArticleTodayCard(
    card: StoreCard,
    onOpenCard: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 230.dp,
) {
    val coverFile =
        card.background.cover?.let {
            me.spica27.spicamusic.store.StoreAssets
                .file("covers/$it")
        }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(height)
                .clip(RoundedCornerShape(22.dp))
                .background(CardBackgroundBrush(card.background))
                .clickable { onOpenCard() },
    ) {
        if (coverFile?.exists() == true) {
            TodayCoverImage(card.background.cover, contentDescription = null)
        }
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            if (card.label.isNotBlank()) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = card.title.ifBlank { card.slug },
                    style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.subtitle.isNotBlank()) {
                    Text(
                        text = card.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                card.publishDate?.let { date ->
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 未知类型占位卡 */
@Composable
fun UnknownTodayCard(
    card: StoreCard,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(140.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "新内容类型「${card.type}」",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 评级色（信息流行内小徽标文字） */
@Composable
private fun MetaGradeColor(meta: me.spica27.spicamusic.common.entity.appstore.AppMeta): Color = gradeColors[meta.grade] ?: Color.Gray

/** 卡片背景：cover > color > gradient > 默认深色渐变兜底 */
@Composable
fun CardBackgroundBrush(background: CardBackground): Brush {
    val cover = background.cover
    if (!cover.isNullOrBlank()) {
        // 封面随推荐包落盘在 store/assets/covers/<slug>.png
        val file =
            me.spica27.spicamusic.store.StoreAssets
                .file("covers/$cover")
        if (file?.exists() == true) {
            return Brush.verticalGradient(
                listOf(Color(0xFF1A1A2E), Color(0xFF16213E)),
            )
        }
    }
    return when {
        background.color != null -> {
            val c = background.color?.let { parseColor(it) } ?: Color(0xFF2B3A55)
            Brush.verticalGradient(listOf(c, c))
        }
        background.gradient.isNotEmpty() ->
            Brush.verticalGradient(
                background.gradient.map { parseColor(it) },
            )
        else -> Brush.verticalGradient(listOf(Color(0xFF2B3A55), Color(0xFF10182B)))
    }
}

private fun parseColor(hex: String): Color =
    runCatching {
        val cleaned = hex.removePrefix("#")
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> Color(0xFF2B3A55)
        }
    }.getOrDefault(Color(0xFF2B3A55))

/** 封面图加载（随包资产，离线可用；损坏文件安全回退） */
@Composable
fun TodayCoverImage(
    cover: String?,
    contentDescription: String? = null,
) {
    val file =
        me.spica27.spicamusic.store.StoreAssets
            .file("covers/$cover")
    val bitmap = file?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            painter =
                androidx.compose.ui.graphics.painter
                    .BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}
