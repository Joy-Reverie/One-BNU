package io.github.joyreverie.onebnu.data.repo

import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.core.store.OfflineCache
import io.github.joyreverie.onebnu.core.store.ScheduleCache
import io.github.joyreverie.onebnu.core.store.SecureStore
import io.github.joyreverie.onebnu.data.model.AcademicCalendar
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.Exam
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.Term
import io.github.joyreverie.onebnu.data.parse.Parsers
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/**
 * 教务数据仓库。
 *
 * 统一处理两件事：
 *  - 会话失效时用已保存的凭据静默重登一次再重试，避免用户频繁看到登录页
 *  - 把 HTML 解析成领域模型，并把「查得到但没有数据」和「查询失败」区分开
 */
class AcademicRepository(
    private val api: ZyfwApi,
    private val auth: SessionAuthenticator,
    private val secure: SecureStore,
    /** 当前学期课表的本地缓存（桌面小组件用）；为 null 时不缓存。 */
    private val scheduleCache: ScheduleCache? = null,
    private val offlineCache: OfflineCache? = null,
    private val useOfficialCalendar: Boolean = true,
    private val campus: Campus = Campus.BEIJING,
) {

    /** 空数据不是错误：界面要显示「本学期暂无…」而不是报错。 */
    sealed interface Outcome<out T> {
        data class Ok<T>(val data: T) : Outcome<T>
        data class Empty(val reason: String) : Outcome<Nothing>
        data class Error(val message: String, val needLogin: Boolean = false) : Outcome<Nothing>
    }

    /** 首页预热、课表页和学分页可能同时触发查询；教务会话不是并发安全的。 */
    private val dataMutex = Mutex()

    private suspend fun <T> call(block: () -> T): Outcome<T> = withContext(Dispatchers.IO) {
        try {
            Outcome.Ok(block())
        } catch (e: ZyfwApi.SessionExpiredException) {
            // 用保存的凭据重登一次
            if (secure.hasCredentials) {
                val ok = runCatching { auth.relogin(secure.username, secure.password) }.getOrDefault(false)
                if (ok) {
                    api.invalidate()
                    return@withContext try {
                        Outcome.Ok(block())
                    } catch (e2: Exception) {
                        Outcome.Error(e2.friendly(), needLogin = e2 is ZyfwApi.SessionExpiredException)
                    }
                }
            }
            Outcome.Error("登录状态已失效，请重新登录", needLogin = true)
        } catch (e: Exception) {
            Outcome.Error(e.friendly())
        }
    }

    private fun Exception.friendly(): String = when (this) {
        is ZyfwApi.SessionExpiredException -> "登录状态已失效，请重新登录"
        is java.net.UnknownHostException -> "无法连接到教务系统，请检查网络"
        is java.net.SocketTimeoutException -> "教务系统响应超时，请稍后重试"
        is IOException -> "网络异常：${message ?: "请稍后重试"}"
        else -> message ?: "出现未知错误"
    }

    /** 先走线上请求，失败时用同一解析器读取本地原始快照。 */
    private suspend fun <T> cachedHtml(
        key: String,
        request: () -> String,
        parse: (String) -> T,
        shouldCache: (T) -> Boolean = { true },
    ): Outcome<T> = dataMutex.withLock {
        val live = call(request)
        if (live is Outcome.Ok) {
            val parsed = runCatching { parse(live.data) }
            if (parsed.isSuccess) {
                val value = parsed.getOrThrow()
                if (shouldCache(value)) {
                    offlineCache?.let { cache ->
                        withContext(Dispatchers.IO) { cache.saveText(key, live.data) }
                    }
                } else {
                    // 空表/半截页面不能覆盖上一次有效快照；瞬态空响应时直接继续用旧数据。
                    cachedParsed(key, parse)?.takeIf(shouldCache)?.let { return@withLock Outcome.Ok(it) }
                }
                return@withLock Outcome.Ok(value)
            }

            cachedParsed(key, parse)?.takeIf(shouldCache)?.let { return@withLock Outcome.Ok(it) }
            return@withLock Outcome.Error(parsed.exceptionOrNull()?.message ?: "解析教务数据失败")
        }

        cachedParsed(key, parse)?.takeIf(shouldCache)?.let { return@withLock Outcome.Ok(it) }
        @Suppress("UNCHECKED_CAST")
        live as Outcome<T>
    }

    private suspend fun cachedOptions(
        key: String,
        request: () -> List<Option>,
        shouldCache: (List<Option>) -> Boolean = { it.isNotEmpty() },
    ): Outcome<List<Option>> = dataMutex.withLock {
        val live = call(request)
        if (live is Outcome.Ok) {
            if (shouldCache(live.data)) {
                offlineCache?.let { cache ->
                    withContext(Dispatchers.IO) { cache.saveOptions(key, live.data) }
                }
            } else {
                loadCachedOptions(key)?.takeIf(shouldCache)?.let { return@withLock Outcome.Ok(it) }
            }
            return@withLock live
        }
        loadCachedOptions(key)?.takeIf(shouldCache)?.let { return@withLock Outcome.Ok(it) }
        return@withLock live
    }

    private suspend fun <T> cachedParsed(key: String, parse: (String) -> T): T? =
        offlineCache?.let { cache ->
            withContext(Dispatchers.IO) {
                cache.loadText(key)?.let { snapshot -> runCatching { parse(snapshot.text) }.getOrNull() }
            }
        }

    private suspend fun loadCachedOptions(key: String): List<Option>? =
        offlineCache?.let { cache -> withContext(Dispatchers.IO) { cache.loadOptions(key) } }

    val userContext get() = api.userContext

    suspend fun terms(): Outcome<List<Term>> = cachedOptions(
        OfflineCache.TERMS,
        request = { api.scheduleTerms() },
        shouldCache = { options -> options.any { Term.parse(it.code, it.name) != null } },
    ).let { o ->
        val converted: Outcome<List<Term>> = when (o) {
            is Outcome.Ok -> Outcome.Ok(o.data.mapNotNull { Term.parse(it.code, it.name) })
            is Outcome.Empty -> Outcome.Empty(o.reason)
            is Outcome.Error -> Outcome.Error(o.message, o.needLogin)
        }
        if (converted is Outcome.Ok && converted.data.isEmpty()) {
            scheduleCache?.load()?.schedule?.term?.let { Outcome.Ok(listOf(it)) }
                ?: Outcome.Empty("教务系统暂未发布任何学期课表")
        } else converted
    }

    /**
     * 「当前学期」：优先取教务登录信息里的当前学年学期；拿不到时按今天日期推算，
     * 再退到已开始的最新学期。首页、空闲教室、桌面小组件都用它，保证指向同一个学期。
     */
    fun currentTerm(terms: List<Term>, today: LocalDate = LocalDate.now()): Term? {
        val ctx = api.userContext
        if (ctx != null && ctx.currentXn.isNotBlank()) {
            terms.firstOrNull { it.xn == ctx.currentXn && it.xq == ctx.currentXq }?.let { return it }
        }
        val (y, season) = AcademicCalendar.currentTerm(today, useOfficialCalendar)
        terms.firstOrNull { AcademicCalendar.academicYear(it) == y && AcademicCalendar.season(it) == season }
            ?.let { return it }
        return terms.filter { AcademicCalendar.hasBegun(it, today) }.maxByOrNull { AcademicCalendar.sortKey(it) }
            ?: terms.firstOrNull()
    }

    /** 与 [currentTerm] 同一套判断：这个学期是不是当前学期，是才值得写进小组件缓存。 */
    private fun isCurrentTerm(term: Term): Boolean {
        val ctx = api.userContext
        if (ctx != null && ctx.currentXn.isNotBlank()) return term.xn == ctx.currentXn && term.xq == ctx.currentXq
        val (y, season) = AcademicCalendar.currentTerm(useOfficial = useOfficialCalendar)
        return AcademicCalendar.academicYear(term) == y && AcademicCalendar.season(term) == season
    }

    suspend fun schedule(term: Term): Outcome<Schedule> = cachedHtml(
        key = offlineCache?.key(OfflineCache.SCHEDULE, term.code) ?: "schedule_${term.code}",
        request = { api.scheduleHtml(term.xn, term.xq) },
        parse = { html -> Parsers.parseSchedule(html, term) },
        shouldCache = { it.courses.isNotEmpty() },
    ).let { o ->
        if (o is Outcome.Ok) {
            // 空课表不覆盖上一次有效的小组件数据，避免教务瞬态空响应造成数据消失。
            if (isCurrentTerm(term) && o.data.courses.isNotEmpty()) runCatching { scheduleCache?.save(o.data) }
            if (o.data.courses.isEmpty()) Outcome.Empty("${term.name}没有查询到选课记录") else o
        } else o
    }

    /**
     * 优先取「有效成绩」——只有这个视图带教务官方绩点。
     * 该视图为空时回退到原始成绩，此时绩点需本地换算。
     */
    suspend fun grades(): Outcome<List<Grade>> {
        val valid = cachedHtml(
            key = OfflineCache.GRADES_VALID,
            request = { api.gradesHtml(validOnly = true) },
            parse = Parsers::parseGrades,
            shouldCache = { it.isNotEmpty() },
        )
        val result = if (valid is Outcome.Ok && valid.data.isNotEmpty()) valid else {
            cachedHtml(
                key = OfflineCache.GRADES_ALL,
                request = { api.gradesHtml(validOnly = false) },
                parse = Parsers::parseGrades,
                shouldCache = { it.isNotEmpty() },
            )
        }
        return result.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) {
            Outcome.Empty("教务系统中还没有成绩记录")
        } else o
        }
    }

    /** 培养方案中的课程模块；没有发布时返回空映射而不是错误。 */
    suspend fun courseModules(term: Term? = null): Outcome<Map<String, String>> {
        val year = term?.xn.orEmpty()
        val season = term?.xq.orEmpty()
        val key = if (term == null) {
            OfflineCache.COURSE_MODULES
        } else {
            offlineCache?.key(OfflineCache.COURSE_MODULES, year, season)
                ?: "course_modules_${year}_${season}"
        }
        return cachedHtml(
            key = key,
            request = { api.courseModulesHtml(year, season) },
            parse = Parsers::parseCourseModules,
            shouldCache = { it.isNotEmpty() },
        ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布培养方案课程模块") else o
        }
    }

    /** 网上选课「选课结果」里的官方课程类别；空表时由上层继续使用其他官方来源。 */
    suspend fun selectionCategories(): Outcome<Map<String, String>> = cachedHtml(
        key = OfflineCache.SELECTION_CATEGORIES,
        request = { api.selectionResultHtml() },
        parse = Parsers::parseCourseCategories,
        shouldCache = { it.isNotEmpty() },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未返回选课结果课程类别") else o
    }

    suspend fun examRounds(): Outcome<List<Option>> = cachedOptions(
        OfflineCache.EXAM_ROUNDS,
        request = { api.examRounds() },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布考试安排") else o
    }

    suspend fun exams(round: String): Outcome<List<Exam>> = cachedHtml(
        key = offlineCache?.key(OfflineCache.EXAMS, round) ?: "exams_${round.hashCode()}",
        request = { api.examsHtml(round) },
        parse = Parsers::parseExams,
        shouldCache = { it.isNotEmpty() },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("该轮次下没有你的考试安排") else o
    }

    /** 当前登录入口对应的教务校区，用于查该校区的教室课表。 */
    suspend fun classroomCampus(): Outcome<Option> {
        return when (val all = cachedOptions(OfflineCache.CAMPUSES, request = { api.campuses() })) {
            is Outcome.Ok -> pickClassroomCampus(all.data)?.let { Outcome.Ok(it) }
                ?: Outcome.Empty("教务系统没有返回${campus.label}信息")
            is Outcome.Empty -> all
            is Outcome.Error -> all
        }
    }

    /** 兼容北京校区平面图旧调用点；新功能请使用 [classroomCampus]。 */
    suspend fun mainCampus(): Outcome<Option> = when (campus) {
        Campus.BEIJING -> classroomCampus()
        Campus.ZHUHAI -> Outcome.Empty("珠海校区没有内置北京校区平面图")
    }

    private fun pickClassroomCampus(all: List<Option>): Option? = when (campus) {
        Campus.BEIJING -> all.firstOrNull { it.name.contains("本部") } ?: all.firstOrNull { !it.name.contains("珠海") }
        Campus.ZHUHAI -> all.firstOrNull { it.name.contains("珠海") } ?: all.singleOrNull()
    }

    suspend fun buildings(campus: String): Outcome<List<Option>> = cachedOptions(
        offlineCache?.key(OfflineCache.BUILDINGS, campus) ?: "buildings_${campus.hashCode()}",
        request = { api.buildings(campus) },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统没有返回楼房列表") else o
    }

    suspend fun classrooms(term: Term, campus: String, building: String): Outcome<List<Classroom>> = cachedHtml(
        key = offlineCache?.key(OfflineCache.CLASSROOMS, term.code, campus, building)
            ?: "classrooms_${term.code.hashCode()}_${campus.hashCode()}_${building.hashCode()}",
        request = { api.classroomsHtml(term.xn, term.xq, campus, building) },
        parse = Parsers::parseClassrooms,
        shouldCache = { it.isNotEmpty() },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("这栋楼没有查询到教室课表") else o
    }

    suspend fun studentInfo(): Outcome<List<InfoItem>> {
        return dataMutex.withLock {
            val live = call { Parsers.parseInfoTable(api.studentInfoHtml()) }
            if (live is Outcome.Ok) {
                if (live.data.isNotEmpty()) {
                    offlineCache?.let { cache ->
                        withContext(Dispatchers.IO) { cache.saveInfoItems(OfflineCache.STUDENT_INFO_ITEMS, live.data) }
                    }
                } else {
                    offlineCache?.let { cache ->
                        withContext(Dispatchers.IO) { cache.loadInfoItems(OfflineCache.STUDENT_INFO_ITEMS) }
                    }?.takeIf { it.isNotEmpty() }?.let { return@withLock Outcome.Ok(it) }
                }
                return@withLock if (live.data.isEmpty()) Outcome.Empty("没有查询到学籍信息") else live
            }
            val cached = offlineCache?.let { cache ->
                withContext(Dispatchers.IO) { cache.loadInfoItems(OfflineCache.STUDENT_INFO_ITEMS) }
            }
            if (cached != null) return@withLock if (cached.isEmpty()) Outcome.Empty("没有查询到学籍信息") else Outcome.Ok(cached)
            return@withLock live
        }
    }

    suspend fun creditRequirement(): Outcome<List<InfoItem>> = cachedHtml(
        key = OfflineCache.CREDIT_REQUIREMENTS,
        request = { api.creditRequirementHtml() },
        parse = Parsers::parseCreditRequirements,
        shouldCache = { it.isNotEmpty() },
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("没有查询到毕业学分要求") else o
    }

    /**
     * 登录成功后后台预热基本数据。每项独立失败，不能因为某个校区接口暂时不可用
     * 而阻断其余快照；各方法自身仍会把成功响应写入本地并在断网时回退。
     */
    suspend fun prefetchBasicData() {
        val allTerms = (terms() as? Outcome.Ok)?.data.orEmpty()
        allTerms.forEach { term -> runCatching { schedule(term) } }
        if (allTerms.isNotEmpty()) {
            allTerms.forEach { term -> runCatching { courseModules(term) } }
        } else {
            runCatching { courseModules() }
        }
        runCatching { grades() }
        runCatching { selectionCategories() }
        runCatching { studentInfo() }
        runCatching { creditRequirement() }

        val rounds = (examRounds() as? Outcome.Ok)?.data.orEmpty()
        rounds.forEach { round -> runCatching { exams(round.code) } }

        val classroomCampus = classroomCampus()
        if (classroomCampus is Outcome.Ok) {
            runCatching { buildings(classroomCampus.data.code) }
        }
    }
}
