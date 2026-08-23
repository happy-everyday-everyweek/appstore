package me.spica27.spicamusic.ui.widget

import androidx.compose.foundation.Indication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/** 点击高亮 Indication（按压反馈颜色指示） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberClickHighlightIndication(color: Color): Indication = remember(color) { ripple(color = color) }
