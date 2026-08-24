package me.spica27.spicamusic.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.spicamusic.BuildConfig
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 应用市场设置页：数据同步状态、检查更新、关于（版本/仓库/开源声明）。
 */
@Composable
fun SettingsPage() {
    val context = LocalContext.current
    val viewModel: StoreViewModel = koinActivityViewModel()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastError by viewModel.lastSyncError.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    // 调试模式（版本号连点 5 次开启）；日志页与调试实验室入口
    val path = me.spica27.navkit.path.LocalNavigationPath.current
    val homeViewModel: me.spica27.spicamusic.ui.home.HomeViewModel =
        org.koin.compose.koinInject()
    var debugModeState by remember { mutableStateOf(false) }

    // 调试实验室：详情页自定义内容输入
    var debugAppId by remember { mutableStateOf("") }

    LaunchedEffect(updateAvailable) {
        updateAvailable?.let {
            Toast
                .makeText(
                    context,
                    "发现新版本 ${it.versionName}：${it.releaseUrl}",
                    Toast.LENGTH_LONG,
                ).show()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        SettingsCard(title = "数据与同步") {
            // 同步状态指示：进行中 / 上次失败原因（规格：无手动刷新按钮，同步静默自动）
            if (syncing) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "正在同步…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (lastError != null) {
                Text(
                    text = "上次同步失败：$lastError",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            SettingRow(
                icon = Icons.Default.Storage,
                title = "应用列表数据",
                subtitle = syncState.appIndexVersion ?: "尚未同步",
            )
            SettingRow(
                icon = Icons.Default.Storage,
                title = "推荐内容数据",
                subtitle = syncState.discoverVersion ?: "尚未同步",
            )
            SettingRow(
                icon = Icons.Default.Refresh,
                title = "立即同步",
                subtitle = "经 GitLink 镜像拉取最新增量包",
                action = { viewModel.refreshSilently(context) },
                actionLabel = if (syncing) "同步中" else "同步",
            )
            downloadProgress?.let { progress ->
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        SettingsCard(title = "客户端自身更新") {
            // 规格：打开应用时静默查询新版本并提示，无手动检查入口
            val update = updateAvailable
            if (update != null) {
                SettingRow(
                    icon = Icons.Default.SystemUpdate,
                    title = "发现新版本 ${update.versionName}",
                    subtitle = "下载走 GitLink 镜像加速",
                    action = { viewModel.downloadUpdate(context) },
                    actionLabel = "下载更新",
                )
            } else {
                SettingRow(
                    icon = Icons.Default.SystemUpdate,
                    title = "已是最新版本",
                    subtitle = "内置自身 GitHub 仓库，独立于商店收录",
                )
            }
        }

        SettingsCard(title = "关于") {
            // 版本号：连续点击 5 次进入调试模式
            var tapCount by remember { mutableIntStateOf(0) }
            var lastTap by remember { mutableLongStateOf(0L) }
            val debugMode by remember { mutableStateOf(false) }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val now = System.currentTimeMillis()
                            if (now - lastTap > 2000) tapCount = 1 else tapCount++
                            lastTap = now
                            if (tapCount >= 5) {
                                tapCount = 0
                                Toast.makeText(context, "调试模式已开启", Toast.LENGTH_SHORT).show()
                                debugModeState = true
                            }
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "版本",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SettingRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = "客户端源码仓库",
                subtitle = "github.com/happy-everyday-everyweek/appstore",
                action = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/happy-everyday-everyweek/appstore"),
                        ),
                    )
                },
                actionLabel = "打开",
            )
            SettingRow(
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                title = "商店数据索引",
                subtitle = "github.com/happy-everyday-everyweek/appstore-index",
                action = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/happy-everyday-everyweek/appstore-index"),
                        ),
                    )
                },
                actionLabel = "打开",
            )
        }

        SettingsCard(title = "开发者") {
            SettingRow(
                icon = Icons.Default.Info,
                title = "应用日志",
                subtitle = "查看 / 复制 / 导出同步与下载日志",
                action = { path.push(LogsScene()) },
                actionLabel = "打开",
            )
        }

        if (debugModeState) {
            SettingsCard(title = "调试实验室") {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "推荐页",
                    subtitle = "切到底栏「推荐」Tab",
                    action = {
                        homeViewModel.navigateToPage(me.spica27.spicamusic.ui.home.HomePage.Discover)
                    },
                    actionLabel = "跳转",
                )
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "全部页",
                    subtitle = "切到底栏「全部」Tab",
                    action = {
                        homeViewModel.navigateToPage(me.spica27.spicamusic.ui.home.HomePage.Library)
                    },
                    actionLabel = "跳转",
                )
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "搜索页",
                    subtitle = "打开搜索场景",
                    action = {
                        path.push(
                            me.spica27.spicamusic.ui.search
                                .SearchScene(),
                        )
                    },
                    actionLabel = "跳转",
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.OutlinedTextField(
                        value = debugAppId,
                        onValueChange = { debugAppId = it.filter(Char::isDigit).take(6) },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("应用 ID（如 1047）") },
                        singleLine = true,
                    )
                    androidx.compose.material3.Button(
                        onClick = {
                            val meta = viewModel.appById(debugAppId)
                            if (meta != null) {
                                path.push(
                                    me.spica27.spicamusic.ui.detail
                                        .DetailScene(meta),
                                )
                            } else {
                                Toast.makeText(context, "未找到应用 $debugAppId", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("详情") }
                }
            }
        }

        Text(
            text = "应用市场是一个以 GitHub 为唯一分发源的开源 Android 应用市场。应用收录、元数据采集、评级与分发全部由 GitHub 工作流自动完成；APK 始终来自开发者自己的 Release，来源可追溯。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Spacer(modifier = Modifier.height(96.dp))
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun androidx.compose.ui.graphics.vector.ImageVector.asIcon(): androidx.compose.ui.graphics.vector.ImageVector = this

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (() -> Unit)? = null,
    actionLabel: String = "",
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(enabled = action != null) { action?.invoke() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
