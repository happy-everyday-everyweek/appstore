package me.spica27.spicamusic.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.spica27.spicamusic.common.entity.appstore.AppGrade

/** 评级筛选行：全部 / A / B / C / D / E */
@Composable
fun GradeFilterRow(
    selected: AppGrade?,
    onSelect: (AppGrade?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                // 左边缘与顶部搜索框对齐（16dp），右侧留 12dp 呼吸
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部") },
            modifier = Modifier.padding(end = 8.dp),
        )
        AppGrade.entries.forEach { grade ->
            FilterChip(
                selected = selected == grade,
                onClick = { onSelect(grade) },
                label = { Text(grade.label) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
