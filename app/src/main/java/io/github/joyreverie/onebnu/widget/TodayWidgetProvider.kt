package io.github.joyreverie.onebnu.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * 今日课表桌面小组件。
 *
 * 渲染只读本地缓存（见 ScheduleCache），不联网；缓存由应用加载课表时写入，
 * 或由 [WidgetRefreshJob] 在后台定时 / 手动刷新。系统按 updatePeriodMillis 每半小时唤起一次，
 * 保证过了零点后课表能翻到新的一天，也让「进行中 / 已结束」的标记跟着时间走。
 */
open class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
        WidgetRefreshJob.scheduleIfStale(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle,
    ) {
        manager.updateAppWidget(id, TodayWidgetRenderer.build(context, newOptions))
    }

    override fun onEnabled(context: Context) {
        WidgetRefreshJob.schedule(context, urgent = false)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshJob.cancel(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            WidgetState(context, io.github.joyreverie.onebnu.core.di.ServiceLocator.activeCampus).refreshing = true
            updateAll(context)
            WidgetRefreshJob.schedule(context, urgent = true)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "io.github.joyreverie.onebnu.widget.REFRESH"

        /** 各默认尺寸对应的 receiver，重绘时都要照顾到。 */
        private val PROVIDERS: List<Class<out AppWidgetProvider>> = listOf(
            TodayWidgetProvider::class.java,
            TodayWidgetTallProvider::class.java,
            TodayWidgetFullProvider::class.java,
            TodayWidgetSmallProvider::class.java,
        )

        private fun allIds(context: Context, manager: AppWidgetManager): IntArray =
            PROVIDERS.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }.toIntArray()

        /** 重绘桌面上的每一个实例（所有尺寸）；没有放小组件时什么也不做。 */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            allIds(context, manager).forEach { render(context, manager, it) }
        }

        /**
         * 让系统弹出「添加小组件」确认框，把今日课表直接放到桌面（Android 8 起的标准接口，
         * 不依赖各家启动器的小组件列表）。启动器不支持或调用失败返回 false，调用方退回文字指引。
         */
        fun requestPin(
            context: Context,
            provider: Class<out AppWidgetProvider> = TodayWidgetProvider::class.java,
        ): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            // 用户在系统确认框里点了「添加」后系统回调这里，用来给出「已添加」的反馈；
            // 有些桌面（如 MIUI 未授权「桌面快捷方式」时）接口返回 true 却什么都不弹，调用方据此提示手动添加
            val done = android.app.PendingIntent.getBroadcast(
                context,
                2101,
                Intent(context, WidgetPinReceiver::class.java).setAction(WidgetPinReceiver.ACTION_PINNED),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            return runCatching {
                manager.requestPinAppWidget(ComponentName(context, provider), null, done)
            }.getOrDefault(false)
        }

        fun hasInstances(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context) ?: return false
            return allIds(context, manager).isNotEmpty()
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            manager.updateAppWidget(id, TodayWidgetRenderer.build(context, manager.getAppWidgetOptions(id)))
        }
    }
}
