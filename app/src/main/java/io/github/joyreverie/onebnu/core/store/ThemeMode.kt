package io.github.joyreverie.onebnu.core.store

/** 界面深浅色：默认跟随系统，也可以手动固定。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}
