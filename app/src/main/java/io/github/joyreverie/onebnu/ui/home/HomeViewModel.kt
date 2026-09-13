package io.github.joyreverie.onebnu.ui.home

import io.github.joyreverie.onebnu.data.repo.DataFreshness
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class TodayCourse(val course: Course, val session: ClassSession)

data class HomeUiState(
    val freshness: DataFreshness? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val userName: String = "",
    val greeting: String = "",
    val subtitle: String = "",
    val todayCourses: List<TodayCourse> = emptyList(),
    /** 今天的个人日程，按开始时间排序。 */
    val todayEvents: List<PersonalEvent> = emptyList(),
    val todayHint: String = "",
    val periodTimes: List<String> = emptyList(),
)

class HomeViewModel : ViewModel() {

    private val repo = ServiceLocator.repo
    private val settings = ServiceLocator.settings
    private val events = ServiceLocator.events

    private val _state = MutableStateFlow(HomeUiState(greeting = greetingFor(LocalTime.now()), periodTimes = settings.periodTimes))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            events.events.collect { all ->
                val today = LocalDate.now()
                _state.value = _state.value.copy(todayEvents = all.filter { it.occursOn(today) }.sortedBy { it.start })
            }
        }
        viewModelScope.launch {
            // 设置里改了作息，今日课程的起止时间要立即跟着变
            settings.periodTimesFlow.collect { _state.value = _state.value.copy(periodTimes = it) }
        }
    }

    fun refresh(forceRefresh: Boolean = false) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val today = LocalDate.now().dayOfWeek.value

            when (val t = repo.terms(forceRefresh = forceRefresh)) {
                is Outcome.Ok -> {
                    val ctx = repo.userContext
                    val term = repo.currentTerm(t.data)
                    // 周次必须按**实际取到课表的那个学期**算。按「今天所在学期」算的话，
                    // 教务提前切到下学期时（9 月 1–6 日、1–2 月）会算出「第 28 周」这种数。
                    val week = weekIn(term)
                    val weekLabel = if (week < 1) "开学前" else "第 $week 周"
                    if (term == null) {
                        _state.value = _state.value.copy(
                            loading = false,
                            userName = ctx?.userName.orEmpty(),
                            subtitle = "暂无可用学期",
                            todayHint = "教务系统尚未发布课表",
                        )
                        return@launch
                    }
                    when (val s = repo.schedule(term, forceRefresh = forceRefresh)) {
                        is Outcome.Ok -> {
                            val slots = if (week >= 1) {
                                s.data.slotsOn(week, today).map { TodayCourse(it.first, it.second) }
                            } else {
                                emptyList()
                            }
                            _state.value = HomeUiState(
                                loading = false,
                                greeting = greetingFor(LocalTime.now()),
                                userName = s.data.studentName.ifBlank { ctx?.userName.orEmpty() },
                                subtitle = "${term.name} · $weekLabel · 周${dayLabel(today)}",
                                todayCourses = slots,
                                freshness = s.freshness,
                                todayEvents = _state.value.todayEvents,
                                todayHint = if (week >= 1) {
                                    "$weekLabel 周${dayLabel(today)} 没有排课"
                                } else {
                                    "${term.name}还没开始"
                                },
                                periodTimes = settings.periodTimes,
                            )
                        }
                        is Outcome.Empty -> _state.value = HomeUiState(
                            loading = false,
                            greeting = greetingFor(LocalTime.now()),
                            userName = ctx?.userName.orEmpty(),
                            subtitle = "${term.name} · $weekLabel",
                            todayEvents = _state.value.todayEvents,
                            todayHint = s.reason,
                            periodTimes = settings.periodTimes,
                        )
                        is Outcome.Error -> _state.value = _state.value.copy(
                            loading = false, error = s.message, userName = ctx?.userName.orEmpty(),
                        )
                    }
                }
                is Outcome.Empty -> _state.value = _state.value.copy(
                    loading = false,
                    userName = repo.userContext?.userName.orEmpty(),
                    todayHint = t.reason,
                )
                is Outcome.Error -> _state.value = _state.value.copy(loading = false, error = t.message)
            }
        }
    }

    /**
     * 今天是 [term] 的第几周；学期还没开始时返回 0 或负数，由调用方显示「开学前」。
     * 拿不到学期时退回「今天所在学期」的周次。
     */
    private fun weekIn(term: Term?): Int {
        val start = term?.let { AcademicCalendar.firstMonday(it, useOfficial = true) }
            ?: return AcademicCalendar.currentWeek(useOfficial = true)
        return AcademicCalendar.weekOf(start, LocalDate.now())
    }

    private fun dayLabel(d: Int) = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(d - 1) { "?" }

    private fun greetingFor(t: LocalTime): String = when (t.hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}
