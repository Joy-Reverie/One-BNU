package io.github.joyreverie.onebnu.widget

import android.appwidget.AppWidgetProvider

/**
 * 同一个小组件的不同默认尺寸。启动器要求每个尺寸是独立的 receiver 类，
 * 逻辑全部继承自 [TodayWidgetProvider]；渲染时按实际宽度自动选大 / 小版式。
 */
class TodayWidgetTallProvider : TodayWidgetProvider()
class TodayWidgetFullProvider : TodayWidgetProvider()
class TodayWidgetSmallProvider : TodayWidgetProvider()

/** 「我的」页尺寸选择用。 */
enum class WidgetSize(
    val label: String,
    val description: String,
    val provider: Class<out AppWidgetProvider>,
) {
    SMALL("2×2", "只看当前或下一节课", TodayWidgetSmallProvider::class.java),
    MEDIUM("4×2", "今天的课，约 2～4 节", TodayWidgetProvider::class.java),
    TALL("4×3", "今天的课，约 5～7 节", TodayWidgetTallProvider::class.java),
    FULL("4×4", "一天的课全部列出", TodayWidgetFullProvider::class.java),
}
