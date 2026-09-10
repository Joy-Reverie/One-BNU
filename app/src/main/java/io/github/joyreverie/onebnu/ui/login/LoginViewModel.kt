package io.github.joyreverie.onebnu.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.net.CasClient
import io.github.joyreverie.onebnu.core.net.NetworkDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 二次认证（短信）过程中的界面状态。 */
data class SecondAuthState(
    /** 服务端返回的打码手机号，如 `138****1234`。 */
    val maskedPhone: String,
    val code: String = "",
    val sending: Boolean = false,
    val submitting: Boolean = false,
    /** 重发倒计时秒数，0 表示可以发送。 */
    val secondsLeft: Int = 0,
    val notice: String? = null,
    val error: String? = null,
) {
    val canSend: Boolean get() = !sending && !submitting && secondsLeft == 0
    val canSubmit: Boolean get() = !submitting && !sending && code.length >= MIN_CODE_LENGTH

    companion object {
        const val MIN_CODE_LENGTH = 4
    }
}

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val remember: Boolean = false,
    val captcha: String = "",
    val captchaUrl: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** 非空表示正处于短信二次认证流程中。 */
    val secondAuth: SecondAuthState? = null,
    val canSaveCredentials: Boolean = true,
)

class LoginViewModel : ViewModel() {

    private val secure = ServiceLocator.secure
    private val cas = ServiceLocator.cas

    /** 二次认证要用原来那次尝试的 lt/rsa 续做，不能重新取登录页。 */
    private var pending: CasClient.Pending? = null
    private var countdown: Job? = null

    private val _state = MutableStateFlow(
        LoginUiState(
            username = secure.username,
            password = if (secure.hasCredentials) secure.password else "",
            remember = secure.remember,
            canSaveCredentials = secure.available,
        ),
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUsername(v: String) = _state.update { it.copy(username = v.trim(), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onCaptcha(v: String) = _state.update { it.copy(captcha = v.trim(), error = null) }
    fun onRemember(v: Boolean) = _state.update { it.copy(remember = v) }

    fun onSmsCode(v: String) = _state.updateSecondAuth {
        it.copy(code = v.filter(Char::isDigit).take(8), error = null)
    }

    /** 是否已保存凭据，可直接尝试自动登录。 */
    fun canAutoLogin(): Boolean = secure.hasCredentials

    fun login(onSuccess: () -> Unit) {
        val s = _state.value
        if (s.username.isBlank()) {
            _state.update { it.copy(error = "请输入学工号") }
            return
        }
        if (s.password.isBlank()) {
            _state.update { it.copy(error = "请输入数字京师密码") }
            return
        }
        _state.update { it.copy(loading = true, error = null, secondAuth = null) }

        viewModelScope.launch {
            val r = runIo { cas.login(s.username, s.password, s.captcha) } ?: return@launch
            handle(r, onSuccess)
        }
    }

    /** 二次认证：请求下发短信验证码。 */
    fun sendSmsCode() {
        val p = pending ?: return
        if (_state.value.secondAuth?.canSend != true) return
        _state.updateSecondAuth { it.copy(sending = true, error = null, notice = null) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { cas.sendSecondAuthSms(p) } }
            val r = result.getOrElse { e ->
                _state.updateSecondAuth { it.copy(sending = false, error = networkMessage(e)) }
                return@launch
            }
            when (r) {
                is CasClient.SmsResult.Sent -> {
                    _state.updateSecondAuth {
                        it.copy(sending = false, notice = "验证码已发送，5 分钟内有效")
                    }
                    startCountdown()
                }
                is CasClient.SmsResult.Failed ->
                    _state.updateSecondAuth { it.copy(sending = false, error = r.message) }
            }
        }
    }

    /** 二次认证：提交短信验证码并完成登录。 */
    fun submitSmsCode(onSuccess: () -> Unit) {
        val p = pending ?: return
        val code = _state.value.secondAuth?.code.orEmpty()
        if (code.length < SecondAuthState.MIN_CODE_LENGTH) return
        _state.updateSecondAuth { it.copy(submitting = true, error = null, notice = null) }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { cas.completeSecondAuth(p, code) } }
            val r = result.getOrElse { e ->
                _state.updateSecondAuth { it.copy(submitting = false, error = networkMessage(e)) }
                return@launch
            }
            when (r) {
                is CasClient.Result.Success -> onLoggedIn(onSuccess)
                is CasClient.Result.Failed ->
                    _state.updateSecondAuth { it.copy(submitting = false, error = r.message) }
                // 验证码环节不会再要图形码或二次认证；真出现就退回重来，避免卡死
                else -> {
                    cancelSecondAuth()
                    _state.update { it.copy(error = "二次认证状态已失效，请重新登录一次") }
                }
            }
        }
    }

