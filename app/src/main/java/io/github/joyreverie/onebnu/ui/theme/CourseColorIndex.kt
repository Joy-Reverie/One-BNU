package io.github.joyreverie.onebnu.ui.theme

/**
 * 同名课程始终映射到同一个颜色下标（课表、首页、桌面小组件共用，配色才一致）。
 * String.hashCode 在短中文名上分布一般，混一次让相邻课程更容易错开。
 * 放在独立文件里、不依赖 Compose，纯 JVM 单元测试也能用。
 */
fun courseColorIndex(key: String, size: Int): Int {
    var h = 0
    for (c in key) h = h * 31 + c.code
    h = h xor (h ushr 16)
    return ((h % size) + size) % size
}
