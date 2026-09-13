package io.github.joyreverie.onebnu.core.store

import org.junit.Assert.assertTrue
import org.junit.Test

class ColorThemeTest {

    /**
     * 首页与「我的」的英雄卡是渐变底 + 白字。珊瑚、琥珀这类偏亮的色系原样铺上去只有 4.2:1，
     * 达不到 WCAG AA，因此浅色模式下会先压暗。这里把这条规则钉住，新增色系时不会破。
     */
    @Test
    fun `七个色系的英雄渐变都撑得住白字`() {
        for (theme in ColorTheme.entries) {
            for (dark in listOf(false, true)) {
                val p = theme.palette(dark)
                for ((name, color) in listOf(
                    "heroStart" to p.heroStart,
                    "heroCenter" to p.heroCenter,
                    "heroEnd" to p.heroEnd,
                )) {
                    val contrast = ColorTheme.contrastWithWhite(color)
                    assertTrue(
                        "${theme.label}（${if (dark) "深色" else "浅色"}）的 $name 白字对比度只有 " +
                            "${"%.2f".format(contrast)}:1",
                        contrast >= ColorTheme.WHITE_TEXT_MIN_CONTRAST,
                    )
                }
            }
        }
    }

    @Test
    fun `已经够暗的颜色不会被继续压暗`() {
        val dark = 0xFF10254A.toInt()
        assertTrue(ColorTheme.darkenForWhiteText(dark) == dark)
    }
}
