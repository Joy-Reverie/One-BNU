package io.github.joyreverie.onebnu.core.store

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 深浅色与主色系。
 *
 * 外观是**应用级**偏好，不跟校区走：以前它存在按校区分文件的 [Settings] 里，
 * 切一次校区主题就像被「重置」了，而且切完之前的界面要重启才会换色（两套 Settings
 * 各持一份 StateFlow）。这里只有一份，两个校区共用。
 */
class AppearanceStore(context: Context, private val onChanged: () -> Unit = {}) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateFromCampusSettings(context)
    }

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM),
    )

    private val _colorTheme = MutableStateFlow(
        runCatching { ColorTheme.valueOf(prefs.getString(KEY_COLOR_THEME, null) ?: "") }
            .getOrDefault(ColorTheme.INDIGO),
    )

    /** 深浅色模式。以流的形式暴露，设置页改动后整个界面立即重绘，不重建 Activity。 */
    val themeMode: StateFlow<ThemeMode> get() = _themeMode

    /** 应用主色系；与深浅色独立，切换后立即重绘页面并刷新桌面小组件。 */
    val colorThemeFlow: StateFlow<ColorTheme> get() = _colorTheme

    val colorTheme: ColorTheme get() = _colorTheme.value

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
        onChanged()
    }

    fun setColorTheme(theme: ColorTheme) {
        prefs.edit().putString(KEY_COLOR_THEME, theme.name).apply()
        _colorTheme.value = theme
        onChanged()
    }

    /** 1.9.33 及更早把外观存在各校区的设置文件里，第一次启动时搬过来，用户不会觉得主题被重置。 */
    private fun migrateFromCampusSettings(context: Context) {
        if (prefs.contains(KEY_THEME) || prefs.contains(KEY_COLOR_THEME)) return
        val legacy = Campus.values()
            .map { context.getSharedPreferences(Settings.prefsName(it), Context.MODE_PRIVATE) }
            .firstOrNull { it.contains(KEY_THEME) || it.contains(KEY_COLOR_THEME) }
            ?: return
        prefs.edit()
            .apply {
                legacy.getString(KEY_THEME, null)?.let { putString(KEY_THEME, it) }
                legacy.getString(KEY_COLOR_THEME, null)?.let { putString(KEY_COLOR_THEME, it) }
            }
            .apply()
    }

    private companion object {
        const val PREFS = "onebnu_appearance"
        const val KEY_THEME = "theme_mode"
        const val KEY_COLOR_THEME = "color_theme"
    }
}
