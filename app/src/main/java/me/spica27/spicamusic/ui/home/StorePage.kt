package me.spica27.spicamusic.ui.home

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import me.spica27.spicamusic.R

/** 应用市场一级页面（底栏入口） */
enum class StorePage(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    Discover(R.string.nav_tab_discover, Icons.Default.Explore),
    Library(R.string.nav_tab_library, Icons.Default.Apps),
    Settings(R.string.nav_tab_settings, Icons.Default.Settings),
}
