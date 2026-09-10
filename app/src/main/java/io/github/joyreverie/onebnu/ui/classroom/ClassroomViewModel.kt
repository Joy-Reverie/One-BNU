package io.github.joyreverie.onebnu.ui.classroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 没有官方校历可依据时允许拨到的最大周次。 */
private const val DEFAULT_MAX_WEEK = 25

data class ClassroomUiState(
    val loading: Boolean = false,
    val loadingOptions: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    val buildings: List<Option> = emptyList(),
    val building: Option? = null,
    val term: Term? = null,
    /** 所查学期第 1 周的周一，周次与日期互相换算的基准。 */
    val termStart: LocalDate = AcademicCalendar.currentTermStart(),
    /** 可拨到的最大周次：有官方校历取校历周数，否则给一个宽松上限。 */
    val maxWeek: Int = DEFAULT_MAX_WEEK,
    /** 周次依据的说明，界面上直接展示，避免把推算值误当官方校历。 */
    val calendarNote: String = "",
    val week: Int = 1,
    val dayOfWeek: Int = 1,
    /** 选中的节次区间，如 1..2。 */
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val rooms: List<Classroom> = emptyList(),
    val queried: Boolean = false,
) {
    /** 「第几周 + 周几」对应的具体日期；选日期会反过来改这两项。 */
    val date: LocalDate get() = AcademicCalendar.dateOf(termStart, week, dayOfWeek)

    /** 日期选择器允许的最后一天：最后一个可选周的周日。 */
    val lastDate: LocalDate get() = AcademicCalendar.dateOf(termStart, maxWeek, 7)

    val freeRooms: List<Classroom>
        get() = rooms.filter { it.isFreeAt(week, dayOfWeek, startPeriod..endPeriod) }
            .sortedBy { it.name }
}

/**
 * 空闲教室。
 *
 * 教务系统没有「查空教室」接口，只有「教室课表」——即占用表。
 * 因此这里的做法是：整栋楼拉一次课表，在本地对指定周次/星期/节次取补集。
 * 注意这只反映排课占用，临时借用、活动占用不会体现。
 *
 * 周次与日期是同一个量的两种写法：周次 + 星期换算成日期，日期反过来换算成周次 + 星期，
 * 基准是所查学期第 1 周的周一（优先取官方校历）。
 */
class ClassroomViewModel : ViewModel() {

    private val repo = ServiceLocator.repo

    private val _state = MutableStateFlow(ClassroomUiState())
    val state: StateFlow<ClassroomUiState> = _state.asStateFlow()

    /** 北京校区的教务编码。界面上不再暴露校区，只在这里内部持有。 */
    private var campus: Option? = null

    init {
        loadOptions()
    }

    /** 出错后的重试：学期或楼房还没拿到就重拉选项，否则重跑查询。 */
    fun retry() {
        val s = _state.value
        if (s.term == null || s.buildings.isEmpty()) loadOptions() else query()
    }

    private fun loadOptions() {
        _state.update { it.copy(loadingOptions = true, error = null) }
        viewModelScope.launch {
            // 学期：教务当前学期，其次最新的一个；周次基准跟着所查学期走
            val terms = (repo.terms() as? Outcome.Ok)?.data
            val term = terms?.let { repo.currentTerm(it) }
            val official = term?.let { AcademicCalendar.official(it) }
            val start = term?.let { AcademicCalendar.firstMonday(it) } ?: AcademicCalendar.currentTermStart()
            val maxWeek = official?.weeks ?: DEFAULT_MAX_WEEK
            val today = LocalDate.now()
            _state.update {
                it.copy(
                    term = term,
                    termStart = start,
                    maxWeek = maxWeek,
                    calendarNote = when {
                        official != null -> "周次依据：${official.termLabel}官方校历"
                        term != null -> "周次依据：${term.name}，按学校惯例推算（尚未录入官方校历）"
                        else -> "周次依据：按学校惯例推算"
                    },
                    week = AcademicCalendar.weekOf(start, today).coerceIn(1, maxWeek),
                    dayOfWeek = today.dayOfWeek.value,
                )
            }

            when (val c = repo.mainCampus()) {
                is Outcome.Ok -> {
                    campus = c.data
                    loadBuildings(c.data.code)
                }
                is Outcome.Empty -> _state.update { it.copy(loadingOptions = false, error = c.reason) }
                is Outcome.Error -> _state.update { it.copy(loadingOptions = false, error = c.message) }
            }
        }
    }

    private suspend fun loadBuildings(campusCode: String) {
        when (val b = repo.buildings(campusCode)) {
            is Outcome.Ok -> _state.update { it.copy(loadingOptions = false, buildings = b.data) }
            is Outcome.Empty -> _state.update { it.copy(loadingOptions = false, error = b.reason) }
            is Outcome.Error -> _state.update { it.copy(loadingOptions = false, error = b.message) }
        }
    }

    fun selectBuilding(b: Option) = _state.update {
        it.copy(building = b, rooms = emptyList(), queried = false, error = null)
    }

    fun setWeek(w: Int) = _state.update { it.copy(week = w.coerceIn(1, it.maxWeek)) }
    fun setDay(d: Int) = _state.update { it.copy(dayOfWeek = d.coerceIn(1, 7)) }

    /** 按日期定位：换算成周次与星期。超出学期范围的日期忽略（选择器已限制可选范围）。 */
    fun setDate(d: LocalDate) = _state.update {
        val w = AcademicCalendar.weekOf(it.termStart, d)
        if (w in 1..it.maxWeek) it.copy(week = w, dayOfWeek = d.dayOfWeek.value) else it
    }

    fun setPeriods(start: Int, end: Int) = _state.update {
        val a = start.coerceIn(1, 12)
        val b = end.coerceIn(a, 12)
        it.copy(startPeriod = a, endPeriod = b)
    }

    fun query() {
        val s = _state.value
        val term = s.term
        val campusOpt = campus
        val building = s.building
        if (term == null || campusOpt == null) {
            _state.value = s.copy(error = "没有拿到学期或校区信息，请重试")
            return
        }
        if (building == null) {
            _state.value = s.copy(error = "请先选择楼房")
            return
        }
        _state.value = s.copy(loading = true, error = null, emptyReason = null)
        viewModelScope.launch {
            when (val r = repo.classrooms(term, campusOpt.code, building.code)) {
                is Outcome.Ok -> _state.value = _state.value.copy(
                    loading = false, rooms = r.data, queried = true, error = null, emptyReason = null,
                )
                is Outcome.Empty -> _state.value = _state.value.copy(
                    loading = false, rooms = emptyList(), queried = true, emptyReason = r.reason,
                )
                is Outcome.Error -> _state.value = _state.value.copy(
                    loading = false, error = r.message,
                )
            }
        }
    }

    private inline fun MutableStateFlow<ClassroomUiState>.update(f: (ClassroomUiState) -> ClassroomUiState) {
        value = f(value)
    }
}
