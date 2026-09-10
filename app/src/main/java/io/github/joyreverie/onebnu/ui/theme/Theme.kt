package io.github.joyreverie.onebnu.ui.theme

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.ThemeMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset

/**
 * 视觉体系。
 *
 * 主色取自北师大校色的深靛蓝，辅以青与木铎金构成三段渐变；
 * 中性色不用纯灰而是带一点蓝调，和主色同源，整体不会显得脏。
 */

// ---- 品牌色 ----
private val Indigo900 = Color(0xFF10254A)
private val Indigo700 = Color(0xFF1B3C6E)
private val Indigo500 = Color(0xFF2F5CA8)
private val Indigo300 = Color(0xFF7FA0DA)
private val Indigo100 = Color(0xFFDDE6F8)
/** 选中态的浅蓝：比 Indigo100 深一档，放在白卡片上一眼看出「选中」，又与主色同源。 */
private val Indigo150 = Color(0xFFCBDCF7)

private val Teal600 = Color(0xFF0E7C8C)
private val Teal300 = Color(0xFF63C6D4)
private val Teal100 = Color(0xFFD3EFF4)

private val Gold600 = Color(0xFFB07D16)
private val Gold300 = Color(0xFFE5BC5C)
private val Gold100 = Color(0xFFFAEED2)

private val Rose600 = Color(0xFFB3261E)
private val Rose300 = Color(0xFFF2B8B5)

private val LightColors = lightColorScheme(
    primary = Indigo700,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    inversePrimary = Indigo300,

    secondary = Teal600,
    onSecondary = Color.White,
    // 选中容器（底部导航选中指示、分段选择器选中项）走浅蓝而不是青绿：
    // 青绿在白底上偏「绿」，与品牌蓝不像一套。
    secondaryContainer = Indigo150,
    onSecondaryContainer = Indigo900,

    tertiary = Gold600,
    onTertiary = Color.White,
    tertiaryContainer = Gold100,
    onTertiaryContainer = Color(0xFF3C2A00),

    // 背景比卡片略深一点，卡片才「浮」得起来
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF14181F),
    surface = Color.White,
    onSurface = Color(0xFF14181F),
    surfaceVariant = Color(0xFFE6EAF3),
    onSurfaceVariant = Color(0xFF454B57),
    surfaceTint = Indigo700,
    inverseSurface = Color(0xFF20242C),
    inverseOnSurface = Color(0xFFF1F3F8),

    outline = Color(0xFF767C89),
    outlineVariant = Color(0xFFCBD1DE),

    error = Rose600,
    onError = Color.White,
    errorContainer = Color(0xFFFCE0DE),
    onErrorContainer = Color(0xFF410E0B),

    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Indigo300,
    onPrimary = Color(0xFF0B1A34),
    primaryContainer = Color(0xFF264673),
    onPrimaryContainer = Indigo100,
    inversePrimary = Indigo700,

    secondary = Teal300,
    onSecondary = Color(0xFF04333B),
    secondaryContainer = Color(0xFF2A4C7D),
    onSecondaryContainer = Indigo100,

    tertiary = Gold300,
    onTertiary = Color(0xFF3C2A00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Gold100,

    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6ED),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE3E6ED),
    surfaceVariant = Color(0xFF2A303B),
    onSurfaceVariant = Color(0xFFC0C6D3),
    surfaceTint = Indigo300,
    inverseSurface = Color(0xFFE3E6ED),
    inverseOnSurface = Color(0xFF20242C),

    outline = Color(0xFF8A909D),
    outlineVariant = Color(0xFF39404C),

    error = Rose300,
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFCE0DE),

    scrim = Color(0xFF000000),
)

/**
 * 主题内的扩展记号：Material 的 ColorScheme 里没有渐变与「二级卡片面」的位置，
 * 单独用一个 CompositionLocal 带下去，避免各处硬编码颜色。
 */
data class BnuAccents(
    val heroGradient: Brush,
    val heroGradientSoft: Brush,
    /**
     * 字标专用渐变。不能复用 [heroGradient] —— 那是给深色卡片当**底**的，
     * 暗色主题下它本身就很深，刷到文字上等于深色字压深色背景，几乎看不见。
     */
    val wordmarkGradient: Brush,
    /**
     * 登录页「One BNU」字标的两段色（与启动器图标一致）。
     * 图标里 "One" 是浅蓝、"BNU" 是深蓝，中间一个空格；用两个明确色阶而不是
     * 渐变，才能复现图标里那种对比。
     */
    val wordmarkOne: Color,
    val wordmarkBnu: Color,
    /** 卡片之上再叠一层的浅色面（如卡片内的统计块）。 */
    val raised: Color,
    /** 分隔用的极淡描边。 */
    val hairline: Color,
    val success: Color,
    val warning: Color,
    val courseColors: List<Color>,
    /** 课程卡左侧强调条的颜色，比底色深，保证在浅底上可见。 */
    val courseAccents: List<Color>,
)

