package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 详情页背景：图标全屏模糊铺底。
 * - assets/ 开头：随包图标（聚合包 assets/icons/），本地解码，离线可用；
 * - http(s)：网络图标直接加载（coil3）；
 * - 其他/加载失败：留空由调用方深色兜底。
 */
@Composable
fun StoreAsyncIconBackground(url: String) {
    if (url.startsWith("assets/")) {
        val bitmap =
            remember(url) {
                me.spica27.spicamusic.store.StoreAssets
                    .file(url.removePrefix("assets/"))
                    ?.let {
                        runCatching { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
                    }
            }
        if (bitmap != null) {
            Image(
                painter =
                    androidx.compose.ui.graphics.painter
                        .BitmapPainter(bitmap.asImageBitmap()),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.2f).blur(60.dp),
                contentScale = ContentScale.Crop,
            )
        }
    } else if (url.startsWith("http://") || url.startsWith("https://")) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.2f).blur(60.dp),
            contentScale = ContentScale.Crop,
        )
    }
}
