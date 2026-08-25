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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 实时速度波形图（移植自 GitLink，高采样绘制：每次状态刷新即时重绘）。
 * history：按时间顺序的瞬时速度（B/s），数量越多波形越细。
 * 图中标注三类信息，直观反映下载质量：
 * - 波形 + 渐变填充：当前下载速度的真实变化轨迹（波动越大说明网络越不稳定）；
 * - 峰值点：本次下载至今的最快瞬时速度；
 * - 平均虚线：速度均值，波形低于它说明当前处于慢速段。
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
                .height(64.dp)
                .background(gridColor.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(64.dp)) {
            val n = history.size
            if (n < 1) return@Canvas
            val maxV = (history.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
            val topPad = 4.dp.toPx()
            val bottomPad = 4.dp.toPx()
            val usable = size.height - topPad - bottomPad

            fun yOf(v: Long): Float = topPad + usable - (v.toFloat() / maxV) * usable

            // 水平网格线（3 条参考线）
            (1..3).forEach { i ->
                val gy = size.height * i / 4f
                drawLine(
                    color = gridColor.copy(alpha = 0.25f),
                    start = Offset(0f, gy),
                    end = Offset(size.width, gy),
                    strokeWidth = 1f,
                )
            }

            // 平均速度虚线
            if (n >= 2) {
                val avg = history.average().toFloat()
                val avgY = yOf(avg.toLong())
                drawLine(
                    color = color.copy(alpha = 0.45f),
                    start = Offset(0f, avgY),
                    end = Offset(size.width, avgY),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
            }

            // 峰值点（只标注一次出现的最高瞬时速度）
            val peakIdx = (0 until n).maxByOrNull { history[it] } ?: 0
            val peakX = if (n == 1) 6.dp.toPx() else size.width * peakIdx / (n - 1)
            val peakY = yOf(history[peakIdx])
            drawCircle(color = color.copy(alpha = 0.18f), radius = 9.dp.toPx(), center = Offset(peakX, peakY))
            drawCircle(color = color, radius = 3.2.dp.toPx(), center = Offset(peakX, peakY))

            // 波形 + 渐变填充
            val stepX = if (n == 1) 0f else size.width / (n - 1)
            val path = Path()
            history.forEachIndexed { i, v ->
                val x = if (n == 1) 6.dp.toPx() else i * stepX
                val y = yOf(v)
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
                brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.2f), Color.Transparent)),
            )
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // 末点（当前实时速度）强标记
            val lastX = if (n == 1) 6.dp.toPx() else (n - 1) * stepX
            val lastY = yOf(history.last())
            drawCircle(color = color.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = Offset(lastX, lastY))
            drawCircle(color = color, radius = 3.8.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}
