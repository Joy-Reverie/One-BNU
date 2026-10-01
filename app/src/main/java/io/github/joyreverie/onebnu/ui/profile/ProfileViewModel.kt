package io.github.joyreverie.onebnu.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.store.AvatarStore
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class ProfileViewModel : ViewModel() {

    private val session = ServiceLocator.session
    private val avatarStore = AvatarStore(ServiceLocator.app)

    val state: StateFlow<SessionRepository.State> = session.state

    private val _avatar = MutableStateFlow<Bitmap?>(null)
    val avatar: StateFlow<Bitmap?> = _avatar.asStateFlow()

    private val _cropSource = MutableStateFlow<Bitmap?>(null)
    val cropSource: StateFlow<Bitmap?> = _cropSource.asStateFlow()

    private val _cropLoading = MutableStateFlow(false)
    val cropLoading: StateFlow<Boolean> = _cropLoading.asStateFlow()

    private var avatarProfileId: String? = null
    private var avatarLoadJob: Job? = null

    init {
        // 进入页面时若尚未加载（例如登录后直接点「我的」），补一次
        viewModelScope.launch { session.ensureLoaded() }
    }

    fun retry() {
        viewModelScope.launch { session.ensureLoaded(force = true) }
    }

    fun loadAvatar(profileId: String) {
        if (avatarProfileId == profileId) return
        avatarProfileId = profileId
        _avatar.value = null
        avatarLoadJob?.cancel()
        avatarLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = avatarStore.load(profileId)
            withContext(Dispatchers.Main) {
                if (avatarProfileId == profileId) _avatar.value = loaded
            }
        }
    }

    fun prepareCrop(uri: Uri) {
        _cropLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val decoded = runCatching { avatarStore.decode(uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                _cropLoading.value = false
                _cropSource.value = decoded
            }
        }
    }

    fun dismissCrop() {
        _cropSource.value = null
    }

    fun saveAvatar(profileId: String, bitmap: Bitmap) {
        avatarLoadJob?.cancel()
        avatarProfileId = profileId
        _avatar.value = bitmap
        _cropSource.value = null
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { avatarStore.save(profileId, bitmap) }
        }
    }
}
