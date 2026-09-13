package io.github.joyreverie.onebnu.ui.exam

import io.github.joyreverie.onebnu.data.repo.DataFreshness
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.Exam
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ExamUiState(
    val freshness: DataFreshness? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    val rounds: List<Option> = emptyList(),
    val round: Option? = null,
    val exams: List<Exam> = emptyList(),
)

class ExamViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    private val _state = MutableStateFlow(ExamUiState())
    val state: StateFlow<ExamUiState> = _state.asStateFlow()

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        _state.value = _state.value.copy(loading = true, error = null, emptyReason = null)
        viewModelScope.launch {
            var rounds = _state.value.rounds
            if (rounds.isEmpty()) {
                when (val r = repo.examRounds(forceRefresh = forceRefresh)) {
                    is Outcome.Ok -> rounds = r.data
                    is Outcome.Empty -> {
                        _state.value = _state.value.copy(loading = false, emptyReason = r.reason)
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
        }
    }

    fun selectRound(r: Option) {
        if (r.code == _state.value.round?.code) return
        _state.value = _state.value.copy(loading = true, round = r, error = null, emptyReason = null)
        viewModelScope.launch { fetch(_state.value.rounds, r, forceRefresh = false) }
    }

    private suspend fun fetch(rounds: List<Option>, round: Option, forceRefresh: Boolean) {
        when (val e = repo.exams(round.code, forceRefresh = forceRefresh)) {
            is Outcome.Ok -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round,
                freshness = e.freshness,
                exams = e.data.sortedBy { it.date ?: "9999" },
            )
            is Outcome.Empty -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round, emptyReason = e.reason,
            )
            is Outcome.Error -> _state.value = ExamUiState(
                loading = false, rounds = rounds, round = round, error = e.message,
            )
        }
    }
}
