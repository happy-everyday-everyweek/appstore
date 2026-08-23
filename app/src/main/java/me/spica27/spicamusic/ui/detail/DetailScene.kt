package me.spica27.spicamusic.ui.detail

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.common.entity.appstore.AppMeta
import me.spica27.spicamusic.ui.components.AppIcon
import me.spica27.spicamusic.ui.components.OpenSourceTag
import me.spica27.spicamusic.ui.components.TagChip
import me.spica27.spicamusic.ui.components.gradeColors

/**
 * 应用详情页
 * 布局：图标模糊背景；顶部图标/名称/简介；标签行（评级/开闭源/权限）；
 * README 内容；权限列表；悬浮下载按钮。
 */
class DetailScene(
    private val app: AppMeta,
) : StackScene() {
    @Composable
    override fun Content() {
        DetailScreen(app = app)
    }
}

@Composable
fun DetailScreen(app: AppMeta) {
    val path = LocalNavigationPath.current
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景：图标模糊铺底
        if (app.iconUrl.isNotBlank()) {
            me.spica27.spicamusic.ui.components
                .StoreAsyncIconBackground(url = app.iconUrl)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f)),
        )

        // 内容
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
        ) {
            // 返回按钮
            IconButton(
                onClick = { path.popTop() },
                modifier =
                    Modifier
                        .padding(8.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.3f)),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                )
            }

            // 图标
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(
                    app = app,
                    size = 96.dp,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)),
                )
            }

            // 名称
            Text(
                text = app.name.ifBlank { app.packageName },
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )

            // 简介
            if (app.summary.isNotBlank()) {
                Text(
                    text = app.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }

            // 标签行：评级 / 开源闭源 / 最低权限
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagChip(
                    text = app.grade.label,
                    background = gradeColors[app.grade] ?: Color.Gray,
                    contentColor = Color.White,
                )
                Spacer(modifier = Modifier.size(8.dp))
                OpenSourceTag(isOpenSource = app.isOpenSource)
                app.specialPermissions.forEach { perm ->
                    Spacer(modifier = Modifier.size(8.dp))
                    TagChip(
                        text = perm.label,
                        background = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 介绍（README）
            SectionCard(title = stringResource(R.string.detail_readme)) {
                val readme = app.readmeText
                if (readme.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.no_readme),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = readme,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 权限列表
            SectionCard(title = stringResource(R.string.detail_permissions)) {
                if (app.permissions.isEmpty()) {
                    Text(
                        text = "无权限声明",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        app.permissions.forEach { permission ->
                            Text(
                                text = "• $permission",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }

        // 悬浮下载按钮
        ExtendedFloatingActionButton(
            onClick = {
                Toast
                    .makeText(context, R.string.download_coming_soon, Toast.LENGTH_SHORT)
                    .show()
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            icon = {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                )
            },
            text = {
                Text(text = stringResource(R.string.detail_download))
            },
        )
    }
}

/** 白底圆角内容卡片区块 */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}
