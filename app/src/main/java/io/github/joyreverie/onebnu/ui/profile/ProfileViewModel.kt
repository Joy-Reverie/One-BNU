package io.github.joyreverie.onebnu.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileViewModel : ViewModel() {

    private val session = ServiceLocator.session

    val state: StateFlow<SessionRepository.State> = session.state

    init {
        // 进入页面时若尚未加载（例如登录后直接点「我的」），补一次
        viewModelScope.launch { session.ensureLoaded() }
    }

    fun retry() {
        viewModelScope.launch { session.ensureLoaded(force = true) }
    }
}
