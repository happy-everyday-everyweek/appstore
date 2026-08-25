package me.spica27.spicamusic.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.store.DebugLog

/** 日志查看页：展示环形日志，支持按等级筛选（信息/警告/错误）、复制 / 导出 / 清空 */
class LogsScene : StackScene() {
    @Composable
    override fun Content() {
        val path = LocalNavigationPath.current
        val context = LocalContext.current
        var revision by remember { mutableStateOf(0) }
        // 等级筛选：null=全部；否则只显示该等级
        var filter by remember { mutableStateOf<String?>(null) }
        val all = remember(revision) { DebugLog.snapshot() }
        val entries =
            remember(revision, filter) {
                if (filter == null) all else all.filter { it.level == filter }
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { path.popTop() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "back",
                    )
                }
                Text(
                    text = "应用日志",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    val text = DebugLog.text()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("appstore-log", text))
                    Toast.makeText(context, "已复制 ${text.lines().size} 行日志", Toast.LENGTH_SHORT).show()
                }) { Text("复制日志") }
                Button(onClick = {
                    val file = DebugLog.exportFile(context)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(intent, "导出日志"))
                }) { Text("导出日志") }
                Button(onClick = {
                    DebugLog.clear()
                    revision++
                }) { Text("清空") }
            }
            // 等级筛选：全部 / 信息 / 警告 / 错误
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LevelChip(label = "全部", selected = filter == null, onClick = { filter = null })
                LevelChip(label = "信息", selected = filter == "I", onClick = { filter = "I" })
                LevelChip(label = "警告", selected = filter == "W", onClick = { filter = "W" })
                LevelChip(label = "错误", selected = filter == "E", onClick = { filter = "E" })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text(
                    text = "${entries.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (entries.isEmpty()) {
                    item {
                        Text(
                            "暂无日志记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    }
                }
                items(entries, key = { it.time + it.tag + it.label + it.message.length }) { entry ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            text = "[${entry.time}] ${entry.label} ${entry.tag}",
                            style = MaterialTheme.typography.labelSmall,
                            color = LevelColor(entry.level),
                        )
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(48.dp)) }
            }
        }
    }
}

/** 等级筛选小按钮 */
@Composable
private fun LevelChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape =
            androidx.compose.foundation.shape
                .RoundedCornerShape(10.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** 等级配色：信息/调试=主色，警告=橙，错误=红，冗余=灰 */
@Composable
private fun LevelColor(code: String): Color {
    val scheme = MaterialTheme.colorScheme
    return when (code) {
        "E" -> scheme.error
        "W" -> Color(0xFFE6A23C)
        "D", "V" -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
}
