package io.github.joyreverie.onebnu.data.repo

import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.store.OfflineCache
import io.github.joyreverie.onebnu.core.store.SecureStore
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.StudentProfile
import io.github.joyreverie.onebnu.data.parse.Parsers
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 当前登录用户。
 *
 * 之前身份信息挂在 [ZyfwApi.userContext] 这个普通字段上，只有某次教务请求
 * 顺带触发 `ensureSession()` 时才会被填上，而且不是可观察状态 ——
 * 结果是「登录后直接进『我的』」会一直显示未登录，且之后再也不会自己恢复。
 *
 * 这里把它提为独立的、可观察的会话状态：登录成功后主动拉一次，
 * 界面订阅 [state]，失败可重试。
 */
class SessionRepository(
    private val api: ZyfwApi,
    private val auth: SessionAuthenticator,
    private val secure: SecureStore,
    private val offlineCache: OfflineCache? = null,
) {

    sealed interface State {
        /** 尚未开始加载。 */
        object Idle : State
        object Loading : State
        data class Ready(val profile: StudentProfile) : State
        /** 教务连不上或会话失效；[needLogin] 为真时要退回登录页。 */
        data class Failed(val message: String, val needLogin: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 已就绪时直接返回；[force] 为真则强制重新拉取。 */
    suspend fun ensureLoaded(force: Boolean = false) {
        mutex.withLock {
            if (!force && _state.value is State.Ready) return
            if (_state.value is State.Loading) return
            _state.value = State.Loading

            val result = withContext(Dispatchers.IO) { load() }
            _state.value = result
        }
    }

    private fun load(): State {
        if (!auth.hasSession() && !relogin()) return cachedProfile()
        return try {
            api.ensureSession(force = true)
            val xml = api.studentInfoHtml()
            val parsed = Parsers.parseStudentProfile(xml)
            if (parsed != null) {
                offlineCache?.saveStudentProfile(OfflineCache.STUDENT_PROFILE, parsed)
                State.Ready(parsed)
            } else {
                // 学籍接口没给出内容时，退回 SetMainInfo.jsp 里的基本身份，
                // 至少不让界面停在「未登录」。
                val ctx = api.userContext
                if (ctx != null && ctx.userName.isNotBlank()) {
                    State.Ready(
                        StudentProfile(
                            name = ctx.userName,
                            studentId = ctx.loginId,
                            gender = "", department = "", major = "",
                            className = "", grade = "", level = "",
                            details = listOf(
                                InfoItem("姓名", ctx.userName),
                                InfoItem("学号", ctx.loginId),
                            ),
                        ),
                    )
                } else {
                    State.Failed("没有读到学籍信息", needLogin = false)
                }
            }
        } catch (e: ZyfwApi.SessionExpiredException) {
            if (relogin()) {
                runCatching {
                    api.ensureSession(force = true)
                    val xml = api.studentInfoHtml()
                    val parsed = Parsers.parseStudentProfile(xml)
                    if (parsed != null) offlineCache?.saveStudentProfile(OfflineCache.STUDENT_PROFILE, parsed)
                    parsed
                }.getOrNull()?.let { return State.Ready(it) }
            }
            cachedProfile("登录状态已失效，请重新登录")
        } catch (e: java.net.UnknownHostException) {
            cachedProfile("无法连接到教务系统，请检查网络")
        } catch (e: java.net.SocketTimeoutException) {
            cachedProfile("教务系统响应超时，请稍后重试")
        } catch (e: Exception) {
            cachedProfile(e.message ?: "读取用户信息失败")
        }
    }

    private fun cachedProfile(fallbackMessage: String = "登录状态已失效，请重新登录"): State {
        val profile = offlineCache?.loadStudentProfile(OfflineCache.STUDENT_PROFILE)
        return profile?.let { State.Ready(it) }
            ?: State.Failed(fallbackMessage, needLogin = !auth.hasSession())
    }

    private fun relogin(): Boolean {
        if (!secure.hasCredentials) return false
        return runCatching { auth.relogin(secure.username, secure.password) }.getOrDefault(false)
    }

    fun clear() {
        _state.value = State.Idle
    }
}
