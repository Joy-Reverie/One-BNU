package io.github.joyreverie.onebnu.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class ScheduleUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    val terms: List<Term> = emptyList(),
    val term: Term? = null,
    val schedule: Schedule? = null,
    val week: Int = 1,
    /** 今天所在周；不在本学期范围内时为 null。 */
    val currentWeek: Int? = null,
    val maxWeek: Int = 20,
    val periodTimes: List<String> = emptyList(),
    /** 所选学期第一周的周一。 */
    val termStart: LocalDate? = null,
    /** 全部个人日程；网格按所显示周的日期筛。 */
    val events: List<PersonalEvent> = emptyList(),
) {
    /** 当前显示这一周里，周一到周日的日期。 */
    val weekDates: List<LocalDate>
        get() = termStart?.let { s ->
            val monday = s.plusWeeks((week - 1).toLong())
            (0..6).map { monday.plusDays(it.toLong()) }
        } ?: emptyList()

    val isCurrentWeek: Boolean get() = currentWeek != null && week == currentWeek
}

class ScheduleViewModel : ViewModel() {

    private val repo = ServiceLocator.repo
    private val settings = ServiceLocator.settings
    private val session = ServiceLocator.session

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            ServiceLocator.events.events.collect { all -> _state.value = _state.value.copy(events = all) }
        }
        viewModelScope.launch {
            // 设置里改了作息，时间刻度与日程的纵向定位要立即跟着变，不必重新查课表
            settings.periodTimesFlow.collect { _state.value = _state.value.copy(periodTimes = it) }
        }
    }

    fun load(term: Term? = null) {
        _state.value = _state.value.copy(loading = true, error = null, emptyReason = null)
        viewModelScope.launch {
            var terms = _state.value.terms
            if (terms.isEmpty()) {
                // 学期下限取自学籍里的年级。课表若比学籍先加载完，就会拿不到年级、
                // 失去「入学之后」这道过滤，而 terms 之后是缓存的，一次错就一直错。
                runCatching { session.ensureLoaded() }
                when (val t = repo.terms()) {
                    is Outcome.Ok -> terms = filterTerms(t.data)
                    is Outcome.Empty -> {
                        _state.value = _state.value.copy(loading = false, emptyReason = t.reason)
                        return@launch
                    }
                    is Outcome.Error -> {
                        _state.value = _state.value.copy(loading = false, error = t.message)
                        return@launch
                    }
                }
            }

            // 默认选教务当前学期，其次最新的一个
            val ctx = repo.userContext
            val target = term
                ?: terms.firstOrNull { it.xn == ctx?.currentXn && it.xq == ctx.currentXq }
                ?: terms.firstOrNull()

            if (target == null) {
                _state.value = _state.value.copy(loading = false, emptyReason = "没有可查询的学期")
                return@launch
            }

            val start = AcademicCalendar.firstMonday(
                target,
                useOfficial = true,
            )
            val cur = start?.let { currentWeekIn(it) }

            when (val s = repo.schedule(target)) {
                is Outcome.Ok -> {
                    val max = maxOf(s.data.maxWeek, cur ?: 1, MIN_WEEKS)
                    _state.value = _state.value.copy(
                        loading = false,
                        terms = terms,
                        term = target,
                        schedule = s.data,
                        termStart = start,
                        currentWeek = cur?.takeIf { it in 1..max },
                        week = (cur ?: 1).coerceIn(1, max),
                        maxWeek = max,
                        periodTimes = settings.periodTimes,
                        error = null,
                        emptyReason = null,
                    )
                }
                is Outcome.Empty -> _state.value = _state.value.copy(
                    loading = false, terms = terms, term = target, termStart = start,
                    schedule = null, emptyReason = s.reason,
                )
                is Outcome.Error -> _state.value = _state.value.copy(
                    loading = false, terms = terms, term = target, termStart = start,
                    error = s.message,
                )
            }
        }
    }

    fun selectTerm(t: Term) {
        if (t.code == _state.value.term?.code) return
        load(t)
    }

    fun setWeek(w: Int) {
        _state.value = _state.value.copy(week = w.coerceIn(1, _state.value.maxWeek))
    }

    fun backToCurrentWeek() {
        _state.value.currentWeek?.let { setWeek(it) }
    }

    /**
     * 只保留「入学之后、且已经开始」的学期。
     * 教务的下拉会把整个库里的学年都列出来，既有入学前的，也有还没到的。
     */
    private fun filterTerms(all: List<Term>): List<Term> {
        val enroll = AcademicCalendar.enrollmentYear(
            (session.state.value as? SessionRepository.State.Ready)?.profile?.grade,
        )
        return AcademicCalendar.selectable(all, enroll).ifEmpty {
            // 兜底：过滤后一个都不剩时，至少给出已经开始的那些
            AcademicCalendar.selectable(all, null).ifEmpty { all }
        }
    }

    /** 今天落在该学期的第几周；学期还没开始或已过很久则返回 null。 */
    private fun currentWeekIn(start: LocalDate): Int? {
        val days = ChronoUnit.DAYS.between(start, LocalDate.now())
        if (days < 0) return null
        return (days / 7 + 1).toInt()
    }

    companion object {
        /** 课表至少铺满这么多周，方便往后翻。 */
        private const val MIN_WEEKS = 20

        /** 今天是周几（1=周一）。 */
        fun todayDayOfWeek(): Int = LocalDate.now().dayOfWeek.value
    }
}
