package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.spica27.spicamusic.store.StoreAssets
import me.spica27.spicamusic.ui.discover.MarkdownPlain
import java.io.File

/**
 * README/文章正文 Markdown 渲染：
 * - 文本行沿用 [MarkdownPlain] 轻量净化（标题/列表/粗体等符号剥离）；
 * - 图片行 `![alt](src)`：本地引用（`<id>_files/...`，承载包随包资产）映射到 StoreAssets 读取，
 *   网络引用（http/https）直接加载；
 * - 相对链接（如 `[x](docs/y.md)`）文本保留可读形式，不渲染成不可点的占位。
 */
@Composable
fun MarkdownContent(
    md: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        md.lineSequence().forEach { line ->
            val imgSrc = imageLineSrc(line)
            if (imgSrc != null) {
                val model: Any? =
                    if (imgSrc.startsWith("http://") || imgSrc.startsWith("https://")) {
                        imgSrc
                    } else {
                        StoreAssets.file("readmes/$imgSrc")
                    }
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp)),
                    )
                }
            } else if (line.isNotBlank()) {
                Text(
                    text = MarkdownPlain.render(line),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 若该行是图片行（`![alt](src)`），返回 src；否则返回 null */
private fun imageLineSrc(line: String): String? {
    val t = line.trim()
    if (!t.startsWith("![")) return null
    val close = t.indexOf("](")
    if (close < 0) return null
    val end = t.indexOf(')', close + 2)
    if (end < 0) return null
    return t.substring(close + 2, end).trim().ifBlank { null }
}