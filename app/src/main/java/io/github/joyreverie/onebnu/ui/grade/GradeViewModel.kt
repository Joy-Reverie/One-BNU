package io.github.joyreverie.onebnu.ui.grade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.GpaSummary
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GradeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    val grades: List<Grade> = emptyList(),
    val scale: GpaScale = GpaScale.OFFICIAL,
    val overall: GpaSummary? = null,
    val byTerm: List<Pair<String, GpaSummary>> = emptyList(),
    /** 教务没给绩点、只能本地换算时提示用户口径差异。 */
    val officialPointsMissing: Boolean = false,
)

class GradeViewModel : ViewModel() {

    private val repo = ServiceLocator.repo
    private val settings = ServiceLocator.settings

    private val _state = MutableStateFlow(GradeUiState(scale = settings.gpaScale))
    val state: StateFlow<GradeUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null, emptyReason = null)
        viewModelScope.launch {
            when (val r = repo.grades()) {
                is Outcome.Ok -> applyGrades(r.data)
                is Outcome.Empty -> _state.value = _state.value.copy(
                    loading = false, grades = emptyList(), emptyReason = r.reason,
                )
                is Outcome.Error -> _state.value = _state.value.copy(loading = false, error = r.message)
            }
        }
    }

    fun setScale(scale: GpaScale) {
        settings.gpaScale = scale
        _state.value = _state.value.copy(scale = scale)
        applyGrades(_state.value.grades)
    }

    private fun applyGrades(grades: List<Grade>) {
        var scale = _state.value.scale
        val noOfficial = grades.isNotEmpty() && grades.none { it.officialPoint != null }
        // 教务没返回绩点时，「教务绩点」口径算不出东西，自动切到线性五分制并提示
        if (scale == GpaScale.OFFICIAL && noOfficial) scale = GpaScale.LINEAR_5

        _state.value = _state.value.copy(
            loading = false,
            grades = grades.sortedWith(compareByDescending<Grade> { it.xn }.thenByDescending { it.xq }),
            scale = scale,
            overall = GpaCalculator.summarize(grades, scale),
            byTerm = GpaCalculator.byTerm(grades, scale),
            officialPointsMissing = noOfficial,
            error = null,
            emptyReason = null,
        )
    }
}
