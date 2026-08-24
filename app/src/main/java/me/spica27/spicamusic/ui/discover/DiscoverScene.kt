package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.spicamusic.common.entity.appstore.CardBackground
import me.spica27.spicamusic.common.entity.appstore.StoreCard
import me.spica27.spicamusic.ui.components.StoreSearchBar
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
                onClick = {
                    when (card.type) {
                        "collection" -> path.push(CollectionScene(card))
                        "article" -> path.push(ArticleScene(card))
                        else -> Unit // 未知类型：占位展示，不可点击深入
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** Today 全宽大卡：高约 230dp、圆角 22dp、屏宽 − 32dp */
@Composable
fun TodayCard(
    card: StoreCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val knownType = card.type == "collection" || card.type == "article"
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
                .clickable(enabled = knownType) { onClick() },
    ) {
        // 封面大图（随包资产）铺满，文字层在上
        if (coverFile?.exists() == true) {
            TodayCoverImage(card.background.cover, contentDescription = null)
        }
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            // 左上分类标签
            if (card.label.isNotBlank()) {
                Text(
                    text = card.label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
            // 底部标题 + 副标题
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Text(
                    text = card.title.ifBlank { if (knownType) card.slug else "新内容类型「${card.type}」" },
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
                    )
                }
            }
        }
    }
}

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
