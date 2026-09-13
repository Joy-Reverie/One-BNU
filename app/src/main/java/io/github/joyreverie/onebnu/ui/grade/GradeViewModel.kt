package io.github.joyreverie.onebnu.ui.grade

import io.github.joyreverie.onebnu.data.repo.DataFreshness
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.GpaCalculator
import io.github.joyreverie.onebnu.data.model.GpaScale
import io.github.joyreverie.onebnu.data.model.GpaSummary
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GradeUiState(
    val freshness: DataFreshness? = null,
    val loading: Boolean = true,
    /** 已经显示出缓存内容，后台还在向教务要新数据。 */
    val refreshing: Boolean = false,
    val error: String? = null,
    val emptyReason: String? = null,
    val grades: List<Grade> = emptyList(),
    val scale: GpaScale = GpaScale.OFFICIAL,
    val overall: GpaSummary? = null,
    val byTerm: List<Pair<String, GpaSummary>> = emptyList(),
    /** 用户在「计算范围」中取消勾选的课程键，仅存本机。 */
    val manuallyExcludedCourseKeys: Set<String> = emptySet(),
    /** 教务没给绩点、只能本地换算时提示用户口径差异。 */
    val officialPointsMissing: Boolean = false,
) {
    /** 当前显示的是本地快照（含「查到的就是空」）。 */
    val fromCache: Boolean get() = freshness?.cached == true
}

class GradeViewModel : ViewModel() {

    private val repo = ServiceLocator.repo
    private val settings = ServiceLocator.settings
    private var backgroundJob: Job? = null

    private val _state = MutableStateFlow(GradeUiState(scale = settings.gpaScale))
    val state: StateFlow<GradeUiState> = _state.asStateFlow()

    init { load() }

    fun load(forceRefresh: Boolean = false) {
        backgroundJob?.cancel()
        _state.value = _state.value.copy(loading = true, refreshing = false, error = null, emptyReason = null)
        viewModelScope.launch {
            apply(repo.grades(forceRefresh = forceRefresh))
            if (_state.value.fromCache) refreshInBackground()
        }
    }

    /**
     * 缓存已经显示出来之后，在后台再向教务要一次。
     * 成功就换成新数据、缓存提示自动消失；失败保持缓存与提示。
     */
    private fun refreshInBackground() {
        if (backgroundJob?.isActive == true) return
        _state.value = _state.value.copy(refreshing = true)
        backgroundJob = viewModelScope.launch {
            when (val r = repo.grades(forceRefresh = true)) {
                is Outcome.Error -> _state.value = _state.value.copy(refreshing = false)
                else -> apply(r)
            }
        }
    }

    private fun apply(result: Outcome<List<Grade>>) {
        when (result) {
            is Outcome.Ok -> {
                _state.value = _state.value.copy(freshness = result.freshness, refreshing = false)
                applyGrades(result.data)
            }
            is Outcome.Empty -> _state.value = _state.value.copy(
                loading = false, refreshing = false, grades = emptyList(),
                emptyReason = result.reason, freshness = result.freshness,
            )
            is Outcome.Error -> _state.value = _state.value.copy(
                loading = false, refreshing = false, error = result.message,
            )
        }
    }

    fun setScale(scale: GpaScale) {
        settings.gpaScale = scale
        _state.value = _state.value.copy(scale = scale)
        applyGrades(_state.value.grades)
    }

    /**
     * 保存当前口径下的手动计算范围。新成绩默认纳入，自动排除项（尤其是缓考）不能被重新勾选。
     * 不删掉其他口径当前不可计算的键，用户切回原口径时选择仍在。
     */
    fun setIncludedCourses(includedCourseKeys: Set<String>) {
        val scale = _state.value.scale
        val eligible = _state.value.grades
            .filter { GpaCalculator.isEligible(it, scale) }
            .map { it.calculationKey }
            .toSet()
        val excluded = settings.gpaExcludedCourseKeys.toMutableSet()
        excluded.removeAll(eligible)
        excluded.addAll(eligible - includedCourseKeys)
        settings.gpaExcludedCourseKeys = excluded
        applyGrades(_state.value.grades)
    }

    private fun applyGrades(grades: List<Grade>) {
        var scale = _state.value.scale
        val noOfficial = grades.isNotEmpty() && grades.none { it.officialPoint != null }
        // 教务没返回绩点时，「教务绩点」口径算不出东西，自动切到线性五分制并提示
        if (scale == GpaScale.OFFICIAL && noOfficial) scale = GpaScale.LINEAR_5

        val manuallyExcluded = settings.gpaExcludedCourseKeys
        _state.value = _state.value.copy(
            loading = false,
            refreshing = false,
            grades = grades.sortedWith(compareByDescending<Grade> { it.xn }.thenByDescending { it.xq }),
            scale = scale,
            overall = GpaCalculator.summarize(grades, scale, manuallyExcluded),
            byTerm = GpaCalculator.byTerm(grades, scale, manuallyExcluded),
            manuallyExcludedCourseKeys = manuallyExcluded,
            officialPointsMissing = noOfficial,
            error = null,
            emptyReason = null,
        )
    }
}
