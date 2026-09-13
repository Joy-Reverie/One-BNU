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
 * 缓存优先的两条判定规则，单独拎出来是因为它们决定了「校外能不能看」这件事，
 * 而仓库本身依赖 Android 的 Context，没法直接跑单测。
 */
internal object CachePolicy {

    /**
     * 要不要直接用本地快照。
     *
     * **有快照就用，哪怕内容是空的**：教务「这一轮没有你的考试」本身就是一个答案，
     * 以前空快照不算数，于是每次进页面都要重新连一次教务，蜂窝 / 校外就是一直转圈。
     */
    fun preferSnapshot(hasSnapshot: Boolean, forceRefresh: Boolean): Boolean = hasSnapshot && !forceRefresh

    /**
     * 线上结果要不要写进快照。
     *
     * 有内容就写；没有内容时只在「还没有任何有内容的快照」时写 ——
     * 这样既能把「查到的就是空」存下来，又不会让教务的瞬态空响应抹掉好数据。
     */
    fun shouldWrite(liveHasContent: Boolean, snapshotHasContent: Boolean): Boolean =
        liveHasContent || !snapshotHasContent
}

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

    /**
     * 空数据不是错误：界面要显示「本学期暂无…」而不是报错。
     * [Empty] 也带 [DataFreshness]，因为「查到的就是空」同样可能来自本地快照，界面要照样提示需要更新。
     */
    sealed interface Outcome<out T> {
        data class Ok<T>(val data: T, val freshness: DataFreshness? = null) : Outcome<T>
        data class Empty(val reason: String, val freshness: DataFreshness? = null) : Outcome<Nothing>
        data class Error(val message: String, val needLogin: Boolean = false) : Outcome<Nothing>

        /** 这份结果是不是来自本地快照。 */
        val cached: Boolean
            get() = when (this) {
                is Ok -> freshness?.cached == true
                is Empty -> freshness?.cached == true
                is Error -> false
            }
    }

    /**
     * 首页预热、课表页和学分页可能同时触发查询；教务会话不是并发安全的。
     * **只锁真正发出请求的那一段** —— 读本地快照必须随时可以进行，
     * 否则登录后那一轮预取（十几个串行请求，蜂窝下每个都可能等满超时）会把所有页面一起堵住。
     */
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
        // ensureSession 已经把「代理 / 直连都不通」翻译成用户看得懂的一句话，不要再套一层「网络异常」
        is IOException -> message?.takeIf { it == ZyfwApi.UNREACHABLE_MESSAGE }
            ?: "网络异常：${message ?: "请稍后重试"}"
        else -> message ?: "出现未知错误"
    }

    /**
     * 缓存优先：有快照就先拿快照渲染，网络留给后台刷新。
     *
     * 两条与旧写法不同的规则：
     *  - **「查到的就是空」也是一种快照**。以前空快照过不了 [shouldCache]，于是每次进页面都要重新
     *    走一次网络；在蜂窝 / 校外网络下就是一直转圈最后报错。现在只要有快照就直接用，
     *    是空就显示「暂无…」，并由调用方在后台再取一次。
     *  - **锁只罩住真正的请求**。读快照在锁外，登录后的预取不会把课表、成绩、考试页一起堵住。
     *
     * 写入仍受 [shouldCache] 保护：空表或半截响应不覆盖已有的**有内容**快照；
     * 但一份快照都还没有时，空结果要存下来，否则「空」这个状态永远进不了缓存。
     */
    private suspend fun <T> cachedHtml(
        key: String,
        request: () -> String,
        parse: (String) -> T,
        shouldCache: (T) -> Boolean = { true },
        forceRefresh: Boolean = false,
    ): Outcome<T> {
        val snapshot = cachedParsed(key, parse)
        if (CachePolicy.preferSnapshot(snapshot != null, forceRefresh)) return requireNotNull(snapshot)
        val hadContent = snapshot != null && shouldCache(snapshot.data)

        return dataMutex.withLock {
            val live = call(request)
            if (live is Outcome.Ok) {
                val parsed = runCatching { parse(live.data) }
                if (parsed.isSuccess) {
                    val value = parsed.getOrThrow()
                    if (CachePolicy.shouldWrite(shouldCache(value), hadContent)) {
                        offlineCache?.let { cache ->
                            withContext(Dispatchers.IO) { cache.saveText(key, live.data) }
                        }
                    } else if (snapshot != null) {
                        // 瞬态空响应不能把上一次有内容的快照抹掉，直接继续用旧数据
                        return@withLock snapshot
                    }
                    return@withLock Outcome.Ok(value, DataFreshness(System.currentTimeMillis(), false))
                }

                snapshot?.let { return@withLock it }
                return@withLock Outcome.Error(parsed.exceptionOrNull()?.message ?: "解析教务数据失败")
            }

            snapshot?.let { return@withLock it }
            @Suppress("UNCHECKED_CAST")
            live as Outcome<T>
        }
    }

    private suspend fun cachedOptions(
        key: String,
        request: () -> List<Option>,
        shouldCache: (List<Option>) -> Boolean = { it.isNotEmpty() },
        forceRefresh: Boolean = false,
    ): Outcome<List<Option>> {
        val snapshot = loadCachedOptions(key)
        if (CachePolicy.preferSnapshot(snapshot != null, forceRefresh)) return requireNotNull(snapshot)
        val hadContent = snapshot != null && shouldCache(snapshot.data)

        return dataMutex.withLock {
            val live = call(request)
            if (live is Outcome.Ok) {
                if (CachePolicy.shouldWrite(shouldCache(live.data), hadContent)) {
                    offlineCache?.let { cache ->
                        withContext(Dispatchers.IO) { cache.saveOptions(key, live.data) }
                    }
                } else if (snapshot != null) {
                    return@withLock snapshot
                }
                return@withLock Outcome.Ok(live.data, DataFreshness(System.currentTimeMillis(), false))
            }
            snapshot?.let { return@withLock it }
            return@withLock live
        }
    }

    private suspend fun <T> cachedParsed(key: String, parse: (String) -> T): Outcome.Ok<T>? =
        offlineCache?.let { cache ->
            withContext(Dispatchers.IO) {
                cache.loadText(key)?.let { snapshot -> runCatching { Outcome.Ok(parse(snapshot.text), DataFreshness(snapshot.savedAt, true)) }.getOrNull() }
            }
        }

    private suspend fun loadCachedOptions(key: String): Outcome.Ok<List<Option>>? =
        offlineCache?.let { cache ->
            withContext(Dispatchers.IO) {
                cache.loadOptionsAt(key)?.let { (options, savedAt) ->
                    Outcome.Ok(options, DataFreshness(savedAt, true))
                }
            }
        }

    val userContext get() = api.userContext

    suspend fun terms(forceRefresh: Boolean = false): Outcome<List<Term>> = cachedOptions(
        OfflineCache.TERMS,
        request = { api.scheduleTerms() },
        shouldCache = { options -> options.any { Term.parse(it.code, it.name) != null } },
        forceRefresh = forceRefresh,
    ).let { o ->
        val converted: Outcome<List<Term>> = when (o) {
            is Outcome.Ok -> Outcome.Ok(o.data.mapNotNull { Term.parse(it.code, it.name) }, o.freshness)
            is Outcome.Empty -> Outcome.Empty(o.reason, o.freshness)
            is Outcome.Error -> Outcome.Error(o.message, o.needLogin)
        }
        if (converted is Outcome.Ok && converted.data.isEmpty()) {
            scheduleCache?.load()?.schedule?.term?.let { Outcome.Ok(listOf(it), converted.freshness) }
                ?: Outcome.Empty("教务系统暂未发布任何学期课表", converted.freshness)
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

    suspend fun schedule(term: Term, forceRefresh: Boolean = false): Outcome<Schedule> = cachedHtml(
        key = offlineCache?.key(OfflineCache.SCHEDULE, term.code) ?: "schedule_${term.code}",
        request = { api.scheduleHtml(term.xn, term.xq) },
        parse = { html -> Parsers.parseSchedule(html, term) },
        shouldCache = { it.courses.isNotEmpty() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok) {
            // 空课表不覆盖上一次有效的小组件数据，避免教务瞬态空响应造成数据消失。
            if (isCurrentTerm(term) && o.data.courses.isNotEmpty()) runCatching { scheduleCache?.save(o.data) }
            if (o.data.courses.isEmpty()) Outcome.Empty("${term.name}没有查询到选课记录", o.freshness) else o
        } else o
    }

    /**
     * 优先取「有效成绩」——只有这个视图带教务官方绩点。
     * 该视图为空时回退到原始成绩，此时绩点需本地换算。
     */
    suspend fun grades(forceRefresh: Boolean = false): Outcome<List<Grade>> {
        val valid = cachedHtml(
            key = OfflineCache.GRADES_VALID,
            request = { api.gradesHtml(validOnly = true) },
            parse = Parsers::parseGrades,
            shouldCache = { it.isNotEmpty() },
            forceRefresh = forceRefresh,
        )
        val result = if (valid is Outcome.Ok && valid.data.isNotEmpty()) valid else {
            cachedHtml(
                key = OfflineCache.GRADES_ALL,
                request = { api.gradesHtml(validOnly = false) },
                parse = Parsers::parseGrades,
                shouldCache = { it.isNotEmpty() },
                forceRefresh = forceRefresh,
            )
        }
        return result.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) {
            Outcome.Empty("教务系统中还没有成绩记录", o.freshness)
        } else o
        }
    }

    /** 培养方案中的课程模块；没有发布时返回空映射而不是错误。 */
    suspend fun courseModules(term: Term? = null, forceRefresh: Boolean = false): Outcome<Map<String, String>> {
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
            forceRefresh = forceRefresh,
        ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布培养方案课程模块", o.freshness) else o
        }
    }

    /** 网上选课「选课结果」里的官方课程类别；空表时由上层继续使用其他官方来源。 */
    suspend fun selectionCategories(forceRefresh: Boolean = false): Outcome<Map<String, String>> = cachedHtml(
        key = OfflineCache.SELECTION_CATEGORIES,
        request = { api.selectionResultHtml() },
        parse = Parsers::parseCourseCategories,
        shouldCache = { it.isNotEmpty() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未返回选课结果课程类别", o.freshness) else o
    }

    suspend fun examRounds(forceRefresh: Boolean = false): Outcome<List<Option>> = cachedOptions(
        OfflineCache.EXAM_ROUNDS,
        request = { api.examRounds() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布考试安排", o.freshness) else o
    }

    suspend fun exams(round: String, forceRefresh: Boolean = false): Outcome<List<Exam>> = cachedHtml(
        key = offlineCache?.key(OfflineCache.EXAMS, round) ?: "exams_${round.hashCode()}",
        request = { api.examsHtml(round) },
        parse = Parsers::parseExams,
        shouldCache = { it.isNotEmpty() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("该轮次下没有你的考试安排", o.freshness) else o
    }

    /** 当前登录入口对应的教务校区，用于查该校区的教室课表。 */
    suspend fun classroomCampus(forceRefresh: Boolean = false): Outcome<Option> {
        return when (val all = cachedOptions(OfflineCache.CAMPUSES, request = { api.campuses() }, forceRefresh = forceRefresh)) {
            is Outcome.Ok -> pickClassroomCampus(all.data)?.let { Outcome.Ok(it, all.freshness) }
                ?: Outcome.Empty("教务系统没有返回${campus.label}信息", all.freshness)
            is Outcome.Empty -> all
            is Outcome.Error -> all
        }
    }

    /** 兼容北京校区平面图旧调用点；新功能请使用 [classroomCampus]。 */
    suspend fun mainCampus(forceRefresh: Boolean = false): Outcome<Option> = when (campus) {
        Campus.BEIJING -> classroomCampus(forceRefresh)
        Campus.ZHUHAI -> Outcome.Empty("珠海校区没有内置北京校区平面图")
    }

    private fun pickClassroomCampus(all: List<Option>): Option? = when (campus) {
        Campus.BEIJING -> all.firstOrNull { it.name.contains("本部") } ?: all.firstOrNull { !it.name.contains("珠海") }
        Campus.ZHUHAI -> all.firstOrNull { it.name.contains("珠海") } ?: all.singleOrNull()
    }

    suspend fun buildings(campus: String, forceRefresh: Boolean = false): Outcome<List<Option>> = cachedOptions(
        offlineCache?.key(OfflineCache.BUILDINGS, campus) ?: "buildings_${campus.hashCode()}",
        request = { api.buildings(campus) },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统没有返回楼房列表", o.freshness) else o
    }

    suspend fun classrooms(term: Term, campus: String, building: String, forceRefresh: Boolean = false): Outcome<List<Classroom>> = cachedHtml(
        key = offlineCache?.key(OfflineCache.CLASSROOMS, term.code, campus, building)
            ?: "classrooms_${term.code.hashCode()}_${campus.hashCode()}_${building.hashCode()}",
        request = { api.classroomsHtml(term.xn, term.xq, campus, building) },
        parse = Parsers::parseClassrooms,
        shouldCache = { it.isNotEmpty() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("这栋楼没有查询到教室课表", o.freshness) else o
    }

    suspend fun studentInfo(forceRefresh: Boolean = false): Outcome<List<InfoItem>> {
        return dataMutex.withLock {
            if (!forceRefresh) {
                offlineCache?.let { cache ->
                    withContext(Dispatchers.IO) { cache.loadInfoItems(OfflineCache.STUDENT_INFO_ITEMS) }
                }?.takeIf { it.isNotEmpty() }?.let { return@withLock Outcome.Ok(it) }
            }
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

    suspend fun creditRequirement(forceRefresh: Boolean = false): Outcome<List<InfoItem>> = cachedHtml(
        key = OfflineCache.CREDIT_REQUIREMENTS,
        request = { api.creditRequirementHtml() },
        parse = Parsers::parseCreditRequirements,
        shouldCache = { it.isNotEmpty() },
        forceRefresh = forceRefresh,
    ).let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("没有查询到毕业学分要求", o.freshness) else o
    }

    /**
     * 登录成功后后台预热基本数据。每项独立失败，不能因为某个校区接口暂时不可用
     * 而阻断其余快照；各方法自身仍会把成功响应写入本地并在断网时回退。
     */
    suspend fun prefetchBasicData() {
        val allTerms = (terms(forceRefresh = true) as? Outcome.Ok)?.data.orEmpty()
        allTerms.forEach { term -> runCatching { schedule(term, forceRefresh = true) } }
        if (allTerms.isNotEmpty()) {
            allTerms.forEach { term -> runCatching { courseModules(term, forceRefresh = true) } }
        } else {
            runCatching { courseModules(forceRefresh = true) }
        }
        runCatching { grades(forceRefresh = true) }
        runCatching { selectionCategories(forceRefresh = true) }
        runCatching { studentInfo(forceRefresh = true) }
        runCatching { creditRequirement(forceRefresh = true) }

        val rounds = (examRounds(forceRefresh = true) as? Outcome.Ok)?.data.orEmpty()
        rounds.forEach { round -> runCatching { exams(round.code, forceRefresh = true) } }

        val classroomCampus = classroomCampus(forceRefresh = true)
        if (classroomCampus is Outcome.Ok) {
            runCatching { buildings(classroomCampus.data.code, forceRefresh = true) }
        }
    }
}
