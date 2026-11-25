package io.github.joyreverie.onebnu.ui.classroom

import io.github.joyreverie.onebnu.data.repo.DataFreshness
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.PeriodMapper
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** 没有官方校历可依据时允许拨到的最大周次。 */
private const val DEFAULT_MAX_WEEK = 25

/** 常用教学楼排在楼房列表前面，其余保持教务原顺序。 */
private val COMMON_BUILDING_KEYS = listOf("教二", "教三", "教四", "教七", "教八", "教九", "化学", "生地", "艺术", "电子")

private fun commonFirst(buildings: List<Option>): List<Option> = buildings.sortedWith(
    compareBy { option ->
        val priority = COMMON_BUILDING_KEYS.indexOfFirst { option.name.contains(it) }
        if (priority >= 0) priority else COMMON_BUILDING_KEYS.size
    },
)

data class ClassroomUiState(
    val freshness: DataFreshness? = null,
    val loading: Boolean = false,
    val loadingOptions: Boolean = true,
    val error: String? = null,
    val emptyReason: String? = null,
    val buildings: List<Option> = emptyList(),
    val building: Option? = null,
    val term: Term? = null,
    /** 所查学期第 1 周的周一，周次与日期互相换算的基准。 */
    val termStart: LocalDate = AcademicCalendar.currentTermStart(useOfficial = true),
    /** 可拨到的最大周次：有官方校历取校历周数，否则给一个宽松上限。 */
    val maxWeek: Int = DEFAULT_MAX_WEEK,
    /** 周次依据的说明，界面上直接展示，避免把推算值误当官方校历。 */
    val calendarNote: String = "",
    val week: Int = 1,
    val dayOfWeek: Int = 1,
    /** 选中的节次，可不连续；空闲指所有选中节次都没有排课。为空表示整楼浏览。 */
    val periods: List<Int> = listOf(1, 2),
    val rooms: List<Classroom> = emptyList(),
    /** 本人课表，只读本地快照；没有快照时为空。 */
    val myCourses: List<Course> = emptyList(),
    val queried: Boolean = false,
) {
    /** 「第几周 + 周几」对应的具体日期；选日期会反过来改这两项。 */
    val date: LocalDate get() = AcademicCalendar.dateOf(termStart, week, dayOfWeek)

    /** 日期选择器允许的最后一天：最后一个可选周的周日。 */
    val lastDate: LocalDate get() = AcademicCalendar.dateOf(termStart, maxWeek, 7)

    val freeRooms: List<Classroom>
        get() {
            if (periods.isEmpty()) return emptyList()
            return rooms.filter { room ->
                periods.all { p ->
                    room.busy.none { s ->
                        s.dayOfWeek == dayOfWeek && s.occursOn(week) && s.startPeriod <= p && s.endPeriod >= p
                    }
                }
            }.sortedBy { it.name }
        }

    /** 整楼列表：空闲的在前，其余按教室名。 */
    val buildingRooms: List<Classroom>
        get() {
            val free = if (periods.isEmpty()) emptySet() else freeRooms.toSet()
            return rooms.sortedWith(compareByDescending<Classroom> { it in free }.thenBy { it.name })
        }
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
        if (s.term == null || s.buildings.isEmpty()) loadOptions(forceRefresh = true) else query(forceRefresh = true)
    }

    private fun loadOptions(forceRefresh: Boolean = false) {
        _state.update { it.copy(loadingOptions = true, error = null) }
        viewModelScope.launch {
            // 学期：教务当前学期，其次最新的一个；周次基准跟着所查学期走
            val terms = (repo.terms(forceRefresh = forceRefresh) as? Outcome.Ok)?.data
            val term = terms?.let { repo.currentTerm(it) }
            val official = term?.let { AcademicCalendar.official(it, useOfficial = true) }
            val start = term?.let { AcademicCalendar.firstMonday(it, useOfficial = true) }
                ?: AcademicCalendar.currentTermStart(useOfficial = true)
            val maxWeek = official?.weeks ?: DEFAULT_MAX_WEEK
            val today = LocalDate.now()
            _state.update {
                it.copy(
                    term = term,
                    termStart = start,
                    maxWeek = maxWeek,
                    calendarNote = when {
                        official != null -> "周次依据：${official.termLabel}官方校历"
                        term != null -> "周次依据：${term.name}，按学校惯例推算（珠海与北京通用周次）"
                        else -> "周次依据：按学校惯例推算"
                    },
                    week = AcademicCalendar.weekOf(start, today).coerceIn(1, maxWeek),
                    dayOfWeek = today.dayOfWeek.value,
                    // 默认选中当前所在的节次
                    periods = PeriodMapper.periodsFor(
                        LocalTime.now(), LocalTime.now(), ServiceLocator.settings.periodTimes,
                    ).toList(),
                )
            }

            // 课表只读本地快照，不为教室页单独请求教务
            val courses = term?.let { repo.cachedSchedule(it) }?.courses.orEmpty()
            _state.update { it.copy(myCourses = courses) }

            when (val c = repo.classroomCampus(forceRefresh = forceRefresh)) {
                is Outcome.Ok -> {
                    campus = c.data
                    loadBuildings(c.data.code, forceRefresh)
                }
                is Outcome.Empty -> _state.update { it.copy(loadingOptions = false, error = c.reason) }
                is Outcome.Error -> _state.update { it.copy(loadingOptions = false, error = c.message) }
            }
        }
    }

    private suspend fun loadBuildings(campusCode: String, forceRefresh: Boolean = false) {
        when (val b = repo.buildings(campusCode, forceRefresh = forceRefresh)) {
            is Outcome.Ok -> _state.update {
                it.copy(loadingOptions = false, buildings = commonFirst(b.data))
            }.also { maybeAutoSelectBuilding() }
            is Outcome.Empty -> _state.update { it.copy(loadingOptions = false, error = b.reason) }
            is Outcome.Error -> _state.update { it.copy(loadingOptions = false, error = b.message) }
        }
    }

    private var buildingManual = false
    private var queryJob: Job? = null

    /**
     * 进入页面时自动选楼并查询：查看今天时取当前或最近一节课所在的楼，
     * 同一时刻几节课不在同一栋楼就不猜；猜不中用上次查看的楼。手动选过楼后不再自动选。
     */
    private fun maybeAutoSelectBuilding() {
        val s = _state.value
        if (buildingManual || s.building != null || s.buildings.isEmpty() || s.loading) return
        val target = if (s.date == LocalDate.now()) currentCourseBuilding(s.myCourses, s.week, s.dayOfWeek) else null
        val name = target ?: ServiceLocator.settings.classroomLastBuilding ?: return
        val match = matchBuildingOption(s.buildings, name) ?: return
        _state.update { it.copy(building = match) }
        ServiceLocator.settings.classroomLastBuilding = match.name
        query()
    }

    /** 正在上的课，或离现在最近的一节课所在的楼；并列的几节不在同一栋楼时返回 null。 */
    private fun currentCourseBuilding(courses: List<Course>, week: Int, day: Int): String? {
        val periodTimes = ServiceLocator.settings.periodTimes
        val now = LocalTime.now()
        val nowMinute = now.hour * 60L + now.minute
        val timed = courses.flatMap { c -> c.sessions.filter { it.dayOfWeek == day && it.occursOn(week) } }
            .mapNotNull { session ->
                val start = periodTimes.getOrNull(session.startPeriod - 1)?.let { PeriodMapper.parsePeriod(it)?.first }
                    ?: return@mapNotNull null
                val end = periodTimes.getOrNull(session.endPeriod - 1)?.let { PeriodMapper.parsePeriod(it)?.second }
                    ?: return@mapNotNull null
                val building = parseBuilding(session.location) ?: return@mapNotNull null
                Triple(start, end, building)
            }
        if (timed.isEmpty()) return null
        fun minuteOf(t: LocalTime): Long = t.hour * 60L + t.minute
        fun distance(t: Triple<LocalTime, LocalTime, String>): Long =
            maxOf(minuteOf(t.first) - nowMinute, nowMinute - minuteOf(t.second), 0L)
        val ranked = timed.sortedBy { distance(it) }
        val best = distance(ranked.first())
        val buildings = ranked.takeWhile { distance(it) == best }.map { it.third }.toSet()
        return buildings.singleOrNull()
    }

    /** 从上课地点取楼名：「七103」「教七201」→ 教七楼；化学楼等具名楼原样返回。 */
    private fun parseBuilding(location: String): String? {
        val text = location.trim()
        for (named in listOf("化学楼", "生地楼", "艺术楼", "电子楼")) {
            if (text.startsWith(named)) return named
        }
        val m = Regex("""^教?([一二三四五六七八九十])楼?\s*\d""").find(text) ?: return null
        return "教${m.groupValues[1]}楼"
    }

    /** 楼名对回楼房选项：全名、互相包含、去掉「楼」后的前缀，依次尝试。 */
    private fun matchBuildingOption(buildings: List<Option>, target: String): Option? =
        buildings.firstOrNull { it.name == target }
            ?: buildings.firstOrNull { it.name.contains(target) || target.contains(it.name) }
            ?: buildings.firstOrNull { it.name.startsWith(target.removeSuffix("楼")) }

    fun selectBuilding(b: Option) {
        buildingManual = true
        _state.update { it.copy(building = b, rooms = emptyList(), queried = false, error = null) }
        ServiceLocator.settings.classroomLastBuilding = b.name
        query()
    }

    fun setWeek(w: Int) = _state.update { it.copy(week = w.coerceIn(1, it.maxWeek)) }
    fun setDay(d: Int) = _state.update { it.copy(dayOfWeek = d.coerceIn(1, 7)) }

    /** 按日期定位：换算成周次与星期。超出学期范围的日期忽略（选择器已限制可选范围）。 */
    fun setDate(d: LocalDate) = _state.update {
        val w = AcademicCalendar.weekOf(it.termStart, d)
        if (w in 1..it.maxWeek) it.copy(week = w, dayOfWeek = d.dayOfWeek.value) else it
    }

    /** 成对节次里点一个数字：这一对都没选时两节一起选上，否则只切换被点的那节。 */
    fun tapSegment(pairA: Int, pairB: Int, p: Int) = _state.update {
        val pairInactive = pairA !in it.periods && pairB !in it.periods
        val next = when {
            pairInactive -> (it.periods + pairA + pairB).distinct()
            p in it.periods -> it.periods - p
            else -> it.periods + p
        }
        it.copy(periods = next.sorted())
    }

    /** 整体替换所选节次。 */
    fun applyPeriods(ps: List<Int>) = _state.update {
        it.copy(periods = ps.filter { p -> p in 1..12 }.distinct().sorted())
    }

    fun query(forceRefresh: Boolean = false) {
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
        // 换楼后旧请求的结果不能覆盖新楼
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            val r = repo.classrooms(term, campusOpt.code, building.code, forceRefresh = forceRefresh)
            if (_state.value.building?.code != building.code) return@launch
            when (r) {
                is Outcome.Ok -> _state.value = _state.value.copy(
                    loading = false, rooms = r.data, freshness = r.freshness, queried = true, error = null, emptyReason = null,
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
