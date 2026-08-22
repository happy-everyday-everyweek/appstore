package me.spica27.spicamusic.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.scene.StackScene
import me.spica27.spicamusic.R
import me.spica27.spicamusic.dsh.DshMessenger
import me.spica27.spicamusic.terminal.TerminalSessionManager
import me.spica27.spicamusic.ui.chat.ChatPage
import me.spica27.spicamusic.ui.home.player_bar.BottomBarScrollConnection
import me.spica27.spicamusic.ui.home.player_bar.BottomMediaBarV2
import me.spica27.spicamusic.ui.home.player_bar.rememberBottomBarScrollConnection
import me.spica27.spicamusic.ui.settings.dsh.SettingsPage
import me.spica27.spicamusic.ui.terminal.TerminalPage
import org.koin.compose.viewmodel.koinActivityViewModel

class HomeScene : StackScene() {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val homeViewModel: HomeViewModel = koinActivityViewModel()

        val currentPage = homeViewModel.currentPage.collectAsStateWithLifecycle().value

        val bottomBarScrollConnection = rememberBottomBarScrollConnection()
        val context = LocalContext.current

        CompositionLocalProvider(
            LocalBottomBarScrollConnection provides bottomBarScrollConnection,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // 底栏切页不做转场动画；SaveableStateHolder 保留页面状态
                val pageStateHolder = rememberSaveableStateHolder()
                Box(modifier = Modifier.fillMaxSize()) {
                    pageStateHolder.SaveableStateProvider(key = currentPage) {
                        when (currentPage) {
                            HomePage.Chat -> ChatPage()
                            HomePage.Terminal -> TerminalPage()
                            HomePage.Settings -> SettingsPage()
                        }
                    }
                }
                BottomMediaBarV2(
                    onSend = { text ->
                        if (text.isBlank()) return@BottomMediaBarV2
                        when (currentPage) {
                            // 终端页：发送到 PTY（终端环境内执行）
                            HomePage.Terminal -> TerminalSessionManager.writeCommand(context, text)
                            // 对话/设置页：发送到 dsh 当前会话
                            HomePage.Chat, HomePage.Settings -> DshMessenger.sendToDsh(text)
                        }
                    },
                    bottomBarScrollConnection = bottomBarScrollConnection,
                )
            }
        }
    }
}

@Immutable
enum class HomePage(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Chat(R.string.nav_tab_chat, Icons.Default.ChatBubble),
    Terminal(R.string.nav_tab_terminal, Icons.Default.Terminal),
    Settings(R.string.nav_tab_settings, Icons.Default.Settings),
}

val LocalBottomBarScrollConnection =
    compositionLocalOf<BottomBarScrollConnection> {
        error("No BottomBarScrollConnection provided. This composable must be called inside a Scene's content lambda.")
    }
