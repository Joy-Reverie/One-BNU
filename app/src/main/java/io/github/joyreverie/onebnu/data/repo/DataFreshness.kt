package io.github.joyreverie.onebnu.data.repo

/** 当前展示结果的实际获取时间；缓存回退不能把旧数据标成刚刚同步。 */
data class DataFreshness(val savedAt: Long, val cached: Boolean)
