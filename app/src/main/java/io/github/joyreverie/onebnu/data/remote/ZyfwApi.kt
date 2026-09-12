package io.github.joyreverie.onebnu.data.remote

import io.github.joyreverie.onebnu.core.net.Http
import io.github.joyreverie.onebnu.core.net.OneVpnSso
import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.parse.Parsers
import org.json.JSONArray
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64

/**
 * KINGOSOFT 教务系统（zyfw.bnu.edu.cn）接口封装。
 *
 * 所有请求都依赖 CAS 会话；[ensureSession] 负责在会话失效时静默重新 SSO，
 * 若连 CAS 票据也没了则抛出 [SessionExpiredException] 交由上层引导重新登录。
 */
class ZyfwApi(
    private val http: Http,
    private val auth: SessionAuthenticator,
    val base: String = BASE,
    private val proxyBase: String? = null,
    private val preferProxy: () -> Boolean = { false },
) {

    companion object {
        const val BASE = "http://zyfw.bnu.edu.cn"

        /** 教务系统只提供 HTTP；移动网络下由北京 OneVPN 以 HTTPS 代理访问。 */
        const val NOTE_CLEARTEXT = "zyfw.bnu.edu.cn 仅支持 HTTP，流量网络使用 OneVPN 代理"
    }

    class SessionExpiredException : IOException("登录状态已失效")

    @Volatile private var ssoDone = false
    @Volatile private var cachedToken: String? = null
    @Volatile private var activeBase: String = base

    /** 登录信息（来自 SetMainInfo.jsp），SSO 后可用。 */
    data class UserContext(
        val loginId: String,
        val userCode: String,
        val userName: String,
        val currentXn: String,
        val currentXq: String,
        val termDesc: String,
    )

    @Volatile var userContext: UserContext? = null
        private set

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    @Synchronized
    @Throws(IOException::class)
    fun ensureSession(force: Boolean = false) {
        val shouldUseProxy = proxyBase != null && preferProxy()
        if (
            ssoDone && !force &&
            ((shouldUseProxy && activeBase == proxyBase) || (!shouldUseProxy && activeBase == base))
        ) return
        if (!auth.hasSession()) throw SessionExpiredException()

        if (shouldUseProxy) {
            establishProxySession()
            return
        }

        try {
            establishDirectSession()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: IOException) {
            // 校园网通常可以直连旧教务；流量或校外 Wi-Fi 可能只是不通 80 端口，
            // 这时把同一目标切到已认证的 OneVPN HTTPS 代理，不改变 CAS 登录状态。
            if (proxyBase == null) throw e
            establishProxySession()
        }
    }

    fun invalidate() {
        ssoDone = false
        cachedToken = null
        userContext = null
        activeBase = base
    }

    @Throws(IOException::class)
    private fun establishDirectSession() {
        activeBase = base
        val service = if (base == BASE) "$base/" else "$base/caslogin"
        val res = auth.sso(service)
        if (isExpiredPage(res.body) || Parsers.looksLikeLoginPage(res.body)) {
            ssoDone = false
            throw SessionExpiredException()
        }
        ssoDone = false
        cachedToken = null
        loadUserContext()
        ssoDone = true
    }

    @Throws(IOException::class)
    private fun establishProxySession() {
        val target = proxyBase ?: throw IOException("教务代理未配置")
        if (!OneVpnSso.establish(http, auth, Campus.BEIJING, "$target/")) {
            ssoDone = false
            throw IOException("教务系统代理会话未能建立，请检查网络后重试")
        }
        activeBase = target
        ssoDone = false
        cachedToken = null
        loadUserContext()
        ssoDone = true
    }

    private fun loadUserContext() {
        val js = guard(http.get("$activeBase/frame/home/js/SetMainInfo.jsp", home).body)
        fun v(name: String) = Regex("""var\s+$name\s*=\s*['\"]([^'\"]*)['\"]""").find(js)?.groupValues?.get(1)
        val loginId = v("_loginid") ?: v("G_LOGIN_ID") ?: Regex("""\[([^\]]+)]""").find(js)?.groupValues?.get(1) ?: return
        userContext = UserContext(
            loginId = loginId,
            userCode = v("G_USER_CODE").orEmpty(),
            userName = v("_userName") ?: v("G_USER_NAME").orEmpty(),
            currentXn = v("_currentXn").orEmpty(),
            currentXq = v("_currentXq").orEmpty(),
            termDesc = v("_xnxqDesc").orEmpty(),
        )
    }

    /** 报表页面需要的一次性令牌。 */
    @Throws(IOException::class)
    private fun token(): String {
        cachedToken?.let { return it }
        val t = http.get("$activeBase/frame/menus/js/SetTokenkey.jsp", home).body
            .replace(Regex("\\s"), "")
        cachedToken = t
        return t
    }

    private fun b64(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** 统一出口：命中登录页时清掉会话并抛出，让上层重新登录后重试一次。 */
    @Throws(IOException::class)
    private fun guard(body: String): String {
        if (isExpiredPage(body) || Parsers.looksLikeLoginPage(body)) {
            invalidate()
            throw SessionExpiredException()
        }
        return body
    }

    /** 珠海教务过期时返回一段 JS alert，而不是北京系统的登录 HTML。 */
    private fun isExpiredPage(body: String): Boolean =
        body.contains("凭证已失效") || body.contains("login.action") && body.contains("请重新登录")

    // ------------------------------------------------------------------
    // 下拉数据
    // ------------------------------------------------------------------

    /** 教务通用下拉接口，直接返回 `[{"code":..,"name":..}]`。 */
    @Throws(IOException::class)
    fun dropList(comboBoxName: String, paramValue: String = ""): List<Option> {
        ensureSession()
        val res = http.postForm(
            "$activeBase/frame/droplist/getDropLists.action",
            mapOf(
                "comboBoxName" to comboBoxName,
                "paramValue" to paramValue,
                "isNeedNull" to "false",
            ),
            referer = home,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
        )
        val body = guard(res.body).trim()
        if (!body.startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Option(o.optString("code"), o.optString("name"))
        }
    }

    /**
     * 已发布课表的学年学期。
     *
     * 珠海学生端页面使用 `Ms_KBBP_FBXKJGXNXQ`：它只返回当前学生可查的学期。
     * 若该下拉暂未配置则回退到通用下拉，避免误报「没有发布任何学期课表」。
     */
    @Throws(IOException::class)
    fun scheduleTerms(): List<Option> {
        val personalTerms = if (base != BASE) dropList("Ms_KBBP_FBXKJGXNXQ") else emptyList()
        return personalTerms.ifEmpty { dropList("Ms_KBBP_FBXQLLJXAP") }
    }

    /** 已发布的考试轮次，code 形如 "2025,1,2"。 */
    @Throws(IOException::class)
    fun examRounds(): List<Option> = dropList("Ms_KSSW_FBXNXQKSLC")

    @Throws(IOException::class)
    fun campuses(): List<Option> = dropList("MsSchoolArea")

    @Throws(IOException::class)
    fun buildings(campus: String): List<Option> = dropList("MsSchoolArea_LF", "ssxq=$campus")

    @Throws(IOException::class)
    fun classroomTypes(): List<Option> = dropList("MsCodeset", "DM-JSLX")

    // ------------------------------------------------------------------
    // 课表
    // ------------------------------------------------------------------

    /** 学生课表（列表视图，字段最全）。 */
    @Throws(IOException::class)
    fun scheduleHtml(xn: String, xq: String): String {
        ensureSession()
        val params = b64("xn=$xn&xq=$xq")
        val url = "$activeBase/wsxk/xkjg.ckdgxsxdkchj_data10319.jsp?params=$params&t=${token()}"
        return guard(http.get(url, home).body)
    }

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    /**
     * @param validOnly true 取「有效成绩」（含学分绩点），false 取原始成绩
     * @param xn 指定学年；为空表示全部学期
     */
    @Throws(IOException::class)
    fun gradesHtml(validOnly: Boolean, xn: String = "", xq: String = ""): String {
        ensureSession()
        val page = if (validOnly) "xscj.chkdgxscjyxxjd_data.jsp" else "xscj.stuckcj_data.jsp"
        val form = mapOf(
            "sjxz" to if (xn.isBlank()) "sjxz1" else "sjxz3",
            "ysyx" to if (validOnly) "yxcj" else "yscj",
            "zfx" to "0",
            "t" to token(),
            "xn" to xn,
            "xn1" to (xn.toIntOrNull()?.plus(1)?.toString() ?: ""),
            "xq" to xq,
        )
        return guard(
            http.postForm("$activeBase/student/$page", form, referer = "$activeBase/student/xscj.stuckcj.jsp").body,
        )
    }

    /** 教务培养方案对比报表；有课程模块时可为学分核算提供权威归类。 */
    @Throws(IOException::class)
    fun courseModulesHtml(xn: String = "", xq: String = ""): String {
        ensureSession()
        val context = userContext
        val year = xn.ifBlank { context?.currentXn.orEmpty() }
        val season = xq.ifBlank { context?.currentXq.orEmpty() }
        return guard(
            http.postForm(
                "$activeBase/taglib/DataTable.jsp?tableId=5327008",
                mapOf(
                    "xh" to (context?.userCode ?: context?.loginId).orEmpty(),
                    "xn" to year,
                    "xn1" to (year.toIntOrNull()?.plus(1)?.toString() ?: ""),
                    "xq_m" to season,
                    "_xq" to season,
                    "ck_px" to "akcmk",
                ),
                referer = "$activeBase/student/wsxk.pyfadb.html",
            ).body,
        )
    }

    // ------------------------------------------------------------------
    // 考试
    // ------------------------------------------------------------------

    /** @param round examRounds() 返回的 code，形如 "2025,1,2" */
    @Throws(IOException::class)
    fun examsHtml(round: String): String {
        ensureSession()
        val parts = round.split(",")
        val form = mapOf(
            "xh" to "",
            "xn" to parts.getOrElse(0) { "" },
            "xq" to parts.getOrElse(1) { "" },
            "kslc" to parts.getOrElse(2) { "" },
        )
        return guard(
            http.postForm(
                "$activeBase/taglib/DataTable.jsp?tableId=2538",
                form,
                referer = "$activeBase/student/ksap.ksapb.html",
            ).body,
        )
    }

    // ------------------------------------------------------------------
    // 教室课表
    // ------------------------------------------------------------------

    /**
     * 按楼房拉取整栋楼所有教室的课表（列表格式），据此在本地推算空闲教室。
     * 教务没有「空闲教室」接口，只能取占用表再取补集。
     */
    @Throws(IOException::class)
    fun classroomsHtml(xn: String, xq: String, campus: String, building: String, roomType: String = ""): String {
        ensureSession()
        val form = mapOf(
            "hidFJBH" to "",
            "hidXQ" to campus,
            "hidLF" to building,
            "hidJSLX" to roomType,
            "hidSYDW" to "",
            "hidCXLX" to if (roomType.isBlank()) "flf" else "flx",
            "xssj" to "xssj",
            "xsrq" to "xsrq",
            "sfxsym" to "1",
            "xn" to xn,
            "xn1" to (xn.toIntOrNull()?.plus(1)?.toString() ?: ""),
            "_xq" to "",
            "xq_m" to xq,
            "jslx" to roomType,
            "xnxq" to "$xn,$xq",
            "selSYDW" to "",
            "selXQ" to campus,
            "selJSLX" to roomType,
            "selLF" to building,
            "selJSMC" to "",
            "selGS" to "2",
            "txtJSMC" to "",
        )
        return guard(
            http.postForm(
                "$activeBase/kbbp/dykb.jsikb_data.10027.jsp",
                form,
                referer = "$activeBase/student/dykb.jsikb.html",
            ).body,
        )
    }

    // ------------------------------------------------------------------
    // 学籍
    // ------------------------------------------------------------------

    @Throws(IOException::class)
    fun studentInfoHtml(): String {
        ensureSession()
        val url = "$activeBase/STU_BaseInfoAction.do?hidOption=InitData&menucode_current=JW13020101"
        return guard(http.get(url, "$activeBase/student/stu.xsxj.xjda.jbxx.html").body)
    }

    /** 毕业学分要求。 */
    @Throws(IOException::class)
    fun creditRequirementHtml(): String {
        ensureSession()
        return guard(
            http.postForm(
                "$activeBase/taglib/DataTable.jsp?tableId=6033",
                mapOf("sysf" to ""),
                referer = "$activeBase/student/pyfa.byxfyq.html",
            ).body,
        )
    }

    /** 用于在 WebView 中免密打开教务/门户页面。 */
    fun ssoUrl(service: String): String = auth.ssoUrl(service)

    private val home: String get() = "$activeBase/frame/homes.html"
}
