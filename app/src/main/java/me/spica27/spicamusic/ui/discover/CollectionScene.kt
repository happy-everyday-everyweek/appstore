package me.spica27.spicamusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.path.LocalNavigationPath
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.common.entity.appstore.StoreCard
import me.spica27.spicamusic.ui.detail.DetailScene
import me.spica27.spicamusic.ui.home.StoreViewModel
import org.koin.compose.viewmodel.koinActivityViewModel

/**
 * 集合页：头顶 Today 缩略大卡（复用同卡背景）+ 应用列表行（复用全部页行样式）。
 * 某应用 id 在聚合包缺失时显示占位行（保持数量一致）。
 */
class CollectionScene(
    private val card: StoreCard,
) : StackScene() {
    @Composable
    override fun Content() {
        val viewModel: StoreViewModel = koinActivityViewModel()
        val apps by viewModel.apps.collectAsStateWithLifecycle()
        val path = LocalNavigationPath.current

        // 全面屏适配：全屏集合页避开状态栏与系统导航条；实色背景避免转场露出黑边
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

            // 头顶缩略大卡（高度收窄，同 Today 背景渲染）
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(120.dp),
            ) {
                TodayCard(card = card, onClick = {}) // 缩略卡自身不可再深入
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 应用列表：按卡片 apps 顺序展示；缺失 id 显示占位
            val metas = card.appIds.mapNotNull { id -> apps[id] }
            if (metas.isEmpty() && card.appIds.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "集合内应用尚未同步（id: ${card.appIds.joinToString(", ")}）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(metas, key = { it.id }) { app ->
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        )
                        me.spica27.spicamusic.ui.components.AppRow(
                            app = app,
                            onClick = { path.push(DetailScene(app)) },
                        )
                    }
                }
            }
        }
    }
}
