package me.spica27.spicamusic.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.spica27.navkit.stack.NavigationStack
import me.spica27.spicamusic.ui.home.HomeScene
import me.spica27.spicamusic.ui.theme.SPICaMusicTheme

/**
 * 应用主框架：主题 + 导航栈
 */
@Composable
fun AppScaffold() {
    val preferencesManager =
        org.koin.compose.koinInject<me.spica27.spicamusic.core.preferences.PreferencesManager>()

    val isDarkMode by
        preferencesManager
            .getBoolean(me.spica27.spicamusic.core.preferences.PreferencesManager.Keys.DARK_MODE)
            .collectAsStateWithLifecycle(false)

    val themeColorStyleValue by
        preferencesManager
            .getString(
                me.spica27.spicamusic.core.preferences.PreferencesManager.Keys.THEME_COLOR_STYLE,
                me.spica27.spicamusic.common.entity.ThemeColorStyle.Textured.value,
            ).collectAsStateWithLifecycle(me.spica27.spicamusic.common.entity.ThemeColorStyle.Textured.value)

    SPICaMusicTheme(
        darkTheme = isDarkMode,
        themeColor =
            androidx.compose.ui.graphics
                .Color(0xFF6750A4),
        themeColorStyle =
            me.spica27.spicamusic.common.entity.ThemeColorStyle
                .fromString(themeColorStyleValue),
    ) {
        NavigationStack(
            initialScene = {
                HomeScene()
            },
            content = {
            },
        )
    }
}
