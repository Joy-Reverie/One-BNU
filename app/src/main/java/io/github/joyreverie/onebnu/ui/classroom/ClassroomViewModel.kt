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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** 没有官方校历可依据时允许拨到的最大周次。 */
private const val DEFAULT_MAX_WEEK = 25

/**
 * 常用教学楼排前面：教务的楼房下拉按全称拼音序，常用的几栋淹没在几十栋楼里。
 * 口径与 BNU Sparks 网页端一致（教二到教九 + 化学楼、生地楼、艺术楼、电子楼）；
 * 用包含匹配兼容「教二楼 / 教二教学楼」这类全称差异，没匹配上的保持教务原顺序排在后面。
 */
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
    /** 选中的节次，可多选、可不连续；查询语义是「全部选中节次都空闲」的交集。 */
    val periods: List<Int> = listOf(1, 2),
    val rooms: List<Classroom> = emptyList(),
    /** 学生本人课表（缓存优先取自教务），用于节次选择与「可连自习」的课表联动；取不到就为空，界面各处按无联动处理。 */
    val myCourses: List<Course> = emptyList(),
    val queried: Boolean = false,
) {
    /** 「第几周 + 周几」对应的具体日期；选日期会反过来改这两项。 */
    val date: LocalDate get() = AcademicCalendar.dateOf(termStart, week, dayOfWeek)

    /** 日期选择器允许的最后一天：最后一个可选周的周日。 */
    val lastDate: LocalDate get() = AcademicCalendar.dateOf(termStart, maxWeek, 7)

    val freeRooms: List<Classroom>
        get() = rooms.filter { room ->
            periods.all { p ->
                room.busy.none { s ->
                    s.dayOfWeek == dayOfWeek && s.occursOn(week) && s.startPeriod <= p && s.endPeriod >= p
                }
            }
        }.sortedBy { it.name }
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
                    // 默认选中「现在」所在的节次：进来就该看眼前能用的空教室。
                    periods = PeriodMapper.periodsFor(LocalTime.now(), LocalTime.now(), ServiceLocator.settings.periodTimes)
                        .toList(),
                )
            }

            // 课表联动：学生课表另路取（缓存优先，几乎即时）。失败静默——联动是锦上添花，不能卡住楼房列表。
            term?.let { t ->
                viewModelScope.launch {
                    when (val sc = repo.schedule(t, forceRefresh = forceRefresh)) {
                        is Outcome.Ok -> _state.update {
                            it.copy(myCourses = sc.data.courses)
                        }.also { maybeAutoPickBuilding() }
                        else -> Unit
                    }
                }
            }

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
            }.also { maybeAutoPickBuilding() }
            is Outcome.Empty -> _state.update { it.copy(loadingOptions = false, error = b.reason) }
            is Outcome.Error -> _state.update { it.copy(loadingOptions = false, error = b.message) }
        }
    }

    /** 用户手动选过楼之后，自动跟随课表选楼就退场，不再抢用户的决定。 */
    private var buildingManual = false

    /**
     * 自动选楼：没手动选过、且楼房与课表都拿到了、且正在看「今天」时，
     * 默认选中当前（或最近一节）自己要上的课所在的教学楼——与 BNU Sparks 网页端同规则：
     * 同时段的几节课不在同一栋楼时宁可不猜。手动选楼一次后即失效。
     */
    private fun maybeAutoPickBuilding() {
        val s = _state.value
        if (buildingManual || s.building != null || s.buildings.isEmpty() || s.myCourses.isEmpty()) return
        if (s.date != LocalDate.now()) return
        val target = currentCourseBuilding(s.myCourses, s.week, s.dayOfWeek) ?: return
        val match = matchBuildingOption(s.buildings, target) ?: return
        _state.update { it.copy(building = match) }
    }

    /** 当前正在上、不在上课则取与当前时刻最近的一节课；并列候选楼不一致时返回 null。 */
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
        // 上课中距离为 0；都不在上课就取最近的一节，并列最近（同时段）的几节楼不同就放弃
        val ranked = timed.sortedBy { distance(it) }
        val best = distance(ranked.first())
        val buildings = ranked.takeWhile { distance(it) == best }.map { it.third }.toSet()
        return buildings.singleOrNull()
    }

    /** 从上课地点猜教学楼：教务地点常省略「教」和「楼」，形如「七103」；具名楼（化学楼等）原样匹配。 */
    private fun parseBuilding(location: String): String? {
        val text = location.trim()
        for (named in listOf("化学楼", "生地楼", "艺术楼", "电子楼")) {
            if (text.startsWith(named)) return named
        }
        val m = Regex("""^([一二三四五六七八九十])\s*\d""").find(text) ?: return null
        return "教${m.groupValues[1]}楼"
    }

    /** 解析出的楼名对回楼房下拉的选项：全名 → 包含 → 去掉「楼」字前缀，三级兜底。 */
    private fun matchBuildingOption(buildings: List<Option>, target: String): Option? =
        buildings.firstOrNull { it.name == target }
            ?: buildings.firstOrNull { it.name.contains(target) || target.contains(it.name) }
            ?: buildings.firstOrNull { it.name.startsWith(target.removeSuffix("楼")) }

    fun selectBuilding(b: Option) {
        buildingManual = true
        _state.update { it.copy(building = b, rooms = emptyList(), queried = false, error = null) }
    }

    fun setWeek(w: Int) = _state.update { it.copy(week = w.coerceIn(1, it.maxWeek)) }
    fun setDay(d: Int) = _state.update { it.copy(dayOfWeek = d.coerceIn(1, 7)) }

    /** 按日期定位：换算成周次与星期。超出学期范围的日期忽略（选择器已限制可选范围）。 */
    fun setDate(d: LocalDate) = _state.update {
        val w = AcademicCalendar.weekOf(it.termStart, d)
        if (w in 1..it.maxWeek) it.copy(week = w, dayOfWeek = d.dayOfWeek.value) else it
    }

    /** 点一个节次加进 / 移出所选。只剩最后一个时不许清空：本页没有「不选节次」的浏览形态。 */
    fun togglePeriod(p: Int) = _state.update {
        val next = if (p in it.periods) it.periods - p else it.periods + p
        if (next.isEmpty()) it else it.copy(periods = next.sorted())
    }

    /** 快速选（后两节 / 到最后 / 全天）：整体替换所选节次，越界值丢掉、去重排序。 */
    fun applyPeriods(ps: List<Int>) = _state.update {
        val next = ps.filter { p -> p in 1..12 }.distinct().sorted()
        if (next.isEmpty()) it else it.copy(periods = next)
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
        viewModelScope.launch {
            when (val r = repo.classrooms(term, campusOpt.code, building.code, forceRefresh = forceRefresh)) {
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
