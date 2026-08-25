package me.spica27.spicamusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import me.spica27.spicamusic.store.StoreAssets

/**
 * README/文章正文 Markdown 渲染（mikepenz multiplatform-markdown-renderer，Material3 变体）：
 * - 完整支持标题 / 列表 / 引用 / 表格 / 代码块 / 粗体斜体 / 链接 / 图片等 GFM 语法；
 * - 图片加载走 coil3（[Coil3ImageTransformerImpl]）：
 *   网络引用（http/https）直接加载；
 *   随包本地引用（`<id>_files/...`，聚合包 assets/readmes/ 下）在渲染前重写为 file:// 绝对路径；
 *   无法解析的本地路径保留原文（不渲染成死链占位）。
 */
@Composable
fun MarkdownContent(
    md: String,
    modifier: Modifier = Modifier,
) {
    val content = remember(md) { rewriteLocalImages(md) }
    Markdown(
        content = content,
        imageTransformer = Coil3ImageTransformerImpl,
        modifier = modifier,
    )
}

/** Markdown 图片引用重写：`![](<src>)` 与 `<img src="<src>">` 中的本地相对路径 → file:// URI */
private fun rewriteLocalImages(md: String): String {
    if (md.isBlank()) return md
    var out = md
    // 标准图片语法 ![](src)
    out =
        MARKDOWN_IMAGE_REGEX.replace(out) { match ->
            val src = match.groupValues[1].trim()
            val resolved = resolveLocalImage(src)
            if (resolved != null) {
                "![${match.groupValues[2].trim()}](file://$resolved)"
            } else {
                match.value
            }
        }
    // HTML <img src="...">（部分 README 使用；仅本地引用重写，网络原样保留）
    out =
        HTML_IMG_REGEX.replace(out) { match ->
            val src = match.groupValues[1].trim()
            val resolved = resolveLocalImage(src)
            if (resolved != null) {
                "<img src=\"file://$resolved\">"
            } else {
                match.value
            }
        }
    return out
}

private fun resolveLocalImage(src: String): String? {
    val s = src.removePrefix("\"").removeSuffix("\"")
    if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("file://")) return null
    // 避免路径穿越
    if (s.contains("..")) return null
    // 聚合包把 README 资源放在 assets/readmes/<id>_files/ 下，README 内引用前缀为 <id>_files/
    val file = StoreAssets.file("readmes/$s")
    return file?.takeIf { it.exists() }?.absolutePath
}

private val MARKDOWN_IMAGE_REGEX = Regex("""!\[([^\]]*)\]\(([^)\s]+)\)""")
private val HTML_IMG_REGEX = Regex("""<img\s+src=["']([^"']+)["'][^>]*>""", RegexOption.IGNORE_CASE)
