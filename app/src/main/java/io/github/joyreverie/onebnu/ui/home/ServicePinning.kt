package io.github.joyreverie.onebnu.ui.home

/** 首页「校园服务」默认最多展示几个入口：手机上正好三行四列，其余的折叠在箭头后面。 */
internal const val MAX_PINNED_SERVICES = 12

/**
 * 默认展示的入口键，按首页顺序。[stored] 为 null 表示用户没改过，取前 [MAX_PINNED_SERVICES] 个；
 * 改过的只认这个校区有的入口，超了上限也只取前面的。
 */
internal fun effectivePinned(keys: List<String>, stored: Set<String>?): List<String> =
    (if (stored == null) keys else keys.filter { it in stored }).take(MAX_PINNED_SERVICES)

/**
 * 把 [key] 放进或移出默认展示之后要存的集合。已经放满还要再放就返回 null，由调用方提示。
 * 存的是当前实际生效的那份，这个校区没有的旧键顺手清掉。
 */
internal fun withPinned(keys: List<String>, stored: Set<String>?, key: String, pinned: Boolean): Set<String>? {
    val current = effectivePinned(keys, stored)
    return when {
        !pinned -> (current - key).toSet()
        key in current -> current.toSet()
        current.size >= MAX_PINNED_SERVICES -> null
        else -> (current + key).toSet()
    }
}
