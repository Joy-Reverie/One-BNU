package io.github.joyreverie.onebnu.ui.home

import io.github.joyreverie.onebnu.ui.theme.ScreenInfo

/**
 * 首页「校园服务」收起时最多展示几个入口，总是整行：[ScreenInfo.serviceColumns] × [ScreenInfo.serviceRows]。
 * 手机竖屏三行四列 12 个，平板横屏两行八列 16 个；其余的折叠在箭头后面。
 */
internal val ScreenInfo.pinnedServiceLimit: Int get() = serviceColumns * serviceRows

/**
 * 默认展示的入口键，按首页顺序，最多 [limit] 个。[stored] 为 null 表示用户没改过，排满收起时的那几行；
 * 改过的只认这个校区有的入口，超了上限也只取前面的：大屏上挑的换到分屏、竖屏里照样截断，存的不动。
 */
internal fun effectivePinned(keys: List<String>, stored: Set<String>?, limit: Int): List<String> =
    (if (stored == null) keys else keys.filter { it in stored }).take(limit)

/**
 * 把 [key] 放进或移出默认展示之后要存的集合。已经放满 [limit] 个还要再放就返回 null，由调用方提示。
 * 存的是当前实际生效的那份，这个校区没有的旧键顺手清掉。
 */
internal fun withPinned(keys: List<String>, stored: Set<String>?, key: String, pinned: Boolean, limit: Int): Set<String>? {
    val current = effectivePinned(keys, stored, limit)
    return when {
        !pinned -> (current - key).toSet()
        key in current -> current.toSet()
        current.size >= limit -> null
        else -> (current + key).toSet()
    }
}
