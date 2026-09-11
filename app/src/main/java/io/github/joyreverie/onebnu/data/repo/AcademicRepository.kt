package io.github.joyreverie.onebnu.data.repo

import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.store.Campus
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
    private val useOfficialCalendar: Boolean = true,
    private val campus: Campus = Campus.BEIJING,
) {

    /** 空数据不是错误：界面要显示「本学期暂无…」而不是报错。 */
    sealed interface Outcome<out T> {
        data class Ok<T>(val data: T) : Outcome<T>
        data class Empty(val reason: String) : Outcome<Nothing>
        data class Error(val message: String, val needLogin: Boolean = false) : Outcome<Nothing>
    }

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

    val userContext get() = api.userContext

    suspend fun terms(): Outcome<List<Term>> = call {
        api.scheduleTerms().mapNotNull { Term.parse(it.code, it.name) }
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布任何学期课表") else o
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

    suspend fun schedule(term: Term): Outcome<Schedule> = call {
        val html = api.scheduleHtml(term.xn, term.xq)
        Parsers.parseSchedule(html, term).also { parsed ->
            // 没有选课记录也要写：小组件据此显示「本学期没有选课记录」，而不是一直「正在获取」
            if (isCurrentTerm(term)) runCatching { scheduleCache?.save(parsed) }
        }
    }.let { o ->
        if (o is Outcome.Ok && o.data.courses.isEmpty()) {
            Outcome.Empty("${term.name}没有查询到选课记录")
        } else o
    }

    /**
     * 优先取「有效成绩」——只有这个视图带教务官方绩点。
     * 该视图为空时回退到原始成绩，此时绩点需本地换算。
     */
    suspend fun grades(): Outcome<List<Grade>> = call {
        val valid = Parsers.parseGrades(api.gradesHtml(validOnly = true))
        if (valid.isNotEmpty()) valid else Parsers.parseGrades(api.gradesHtml(validOnly = false))
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) {
            Outcome.Empty("教务系统中还没有成绩记录")
        } else o
    }

    /** 培养方案中的课程模块；没有发布时返回空映射而不是错误。 */
    suspend fun courseModules(): Outcome<Map<String, String>> = call {
        Parsers.parseCourseModules(api.courseModulesHtml())
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布培养方案课程模块") else o
    }

    suspend fun examRounds(): Outcome<List<Option>> = call { api.examRounds() }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统暂未发布考试安排") else o
    }

    suspend fun exams(round: String): Outcome<List<Exam>> = call {
        Parsers.parseExams(api.examsHtml(round))
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("该轮次下没有你的考试安排") else o
    }

    /** 当前登录入口对应的教务校区，用于查该校区的教室课表。 */
    suspend fun classroomCampus(): Outcome<Option> = when (val o = call { pickClassroomCampus(api.campuses()) }) {
        is Outcome.Ok -> o.data?.let { Outcome.Ok(it) } ?: Outcome.Empty("教务系统没有返回${campus.label}信息")
        is Outcome.Empty -> o
        is Outcome.Error -> o
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

    suspend fun buildings(campus: String): Outcome<List<Option>> = call { api.buildings(campus) }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("教务系统没有返回楼房列表") else o
    }

    suspend fun classrooms(term: Term, campus: String, building: String): Outcome<List<Classroom>> = call {
        Parsers.parseClassrooms(api.classroomsHtml(term.xn, term.xq, campus, building))
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("这栋楼没有查询到教室课表") else o
    }

    suspend fun studentInfo(): Outcome<List<InfoItem>> = call {
        Parsers.parseInfoTable(api.studentInfoHtml())
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("没有查询到学籍信息") else o
    }

    suspend fun creditRequirement(): Outcome<List<InfoItem>> = call {
        Parsers.parseCreditRequirements(api.creditRequirementHtml())
    }.let { o ->
        if (o is Outcome.Ok && o.data.isEmpty()) Outcome.Empty("没有查询到毕业学分要求") else o
    }
}
