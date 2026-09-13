package io.github.joyreverie.onebnu.core.store

/** 应用的主色系。每套色系都提供浅色与深色两组高对比色阶。 */
enum class ColorTheme(
    val label: String,
    /** 设置页色板上的代表色。 */
    val swatch: Int,
    private val lightPrimary: Int,
    private val lightSecondary: Int,
    private val lightTertiary: Int,
    private val darkPrimary: Int,
    private val darkSecondary: Int,
    private val darkTertiary: Int,
) {
    INDIGO("靛蓝", 0xFF2F5CA8.toInt(), 0xFF1B3C6E.toInt(), 0xFF315D9A.toInt(), 0xFFB07D16.toInt(), 0xFF7FA0DA.toInt(), 0xFF78A6E0.toInt(), 0xFFE5BC5C.toInt()),
    TEAL("青碧", 0xFF1F7A7A.toInt(), 0xFF116466.toInt(), 0xFF2D7A78.toInt(), 0xFFA06A20.toInt(), 0xFF83D8D2.toInt(), 0xFF81CFC5.toInt(), 0xFFE9B96E.toInt()),
    EMERALD("松绿", 0xFF2E815C.toInt(), 0xFF1F6B4C.toInt(), 0xFF3D8064.toInt(), 0xFFA16C26.toInt(), 0xFF81D5AB.toInt(), 0xFF93D6B3.toInt(), 0xFFEDBE76.toInt()),
    CORAL("珊瑚", 0xFFC65B55.toInt(), 0xFFA5433A.toInt(), 0xFFB7654D.toInt(), 0xFF8D6A2A.toInt(), 0xFFFFB4A9.toInt(), 0xFFF4B7A1.toInt(), 0xFFE9C27A.toInt()),
    AMBER("琥珀", 0xFFB77B1F.toInt(), 0xFF8A5A12.toInt(), 0xFFA26F24.toInt(), 0xFF7D5A25.toInt(), 0xFFF6C76B.toInt(), 0xFFE7BA73.toInt(), 0xFFE2C48A.toInt()),
    ROSE("蔷薇", 0xFFAF4F70.toInt(), 0xFF8A3D5E.toInt(), 0xFFA75473.toInt(), 0xFF8B6725.toInt(), 0xFFF2AFC4.toInt(), 0xFFE9B2B9.toInt(), 0xFFE9C47B.toInt()),
    SLATE("石墨", 0xFF5D7487.toInt(), 0xFF465B68.toInt(), 0xFF4A747C.toInt(), 0xFF856C3A.toInt(), 0xFFA8C6D2.toInt(), 0xFF9CC9CB.toInt(), 0xFFE4C986.toInt()),
    ;

    /** 供 Compose 与桌面小组件共用的完整调色板。 */
    fun palette(dark: Boolean): ThemePalette {
        val primary = if (dark) darkPrimary else lightPrimary
        val secondary = if (dark) darkSecondary else lightSecondary
        val tertiary = if (dark) darkTertiary else lightTertiary
        val primaryContainer = if (dark) mix(primary, BLACK, 0.62f) else mix(primary, WHITE, 0.86f)
        val secondaryContainer = if (dark) mix(secondary, BLACK, 0.62f) else mix(primary, WHITE, 0.79f)
        val tertiaryContainer = if (dark) mix(tertiary, BLACK, 0.62f) else mix(tertiary, WHITE, 0.84f)
        val onPrimary = if (dark) mix(primary, BLACK, 0.84f) else WHITE
        val onSecondary = if (dark) mix(secondary, BLACK, 0.84f) else WHITE
        val onTertiary = if (dark) mix(tertiary, BLACK, 0.84f) else WHITE
        val onPrimaryContainer = if (dark) mix(primary, WHITE, 0.72f) else mix(primary, BLACK, 0.55f)
        val onSecondaryContainer = if (dark) mix(secondary, WHITE, 0.72f) else mix(primary, BLACK, 0.55f)
        val onTertiaryContainer = if (dark) mix(tertiary, WHITE, 0.72f) else mix(tertiary, BLACK, 0.62f)
        // 英雄渐变上永远是白字。珊瑚、琥珀这类偏亮的色系原样铺上去只有 4.2:1，
        // 达不到无障碍 AA，所以浅色模式下按需压暗到 4.5:1 再用。
        val heroStart = if (dark) mix(primary, BLACK, 0.76f) else darkenForWhiteText(primary)
        val heroCenter = if (dark) mix(primary, BLACK, 0.62f) else darkenForWhiteText(mix(primary, secondary, 0.48f))
        val heroEnd = if (dark) mix(secondary, BLACK, 0.70f) else darkenForWhiteText(secondary)
        val softStart = if (dark) mix(primary, BLACK, 0.70f) else primaryContainer
        val softEnd = if (dark) mix(secondary, BLACK, 0.72f) else mix(secondary, WHITE, 0.80f)

        return ThemePalette(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = if (dark) lightPrimary else darkPrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            heroStart = heroStart,
            heroCenter = heroCenter,
            heroEnd = heroEnd,
            heroSoftStart = softStart,
            heroSoftEnd = softEnd,
            wordmarkOne = if (dark) mix(primary, WHITE, 0.22f) else mix(primary, WHITE, 0.28f),
            wordmarkBnu = if (dark) mix(secondary, WHITE, 0.12f) else mix(primary, BLACK, 0.35f),
            raised = if (dark) mix(primary, 0xFF161A21.toInt(), 0.20f) else mix(primary, WHITE, 0.965f),
            hairline = if (dark) 0x1FFFFFFF else 0x14000000,
            success = if (dark) mix(secondary, 0xFF72D5A5.toInt(), 0.48f) else mix(secondary, 0xFF247B50.toInt(), 0.42f),
            warning = tertiary,
            widgetBackground = if (dark) 0xFF161A21.toInt() else WHITE,
            widgetPill = if (dark) mix(primary, BLACK, 0.52f) else mix(primary, WHITE, 0.74f),
            courseColors = courseColors(primary, dark),
            courseAccents = courseAccents(primary, dark),
        )
    }

    private fun courseColors(primary: Int, dark: Boolean): IntArray {
        val bases = if (dark) {
            intArrayOf(0xFF223049.toInt(), 0xFF17362F.toInt(), 0xFF3E2C1D.toInt(), 0xFF302340.toInt(), 0xFF3D1F2C.toInt(), 0xFF1F3319.toInt(), 0xFF16323B.toInt(), 0xFF3A3117.toInt())
        } else {
            intArrayOf(0xFFE3EBFA.toInt(), 0xFFD9F0E8.toInt(), 0xFFFBE9DA.toInt(), 0xFFEFE2F7.toInt(), 0xFFFBE1E9.toInt(), 0xFFE4F1DC.toInt(), 0xFFDCEEF4.toInt(), 0xFFFAF0D6.toInt())
        }
        return bases.map { mix(it, primary, if (dark) 0.16f else 0.10f) }.toIntArray()
    }

    private fun courseAccents(primary: Int, dark: Boolean): IntArray {
        val bases = if (dark) {
            intArrayOf(0xFF8FAEEA.toInt(), 0xFF5FC9A5.toInt(), 0xFFE0A468.toInt(), 0xFFB68DE0.toInt(), 0xFFE68CAB.toInt(), 0xFF8FC873.toInt(), 0xFF63BFD8.toInt(), 0xFFE0C263.toInt())
        } else {
            intArrayOf(0xFF3A63B8.toInt(), 0xFF1E8A6B.toInt(), 0xFFC2702A.toInt(), 0xFF7A4CA8.toInt(), 0xFFBC4A72.toInt(), 0xFF4E8B36.toInt(), 0xFF2483A0.toInt(), 0xFFB5901F.toInt())
        }
        return bases.map { mix(it, primary, if (dark) 0.20f else 0.14f) }.toIntArray()
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /** 白字要求的最小对比度（WCAG AA 正文）。 */
        const val WHITE_TEXT_MIN_CONTRAST = 4.5f

        private fun mix(a: Int, b: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            fun channel(value: Int, shift: Int): Int = (value ushr shift) and 0xFF
            fun blend(shift: Int): Int = (channel(a, shift) * (1f - t) + channel(b, shift) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
        }

        /** sRGB 相对亮度（WCAG 定义）。 */
        fun relativeLuminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val v = ((color ushr shift) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /** 白字压在 [background] 上的对比度。 */
        fun contrastWithWhite(background: Int): Double = 1.05 / (relativeLuminance(background) + 0.05)

        /** 逐档压暗，直到白字达到 [WHITE_TEXT_MIN_CONTRAST]。已经够暗的原样返回。 */
        fun darkenForWhiteText(color: Int): Int {
            var c = color
            repeat(24) {
                if (contrastWithWhite(c) >= WHITE_TEXT_MIN_CONTRAST) return c
                c = mix(c, BLACK, 0.05f)
            }
            return c
        }
    }
}

data class ThemePalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val inversePrimary: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val heroStart: Int,
    val heroCenter: Int,
    val heroEnd: Int,
    val heroSoftStart: Int,
    val heroSoftEnd: Int,
    val wordmarkOne: Int,
    val wordmarkBnu: Int,
    val raised: Int,
    val hairline: Int,
    val success: Int,
    val warning: Int,
    val widgetBackground: Int,
    val widgetPill: Int,
    val courseColors: IntArray,
    val courseAccents: IntArray,
)
