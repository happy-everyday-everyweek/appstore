package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.skydoves.landscapist.image.LandscapistImage

/** 详情页背景：图标全屏模糊铺底（无图标时留空由调用方兜底） */
@Composable
fun StoreAsyncIconBackground(url: String) {
    LandscapistImage(
        imageModel = { url },
        modifier =
            Modifier
                .fillMaxSize()
                .scale(1.2f)
                .blur(60.dp),
        success = { _, painter ->
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        },
    )
}
