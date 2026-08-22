package me.spica27.spicamusic.ui.home.player_bar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.spica27.spicamusic.ui.home.HomePage

/**
 * 全屏输入面板（原全屏播放器位置）。
 *
 * 两种模式：
 * - 终端页：命令输入模式 —— 多行命令 + 常用 dsh 运维命令快捷 chips，发送到 PTY。
 * - 对话/设置页：消息输入模式 —— 多行消息，发送到 dsh 当前会话。
 */
@Composable
fun ExpandedInputPanel(
    currentPage: HomePage,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTerminal = currentPage == HomePage.Terminal
    val title = if (isTerminal) "终端命令" else "发送到 DeepSeek"
    val sendLabel = if (isTerminal) "发送到终端" else "发送到 DeepSeek"

    // 自动收起：输入（或任何内容变化）后 3 秒无操作自动收起；
    // 面板内有点击/输入会通过 inputText 变化重置计时
    LaunchedEffect(inputText) {
        kotlinx.coroutines.delay(3000)
        onCollapse()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // 顶栏：标题 + 收起
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "收起",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 终端模式：常用命令快捷 chips
            if (isTerminal) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickCommandChip("dsh-ctl.sh status")
                    QuickCommandChip("dsh-ctl.sh restart")
                    QuickCommandChip("dsh-ctl.sh logs")
                    QuickCommandChip("clear")
                }
            }

            // 多行输入区
            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                placeholder = {
                    Text(
                        text =
                            if (isTerminal) {
                                "输入终端命令…（支持多行，回车换行）"
                            } else {
                                "输入消息…（支持多行）"
                            },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                shape = RoundedCornerShape(20.dp),
                colors =
                    TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
            )

            // 发送
            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        onSend(inputText)
                        onCollapse()
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(bottom = 4.dp),
                shape = RoundedCornerShape(26.dp),
                enabled = inputText.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text = sendLabel, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun QuickCommandChip(
    command: String,
    onClick: () -> Unit = {},
) {
    Box(
        modifier =
            Modifier
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    RoundedCornerShape(16.dp),
                ).clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = command,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
