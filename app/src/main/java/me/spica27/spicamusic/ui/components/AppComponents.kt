package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppGrade
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.utils.blurhash.BlurHashDecoder

/** 评级徽章颜色 */
val gradeColors: Map<AppGrade, Color> =
    mapOf(
        AppGrade.A to Color(0xFFB8860B),
        AppGrade.B to Color(0xFF1565C0),
        AppGrade.C to Color(0xFF2E7D32),
        AppGrade.D to Color(0xFF616161),
        AppGrade.E to Color(0xFF424242),
    )

/** 小标签 */
@Composable
fun TagChip(
    text: String,
    background: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = background,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}

/** 开源状态标签 */
@Composable
fun OpenSourceTag(isOpenSource: Boolean) {
    if (isOpenSource) {
        TagChip(
            text = stringResource(R.string.label_open_source),
            background = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        TagChip(
            text = stringResource(R.string.label_closed_source),
            background = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 应用图标（图标地址缺失时显示 Android 占位图标） */
@Composable
fun AppIcon(
    app: AppMeta,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .clip(RoundedCornerShape(size / 4))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (app.icon.isBlank()) {
            IconPlaceholder(app = app, size = size)
        } else if (app.icon.startsWith("assets/")) {
            // 随包图标资产（聚合包 assets/icons/<id>.<ext>）
            val rel = app.icon.removePrefix("assets/")
            val file =
                me.spica27.spicamusic.store.StoreAssets
                    .file(rel)
            val bitmap =
                file?.let {
                    runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
                }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    painter =
                        androidx.compose.ui.graphics.painter
                            .BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = app.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                me.spica27.spicamusic.store.DebugLog
                    .w(
                        "Icon",
                        "图标解码失败 app=${app.id} icon=${app.icon} file=${file?.absolutePath} 存在=${file?.exists() ?: false}",
                    )
                IconPlaceholder(app = app, size = size)
            }
        } else {
            androidx.compose.runtime.key(app.icon) {
                me.spica27.spicamusic.ui.components.StoreAsyncIcon(
                    url = app.icon,
                    size = size,
                    placeholderIcon = Icons.Default.Android,
                )
            }
        }
    }
}

/** 图标加载态占位：v2 列表页先以 blurhash 绘制（规格 §6.6），无 blurhash 时回落 Android 图标 */
@Composable
private fun IconPlaceholder(
    app: AppMeta,
    size: androidx.compose.ui.unit.Dp,
) {
    val blurBitmap =
        remember(app.iconBlurhash) {
            if (app.iconBlurhash.isBlank()) {
                null
            } else {
                runCatching { BlurHashDecoder.decode(app.iconBlurhash, width = 32, height = 32) }
                    .getOrNull()
            }
        }
    if (blurBitmap != null) {
        Image(
            painter = BitmapPainter(blurBitmap.asImageBitmap()),
            contentDescription = app.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Icon(
            imageVector = Icons.Default.Android,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

/** 应用列表行：图标 + 名称 + 一句话简介 + 评级徽章 + 特殊权限标签 */
@Composable
fun AppRow(
    app: AppMeta,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app = app, size = 52.dp)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = app.name.ifBlank { app.packageName },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TagChip(
                    text = app.grade.label,
                    background = gradeColors[app.grade] ?: Color.Gray,
                    contentColor = Color.White,
                )
            }
            Text(
                text = app.summary.ifBlank { app.repo },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        app.specialPermissions.firstOrNull()?.let { perm ->
            TagChip(
                text = perm.label,
                background = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 空态占位 */
@Composable
fun EmptyPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
