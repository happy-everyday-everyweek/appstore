package me.spica27.spicamusic.ui.settings.dsh

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.launch
import me.spica27.spicamusic.BuildConfig
import me.spica27.spicamusic.core.preferences.PreferencesManager
import me.spica27.spicamusic.ui.theme.Shapes
import me.spica27.spicamusic.ui.theme.Spacing
import me.spica27.spicamusic.ui.widget.clickHighlight
import org.koin.compose.koinInject

/**
 * 设置页：只管理 App 级偏好 —— WebView 界面无法操作、终端界面也无法操作的内容。
 * 组件与视觉全部移植自 SPICaMusic 原设置页。
 */
@Composable
fun SettingsPage(modifier: Modifier = Modifier) {
    val preferencesManager = koinInject<PreferencesManager>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val isDarkMode by
        preferencesManager
            .getBoolean(PreferencesManager.Keys.DARK_MODE)
            .collectAsStateWithLifecycle(false)

    val terminalFontSize by
        preferencesManager
            .getInt(PreferencesManager.Keys.TERMINAL_FONT_SIZE, 14)
            .collectAsStateWithLifecycle(14)

    // 字号选项（展示值 + 存储值）
    val fontSizeOptions =
        remember {
            ImmutableList.of(
                SettingsOption("10", "10"),
                SettingsOption("12", "12"),
                SettingsOption("14", "14"),
                SettingsOption("16", "16"),
                SettingsOption("18", "18"),
                SettingsOption("20", "20"),
                SettingsOption("24", "24"),
            )
        }
    var expandedKey by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Large, vertical = Spacing.Large),
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        SettingsSectionCard(title = "外观", subtitle = null) {
            SwitchRow(
                title = "深色模式",
                summary = "跟随应用主题，对 WebView 页面同样生效",
                icon = Icons.Default.DarkMode,
                checked = isDarkMode,
                onCheckedChange = { checked ->
                    scope.launch {
                        preferencesManager.setBoolean(PreferencesManager.Keys.DARK_MODE, checked)
                    }
                },
            )
        }

        SettingsSectionCard(title = "终端", subtitle = null) {
            InlineSelectRow(
                rowKey = "font_size",
                title = "终端字号",
                summary = "PTY 终端的字体大小",
                icon = Icons.Default.FormatSize,
                options = fontSizeOptions,
                currentValue = terminalFontSize.toString(),
                expandedKey = expandedKey,
                onExpandChange = { expandedKey = it },
                onValueChange = { value ->
                    scope.launch {
                        preferencesManager.setInt(
                            PreferencesManager.Keys.TERMINAL_FONT_SIZE,
                            value.toIntOrNull() ?: 14,
                        )
                    }
                },
            )
        }

        SettingsSectionCard(title = "WebView", subtitle = null) {
            NavigationRow(
                title = "清除网页缓存",
                summary = "清理对话页 WebView 的本地缓存",
                icon = Icons.Default.DeleteSweep,
                onClick = {
                    WebView(context).clearCache(true)
                    Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                },
            )
        }

        SettingsSectionCard(title = "存储", subtitle = null) {
            NavigationRow(
                title = "文件访问权限",
                summary = "dsh 工作区位于 /sdcard/dsh-workspace，需授予所有文件访问权限",
                icon = Icons.Default.FolderOpen,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                },
            )
        }

        SettingsSectionCard(title = "关于", subtitle = null) {
            SettingsRowFrame(
                icon = Icons.Default.Info,
                highlighted = false,
                onClick = {},
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "DeepSeek Harness 移动端",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------- 以下组件移植自 SPICaMusic 原设置页 ----------------

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(Shapes.ExtraLargeCornerBasedShape)
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f))
                .padding(vertical = Spacing.Large),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.Small))
        content()
    }
}

@Composable
private fun InlineSelectRow(
    rowKey: String,
    title: String,
    summary: String,
    icon: ImageVector,
    options: ImmutableList<SettingsOption>,
    currentValue: String,
    expandedKey: String?,
    onExpandChange: (String?) -> Unit,
    onValueChange: (String) -> Unit,
) {
    val expanded = expandedKey == rowKey
    val currentLabel =
        remember(options, currentValue) {
            options.firstOrNull { it.value == currentValue }?.label
        }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "settings_row_chevron",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsRowFrame(
            icon = icon,
            highlighted = expanded,
            onClick = { onExpandChange(if (expanded) null else rowKey) },
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = currentLabel ?: summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (currentLabel != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (currentLabel != null) FontWeight.Medium else FontWeight.Normal,
                )
            }
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(durationMillis = 220)) + fadeIn(tween(durationMillis = 180)),
            exit = shrinkVertically(animationSpec = tween(durationMillis = 180)) + fadeOut(tween(durationMillis = 140)),
        ) {
            Column(
                modifier =
                    Modifier
                        .padding(
                            start = SettingsRowContentInset,
                            end = Spacing.Large,
                            top = Spacing.ExtraSmall,
                            bottom = Spacing.Small,
                        ),
                verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
            ) {
                options.forEach { option ->
                    OptionCard(
                        option = option,
                        selected = option.value == currentValue,
                        onClick = { onValueChange(option.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val iconScale by animateFloatAsState(
        targetValue = if (checked) 1.08f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "settings_switch_icon_scale",
    )
    SettingsRowFrame(
        icon = icon,
        highlighted = checked,
        onClick = { onCheckedChange(!checked) },
        iconModifier = Modifier.graphicsLayer(scaleX = iconScale, scaleY = iconScale),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
        )
    }
}

@Composable
private fun NavigationRow(
    title: String,
    summary: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    SettingsRowFrame(
        icon = icon,
        highlighted = false,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRowFrame(
    icon: ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val iconBackground by animateColorAsState(
        targetValue =
            if (highlighted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        animationSpec = tween(durationMillis = 220),
        label = "settings_row_icon_bg",
    )
    val iconTint by animateColorAsState(
        targetValue =
            if (highlighted) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = tween(durationMillis = 220),
        label = "settings_row_icon_tint",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clickHighlight(onClick = onClick)
                .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
    ) {
        Box(
            modifier =
                Modifier
                    .size(SettingsRowIconSize)
                    .clip(Shapes.LargeCornerBasedShape)
                    .background(iconBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = iconModifier,
            )
        }
        content()
    }
}

@Composable
private fun OptionCard(
    option: SettingsOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
            },
        animationSpec = tween(durationMillis = 200),
        label = "option_card_background",
    )
    val titleColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        animationSpec = tween(durationMillis = 200),
        label = "option_card_title",
    )
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(Shapes.MediumCornerBasedShape)
                .background(background)
                .clickHighlight(onClick = onClick)
                .padding(horizontal = Spacing.Large, vertical = Spacing.Medium),
    ) {
        Text(
            text = option.label + (if (selected) "（当前）" else ""),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = titleColor,
        )
    }
}

private data class SettingsOption(
    val label: String,
    val value: String,
)

private val SettingsRowIconSize = 46.dp
private val SettingsRowContentInset = Spacing.Large + SettingsRowIconSize + Spacing.Medium
