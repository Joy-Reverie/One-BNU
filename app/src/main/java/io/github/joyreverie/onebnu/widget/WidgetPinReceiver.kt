package io.github.joyreverie.onebnu.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 系统在用户确认「添加到桌面」后回调：提示一句，并让新实例立刻画出内容。 */
class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PINNED) return
        Toast.makeText(context, "小组件已添加到桌面", Toast.LENGTH_SHORT).show()
        TodayWidgetProvider.updateAll(context)
    }

    companion object {
        const val ACTION_PINNED = "io.github.joyreverie.onebnu.WIDGET_PINNED"
    }
}
