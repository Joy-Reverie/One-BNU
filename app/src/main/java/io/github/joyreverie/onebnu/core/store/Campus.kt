package io.github.joyreverie.onebnu.core.store

/** 登录与本地数据的隔离维度。不同校区使用不同认证、Cookie、教务主机和缓存。 */
enum class Campus(val label: String, val storageKey: String) {
    BEIJING("北京校区", "beijing"),
    ZHUHAI("珠海校区", "zhuhai"),
}