    /** 放弃二次认证，回到账号密码输入。 */
    fun cancelSecondAuth() {
        countdown?.cancel()
        countdown = null
        pending = null
        _state.update { it.copy(secondAuth = null, loading = false) }
    }

    fun refreshCaptcha() = _state.update {
        it.copy(captchaUrl = cas.captchaUrl(), captcha = "")
    }

    // ------------------------------------------------------------------

    private fun handle(r: CasClient.Result, onSuccess: () -> Unit) {
        when (r) {
            is CasClient.Result.Success -> onLoggedIn(onSuccess)

            is CasClient.Result.NeedCaptcha -> _state.update {
                it.copy(
                    loading = false,
                    captchaUrl = r.captchaUrl,
                    captcha = "",
                    error = if (it.captchaUrl == null) "本次登录需要输入验证码" else "验证码有误，请重试",
                )
            }

            is CasClient.Result.NeedSecondAuth -> {
                pending = r.pending
                _state.update {
                    it.copy(
                        loading = false,
                        error = null,
                        secondAuth = SecondAuthState(maskedPhone = r.maskedPhone),
                    )
                }
            }

            is CasClient.Result.Failed -> _state.update {
                it.copy(loading = false, error = r.message)
            }
        }
    }

    private fun onLoggedIn(onSuccess: () -> Unit) {
        val s = _state.value
        secure.save(s.username, s.password, s.remember)
        countdown?.cancel()
        countdown = null
        pending = null
        _state.update {
            it.copy(loading = false, error = null, captcha = "", captchaUrl = null, secondAuth = null)
        }
        // 立刻预热身份信息，避免用户先点「我的」时看到未登录
        viewModelScope.launch { ServiceLocator.session.ensureLoaded(force = true) }
        onSuccess()
    }

    private suspend fun runIo(block: () -> CasClient.Result): CasClient.Result? {
        val result = withContext(Dispatchers.IO) { runCatching(block) }
        return result.getOrElse { e ->
            _state.update { it.copy(loading = false, error = networkMessage(e)) }
            null
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            for (s in RESEND_SECONDS downTo 1) {
                _state.updateSecondAuth { it.copy(secondsLeft = s) }
                delay(1000)
            }
            _state.updateSecondAuth { it.copy(secondsLeft = 0) }
        }
    }

    private fun networkMessage(e: Throwable): String =
        "登录失败：" + with(NetworkDiagnostics) { e.describe() }

    private inline fun MutableStateFlow<LoginUiState>.update(f: (LoginUiState) -> LoginUiState) {
        value = f(value)
    }

    /** 只在确实处于二次认证流程时更新，避免用户中途取消后被后台回调复活。 */
    private inline fun MutableStateFlow<LoginUiState>.updateSecondAuth(
        f: (SecondAuthState) -> SecondAuthState,
    ) {
        val current = value.secondAuth ?: return
        value = value.copy(secondAuth = f(current))
    }

    private companion object {
        /** 与网页端一致的重发间隔。 */
        const val RESEND_SECONDS = 60
    }
}
