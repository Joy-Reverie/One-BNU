package io.github.joyreverie.onebnu.core.net

import android.util.Log
import io.github.joyreverie.onebnu.core.store.Campus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 登录成功后提前为各个校内系统兑换一次 service ticket。
 *
 * CAS 的全局票据不能直接跨域发送给业务系统；教务使用自己的 service ticket，门户使用
 * OAuth code 换取 access token，OneVPN 还需要完成受信中转。这里统一在应用侧完成这些步骤，
 * WebView 只接收目标系统的会话 Cookie，不接触账号密码。
 */
internal object SsoWarmup {

    private const val TAG = "OneBNU/SSO"

    internal enum class Kind { CAS, PORTAL, ONEVPN }

    internal data class Target(val label: String, val service: String, val kind: Kind)

    fun targetsFor(campus: Campus): List<Target> = when (campus) {
        Campus.BEIJING -> listOf(
            Target("数字京师", "https://one.bnu.edu.cn/tp_nup/", Kind.PORTAL),
            Target("教务系统", "http://zyfw.bnu.edu.cn/", Kind.CAS),
            Target("课程中心", OneVpnSso.COURSE_CENTER, Kind.ONEVPN),
        )
        Campus.ZHUHAI -> listOf(
            Target("珠海门户", "https://one.bnuzh.edu.cn/", Kind.PORTAL),
            Target("珠海教务", "https://jwxt.bnuzh.edu.cn/caslogin", Kind.CAS),
        )
    }

    /** 在后台运行；单个系统失败不影响其他系统和主界面进入。 */
    fun warm(http: Http, auth: SessionAuthenticator, campus: Campus) {
        if (!auth.hasSession()) return
        targetsFor(campus).forEach { target ->
            if (target.kind == Kind.PORTAL) {
                runCatching { PortalSso.establish(http, auth, campus, target.service) }
                    .onSuccess { ok ->
                        Log.i(TAG, "${target.label} SSO 预热${if (ok) "完成" else "未完成"}")
                    }
                    .onFailure { error ->
                        Log.w(TAG, "${target.label} SSO 预热失败（${error::class.java.simpleName}）")
                    }
                return@forEach
            }

            if (target.kind == Kind.ONEVPN) {
                runCatching { OneVpnSso.establish(http, auth, campus, target.service) }
                    .onSuccess { ok ->
                        Log.i(TAG, "${target.label} SSO 预热${if (ok) "完成" else "未完成"}")
                    }
                    .onFailure { error ->
                        Log.w(TAG, "${target.label} SSO 预热失败（${error::class.java.simpleName}）")
                    }
                return@forEach
            }

            runCatching { auth.sso(target.service) }
                .onSuccess { result ->
                    val host = result.url.toHttpUrlOrNull()?.host ?: "unknown"
                    Log.i(TAG, "${target.label} SSO 预热 HTTP ${result.code} → $host")
                }
                .onFailure { error ->
                    // 这里只记系统名和异常类型，不记录 URL 查询参数、票据或用户信息。
                    Log.w(TAG, "${target.label} SSO 预热失败（${error::class.java.simpleName}）")
                }
        }
    }
}
