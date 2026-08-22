package me.spica27.spicamusic

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import me.jessyan.autosize.internal.CustomAdapt
import me.spica27.spicamusic.ui.AppScaffold
import me.spica27.spicamusic.ui.audioeffects.AudioEffectsViewModel

/**
 * 主 Activity
 */
class MainActivity :
    ComponentActivity(),
    CustomAdapt {
    private val audioEffectsViewModel by viewModels<AudioEffectsViewModel>()

    // Android 13+ 通知权限（前台服务通知需要）
    private val notificationPermissionLauncher =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边缘到边缘显示
        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                ),
        )

        // 请求通知权限（前台服务常驻通知可见）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppScaffold()
        }
    }

    override fun isBaseOnWidth(): Boolean = true

    /**
     * 设计稿基准尺寸（dp）
     * 竖屏：375dp（手机设计稿）
     * 横屏：1024dp（平板/横屏设计稿）
     */
    override fun getSizeInDp(): Float = 375f
}
