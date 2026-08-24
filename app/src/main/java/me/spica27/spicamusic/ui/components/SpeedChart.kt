package me.spica27.spicamusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 实时速度波形图（移植自 GitLink，高采样绘制：每次状态刷新即时重绘）。
 * history：按时间顺序的瞬时速度（B/s），数量越多波形越细。
 */
@Composable
fun SpeedChart(
    history: List<Long>,
    color: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(gridColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
            val n = history.size
            if (n < 1) return@Canvas
            val maxV = (history.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
            if (n == 1) {
                val x = size.width * 0.02f
                val y = size.height - (history[0] / maxV) * (size.height - 6.dp.toPx()) - 3.dp.toPx()
                drawCircle(color = color, radius = 5.dp.toPx(), center = Offset(x, y))
                drawCircle(color = color.copy(alpha = 0.3f), radius = 10.dp.toPx(), center = Offset(x, y))
                return@Canvas
            }
            val stepX = size.width / (n - 1)
            val path = Path()
            history.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v / maxV) * (size.height - 6.dp.toPx()) - 3.dp.toPx()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val fill =
                Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(
                path = fill,
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)),
            )
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
            val lastX = (n - 1) * stepX
            val lastY = size.height - (history.last() / maxV) * (size.height - 6.dp.toPx()) - 3.dp.toPx()
            drawCircle(color = color, radius = 5.dp.toPx(), center = Offset(lastX, lastY))
            drawCircle(color = color.copy(alpha = 0.3f), radius = 10.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}
