package io.github.joyreverie.onebnu.core.store

/** 上课提醒的送达方式。 */
enum class ReminderStyle(val label: String, val description: String) {
    /** 一条普通通知，按系统通知音量响一声就结束。 */
    NOTIFICATION("通知提醒", "发一条通知，按通知音量提示一声"),

    /** 按闹钟音量持续响铃并震动，直到手动停止或两分钟后自动停。 */
    ALARM("闹钟提醒", "按闹钟音量持续响铃，需手动停止"),
}
