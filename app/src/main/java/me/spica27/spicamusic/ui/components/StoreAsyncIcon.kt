package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.skydoves.landscapist.image.LandscapistImage

/** 远程图标加载（Landscapist 封装，加载/失败时显示占位图标） */
@Composable
fun StoreAsyncIcon(
    url: String,
    size: Dp,
    placeholderIcon: ImageVector,
) {
    LandscapistImage(
        imageModel = { url },
        modifier = Modifier.size(size),
        success = { _, painter ->
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        },
        loading = {
            Placeholder(size, placeholderIcon)
        },
    )
}

@Composable
private fun Placeholder(
    size: Dp,
    icon: ImageVector,
) {
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size * 0.6f),
        )
    }
}
