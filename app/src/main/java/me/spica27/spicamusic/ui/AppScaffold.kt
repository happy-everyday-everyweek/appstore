package me.spica27.spicamusic.ui

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

    // 动态取色：Android 12+ 以系统壁纸动态色 primary 作为种子色，应用整体随壁纸取色；
    // 低版本使用默认紫罗兰种子色
    val context = LocalContext.current
    val defaultSeed = Color(0xFF6750A4)
    val themeColor =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            remember(context, isDarkMode) {
                runCatching {
                    val scheme =
                        if (isDarkMode) {
                            dynamicDarkColorScheme(context)
                        } else {
                            dynamicLightColorScheme(context)
                        }
                    scheme.primary
                }.getOrDefault(defaultSeed)
            }
        } else {
            defaultSeed
        }

    SPICaMusicTheme(
        darkTheme = isDarkMode,
        themeColor = themeColor,
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
