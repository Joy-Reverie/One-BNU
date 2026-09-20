package io.github.joyreverie.onebnu.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import io.github.joyreverie.onebnu.ui.theme.LocalDarkTheme

data class SegmentOption(val label: String, val icon: ImageVector? = null)

/**
 * 两三个视图之间来回切的分段开关。
 *
 * 用滑块而不是两个按钮：滑块一直停在当前那一段上，不看文字颜色也知道现在在哪个视图，
 * 切换时滑过去也说明了「这是同一份内容的两种看法」，不是两个入口。
 */
@Composable
fun SegmentedSwitch(
    options: List<SegmentOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val accents = LocalAccents.current
    val thumbShape = RoundedCornerShape(11.dp)
    // 浅色靠阴影把滑块从槽里托起来；深色里阴影看不见，改成「槽暗、滑块亮」。
    val dark = LocalDarkTheme.current
    val trackColor = if (dark) MaterialTheme.colorScheme.surface else accents.raised
    val thumbColor = if (dark) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.height(44.dp),
        color = trackColor,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, accents.hairline),
    ) {
        BoxWithConstraints(Modifier.padding(4.dp)) {
            val segment = maxWidth / options.size
            val offset by animateDpAsState(
                segment * selectedIndex.coerceIn(0, options.lastIndex),
                tween(220),
                label = "segmentThumb",
            )
            Box(
                Modifier
                    .offset(x = offset)
                    .width(segment)
                    .fillMaxHeight()
                    .shadowSoft(thumbShape, elevation = 1.dp)
                    .background(thumbColor, thumbShape),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    val content by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(220),
                        label = "segmentContent",
                    )
                    Row(
                        Modifier
                            .width(segment)
                            .fillMaxHeight()
                            .clip(thumbShape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onSelect(index) },
                            ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        option.icon?.let {
                            Icon(it, null, Modifier.size(17.dp), tint = content)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = content,
                        )
                    }
                }
            }
        }
    }
}