private val LightAccents = BnuAccents(
    heroGradient = Brush.linearGradient(listOf(Indigo700, Indigo500, Teal600)),
    heroGradientSoft = Brush.linearGradient(listOf(Indigo100, Teal100)),
    wordmarkGradient = Brush.linearGradient(listOf(Indigo700, Indigo500, Teal600)),
    // 直接取自启动器图标的字标像素
    wordmarkOne = Color(0xFF4D9DFC),
    wordmarkBnu = Color(0xFF0D32B3),
    raised = Color(0xFFF7F9FD),
    hairline = Color(0x14000000),
    success = Color(0xFF1E7A4B),
    warning = Gold600,
    // 低饱和底色，彼此明度接近，放在一起不会跳
    courseColors = listOf(
        Color(0xFFE3EBFA), Color(0xFFD9F0E8), Color(0xFFFBE9DA), Color(0xFFEFE2F7),
        Color(0xFFFBE1E9), Color(0xFFE4F1DC), Color(0xFFDCEEF4), Color(0xFFFAF0D6),
    ),
    courseAccents = listOf(
        Color(0xFF3A63B8), Color(0xFF1E8A6B), Color(0xFFC2702A), Color(0xFF7A4CA8),
        Color(0xFFBC4A72), Color(0xFF4E8B36), Color(0xFF2483A0), Color(0xFFB5901F),
    ),
)

private val DarkAccents = BnuAccents(
    heroGradient = Brush.linearGradient(listOf(Color(0xFF16305C), Color(0xFF1D4A78), Color(0xFF0F5F6C))),
    heroGradientSoft = Brush.linearGradient(listOf(Color(0xFF1B2A44), Color(0xFF13323A))),
    wordmarkGradient = Brush.linearGradient(listOf(Color(0xFF9CC0FF), Color(0xFF7FD7EA), Color(0xFF74E0C4))),
    wordmarkOne = Color(0xFF8FC1FF),
    wordmarkBnu = Color(0xFF9BB0EC),
    raised = Color(0xFF1D222B),
    hairline = Color(0x1FFFFFFF),
    success = Color(0xFF6BD0A0),
    warning = Gold300,
    courseColors = listOf(
        Color(0xFF223049), Color(0xFF17362F), Color(0xFF3E2C1D), Color(0xFF302340),
        Color(0xFF3D1F2C), Color(0xFF1F3319), Color(0xFF16323B), Color(0xFF3A3117),
    ),
    courseAccents = listOf(
        Color(0xFF8FAEEA), Color(0xFF5FC9A5), Color(0xFFE0A468), Color(0xFFB68DE0),
        Color(0xFFE68CAB), Color(0xFF8FC873), Color(0xFF63BFD8), Color(0xFFE0C263),
    ),
)

val LocalAccents = staticCompositionLocalOf { LightAccents }

/** 统一的圆角与间距刻度，避免各页面各写各的。 */
object Shape {
    val card = 18.dp
    val cardLarge = 24.dp
    val chip = 12.dp
    val tile = 16.dp
    val field = 14.dp
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

/** 当前是否深色主题。比 `isSystemInDarkTheme()` 多考虑了用户在设置里的手动选择。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 深浅色的最终判定：设置里选了浅色 / 深色就按选择，「跟随系统」时读系统。
 * 主题切换只改 Compose 状态，不重建 Activity，页面就地换色。
 */
@Composable
fun resolveDarkTheme(): Boolean {
    val system = isSystemInDarkTheme()
    // Compose 预览等场景下 ServiceLocator 尚未初始化，退回系统设置
    val settings = runCatching { ServiceLocator.settings }.getOrNull() ?: return system
    val mode by settings.themeMode.collectAsState()
    return when (mode) {
        ThemeMode.SYSTEM -> system
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

@Composable
fun OneBnuTheme(
    darkTheme: Boolean = resolveDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val accents = if (darkTheme) DarkAccents else LightAccents
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as Activity
            // 手动选的深浅色可能与系统相反，XML 主题里的窗口底色和系统栏样式都是按系统给的，
            // 这里按实际主题重设，否则键盘弹出、页面切换的瞬间会露出反色的底。
            val transparent = android.graphics.Color.TRANSPARENT
            (activity as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = if (darkTheme) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
                navigationBarStyle = if (darkTheme) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
            )
            activity.window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAccents provides accents,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}

/** 同名课程始终得到同一颜色（底色 + 强调条成对）。 */
@Composable
fun courseColor(key: String): Color {
    val p = LocalAccents.current.courseColors
    return p[courseColorIndex(key, p.size)]
}

@Composable
fun courseAccent(key: String): Color {
    val p = LocalAccents.current.courseAccents
    return p[courseColorIndex(key, p.size)]
}

/**
 * 柔光斑。
 *
 * 不用 Modifier.blur —— 它在 API 31 以下不生效（本应用 minSdk 26），
 * 且默认把模糊裁在元素边界内，会露出硬矩形边。
 * 径向渐变从中心到边缘自然衰减，各版本表现一致。
 */
@Composable
fun GlowBlob(
    color: Color,
    modifier: Modifier = Modifier,
    alpha: Float = 0.30f,
) {
    Box(
        modifier.background(
            Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = alpha),
                    color.copy(alpha = alpha * 0.45f),
                    color.copy(alpha = 0f),
                ),
                radius = Float.POSITIVE_INFINITY,
            ),
            shape = CircleShape,
        ),
    )
}

/**
 * 以绘制方式叠一层柔光，**不参与布局测量**。
 *
 * GlowBlob 作为子元素会把父容器撑到光斑那么大（身份卡就因此空出一大块），
 * 需要「只是装饰、不影响高度」时用这个。
 * [cx]/[cy] 是相对容器的比例位置，[radius] 是相对容器较长边的比例半径。
 */
fun Modifier.glow(
    color: Color,
    alpha: Float = 0.18f,
    cx: Float = 0.85f,
    cy: Float = 0.0f,
    radius: Float = 0.7f,
): Modifier = drawBehind {
    val r = maxOf(size.width, size.height) * radius
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = Offset(size.width * cx, size.height * cy),
            radius = r,
        ),
        radius = r,
        center = Offset(size.width * cx, size.height * cy),
    )
}
