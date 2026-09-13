package io.github.joyreverie.onebnu.ui.exam

import io.github.joyreverie.onebnu.data.repo.DataFreshness
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.Exam
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExamUiState(
    val freshness: DataFreshness? = null,
    val loading: Boolean = true,
    /** 已经显示出缓存内容，后台还在向教务要新数据。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val emptyReason: String? = null,
    val rounds: List<Option> = emptyList(),
    val round: Option? = null,
    val exams: List<Exam> = emptyList(),
) {
    /** 当前显示的是本地快照（含「查到的就是空」）。 */
    val fromCache: Boolean get() = freshness?.cached == true
}

class ExamViewModel : ViewModel() {

    private val repo = ServiceLocator.repo
    private var backgroundJob: Job? = null

    private val _state = MutableStateFlow(ExamUiState())
    val state: StateFlow<ExamUiState> = _state.asStateFlow()

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        backgroundJob?.cancel()
        _state.value = _state.value.copy(loading = true, refreshing = false, error = null, emptyReason = null)
        viewModelScope.launch {
            var rounds = _state.value.rounds
            if (rounds.isEmpty()) {
                when (val r = repo.examRounds(forceRefresh = forceRefresh)) {
                    is Outcome.Ok -> rounds = r.data
                    is Outcome.Empty -> {
                        _state.value = _state.value.copy(
                            loading = false, emptyReason = r.reason, freshness = r.freshness,
                        )
                        if (r.cached) refreshInBackground()
                        return@launch
                    }
                    is Outcome.Error -> {
                        _state.value = _state.value.copy(loading = false, error = r.message)
                        return@launch
                    }
                }
            }
            val target = _state.value.round ?: rounds.firstOrNull()
            if (target == null) {
                _state.value = _state.value.copy(loading = false, emptyReason = "教务系统暂未发布考试安排")
                return@launch
            }
            fetch(rounds, target, forceRefresh)
            if (_state.value.fromCache) refreshInBackground()
        }
    }

    fun selectRound(r: Option) {
        if (r.code == _state.value.round?.code) return
        backgroundJob?.cancel()
        _state.value = _state.value.copy(loading = true, refreshing = false, round = r, error = null, emptyReason = null)
        viewModelScope.launch {
            fetch(_state.value.rounds, r, forceRefresh = false)
            if (_state.value.fromCache) refreshInBackground()
        }
    }

    /**
     * 缓存已经显示出来之后，在后台再向教务要一次。
     * 成功就换成新数据、缓存提示自动消失；失败保持缓存与提示，不打扰用户。
     */
    private fun refreshInBackground() {
        val round = _state.value.round ?: return
        if (backgroundJob?.isActive == true) return
        _state.value = _state.value.copy(refreshing = true)
        backgroundJob = viewModelScope.launch {
            val rounds = (repo.examRounds(forceRefresh = true) as? Outcome.Ok)?.data
                ?.takeIf { it.isNotEmpty() } ?: _state.value.rounds
            when (val e = repo.exams(round.code, forceRefresh = true)) {
                is Outcome.Ok -> _state.value = ExamUiState(
                    loading = false, rounds = rounds, round = round,
                    freshness = e.freshness,
                    exams = e.data.sortedBy { it.date ?: "9999" },
                )
                is Outcome.Empty -> _state.value = _state.value.copy(
                    refreshing = false, rounds = rounds, exams = emptyList(),
                    emptyReason = e.reason, freshness = e.freshness,
                )
                // 后台失败不覆盖已经显示的缓存，只把「正在更新」收起来
                is Outcome.Error -> _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    private suspend fun fetch(rounds: List<Option>, round: Option, forceRefresh: Boolean) {
        when (val e = repo.exams(round.code, forceRefresh = forceRefresh)) {
            is Outcome.Ok -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round,
                freshness = e.freshness,
                exams = e.data.sortedBy { it.date ?: "9999" },
            )
            is Outcome.Empty -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round,
                emptyReason = e.reason, freshness = e.freshness,
            )
            is Outcome.Error -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round, error = e.message,
            )
        }
    }
}
