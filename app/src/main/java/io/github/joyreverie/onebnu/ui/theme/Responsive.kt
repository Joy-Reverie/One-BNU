package io.github.joyreverie.onebnu.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕适配。
 *
 * 按 Material 的宽度断点分三档：
 *  - Compact  (<600dp)  手机竖屏
 *  - Medium   (600-840) 小平板竖屏 / 手机横屏（多数）
 *  - Expanded (>=840)   平板横屏 / 大屏
 *
 * 同时单独记录「高度是否紧张」——手机横屏宽度可能进 Medium，
 * 但高度只有 ~360dp，纵向留白和行高都必须收紧，否则内容会挤成一团。
 */
class ScreenInfo(
    val widthClass: WindowWidthSizeClass,
    val widthDp: Int,
    val heightDp: Int,
) {
    val isCompact: Boolean get() = widthClass == WindowWidthSizeClass.Compact
    val isMedium: Boolean get() = widthClass == WindowWidthSizeClass.Medium
    val isExpanded: Boolean get() = widthClass == WindowWidthSizeClass.Expanded

    val isLandscape: Boolean get() = widthDp > heightDp

    /** 高度不足以铺开纵向留白（典型：手机横屏）。 */
    val isShort: Boolean get() = heightDp < 480

    /** 宽屏用侧边导航栏取代底部栏，避免横屏时底部栏吃掉本就紧张的高度。 */
    val useNavRail: Boolean get() = isExpanded || (isLandscape && widthDp >= 600)

    /** 正文内容的最大宽度：超宽屏上不让文字铺满整行，否则一行太长很难读。 */
    val contentMaxWidth: Dp
        get() = when {
            isExpanded -> 840.dp
            isMedium -> 640.dp
            else -> Dp.Unspecified
        }

    /** 页面左右外边距。 */
    val gutter: Dp
        get() = when {
            isExpanded -> 24.dp
            isMedium -> 20.dp
            else -> 16.dp
        }

    /** 首页服务宫格列数。 */
    val serviceColumns: Int
        get() = when {
            isExpanded -> 8
            isMedium -> if (isLandscape) 8 else 6
            widthDp < 340 -> 3
            else -> 4
        }

    /** 侧边导航占掉一部分宽度后，下游看到的可用宽度。 */
    fun shrunkBy(dp: Int): ScreenInfo =
        ScreenInfo(widthClass, (widthDp - dp).coerceAtLeast(1), heightDp)
}

val LocalScreenInfo: ProvidableCompositionLocal<ScreenInfo> = compositionLocalOf {
    ScreenInfo(WindowWidthSizeClass.Compact, 360, 800)
}

@Composable
fun ProvideScreenInfo(windowSizeClass: WindowSizeClass, content: @Composable () -> Unit) {
    val config = LocalConfiguration.current
    val info = remember(windowSizeClass, config.screenWidthDp, config.screenHeightDp) {
        ScreenInfo(
            widthClass = windowSizeClass.widthSizeClass,
            widthDp = config.screenWidthDp,
            heightDp = config.screenHeightDp,
        )
    }
    CompositionLocalProvider(LocalScreenInfo provides info, content = content)
}

/** 屏幕方向，用于需要区分横竖屏但不关心宽度档位的地方。 */
@Composable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * 列表页的内边距。
 *
 * 大屏上不把行拉满整幅屏宽 —— 一行文字过长很难扫读。
 * 做法是把多出来的宽度平分成左右留白，列表本身仍然是全宽的，
 * 这样滚动条和惯性滑动区域不会缩到中间一条，手感正常。
 */
@Composable
fun ScreenInfo.listPadding(
    top: Dp = 16.dp,
    bottom: Dp = 16.dp,
): androidx.compose.foundation.layout.PaddingValues {
    val side = if (contentMaxWidth != Dp.Unspecified && widthDp.dp > contentMaxWidth) {
        ((widthDp.dp - contentMaxWidth) / 2).coerceAtLeast(gutter)
    } else {
        gutter
    }
    return androidx.compose.foundation.layout.PaddingValues(
        start = side, end = side, top = top, bottom = bottom,
    )
}

/** 非滚动内容用：限制最大宽度并居中。 */
@Composable
fun ConstrainedWidth(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val screen = LocalScreenInfo.current
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            if (screen.contentMaxWidth != Dp.Unspecified) {
                Modifier.widthIn(max = screen.contentMaxWidth).fillMaxWidth()
            } else {
                Modifier.fillMaxWidth()
            },
            content = content,
        )
    }
}
