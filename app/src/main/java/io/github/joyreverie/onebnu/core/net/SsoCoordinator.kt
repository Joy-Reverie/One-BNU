package io.github.joyreverie.onebnu.core.net

/**
 * CAS 多跳流程共用的串行锁。
 *
 * OAuth authorize、门户 token 交换和 OneVPN 中转都会临时改写同一认证主机的 JSESSIONID；
 * 并发请求会把一个流程的 session_state 覆盖掉，表现为门户或 VPN 又回到登录页。
 */
internal object SsoCoordinator {
    val lock = Any()
}
