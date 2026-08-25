package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.common.entity.appstore.StoreCard

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
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp)) {}
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
