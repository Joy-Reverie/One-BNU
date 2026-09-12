package io.github.joyreverie.onebnu.data.parse

import io.github.joyreverie.onebnu.data.model.ClassSession
import io.github.joyreverie.onebnu.data.model.Classroom
import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.Exam
import io.github.joyreverie.onebnu.data.model.Grade
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.Schedule
import io.github.joyreverie.onebnu.data.model.StudentProfile
import io.github.joyreverie.onebnu.data.model.Term
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * KINGOSOFT 教务系统页面解析。
 *
 * 这些页面是服务端拼出来的报表 HTML，没有稳定的 class/id，
 * 因此一律按「表头文字」定位列而不是按固定下标 —— 教务调整列顺序时不至于整片错位。
 */
object Parsers {

    private val WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )

    fun looksLikeLoginPage(html: String): Boolean =
        html.contains("name=\"lt\"") || html.contains("统一身份认证") && html.contains("loginForm")

    fun isUnauthorized(html: String): Boolean =
        html.contains("未授权访问") || html.contains("请更换账号登陆")

    // ------------------------------------------------------------------
    // 课表（列表视图 wsxk/xkjg.ckdgxsxdkchj_data10319.jsp）
    // ------------------------------------------------------------------

    /**
     * 列表视图比网格视图信息完整：带课程号、学分、学时、教师、以及可精确解析的
     * 「周次 星期[节次] 地点(容量)」串，因此课表一律以它为准。
     */
    fun parseSchedule(html: String, term: Term): Schedule {
        val doc = Jsoup.parse(html)
        val text = doc.text()

        val studentId = Regex("""学号[：:]\s*(\S+)""").find(text)?.groupValues?.get(1).orEmpty()
        val studentName = Regex("""姓名[：:]\s*(\S+)""").find(text)?.groupValues?.get(1).orEmpty()
        val className = Regex("""所在班级[：:]\s*(\S+)""").find(text)?.groupValues?.get(1).orEmpty()

        val table = pickDataTable(doc, listOf("课程", "学分")) ?: return Schedule(term, studentId, studentName, className, emptyList())
        val header = headerIndex(table)

        val iName = header.findAny("课程名", "]课程名", "课程")
        val iCredit = header.findAny("学分")
        val iHours = header.findAny("总学时", "学时")
        val iClassNo = header.findAny("上课班号", "班号")
        val iTeacher = header.findAny("任课教师", "教师")
        val iWhen = header.findAny("上课时间、地点", "上课时间", "时间、地点", "时间")
        val iStudy = header.findAny("修读性质", "修读")
        val iMajor = header.findAny("辅修标识", "辅修")
        val iCategory = header.findAny("课程类别", "课程性质", "课程模块", "类别")

        val courses = ArrayList<Course>()
        for (row in dataRows(table)) {
            val cells = row.select("td").map { it.cleanText() }
            if (cells.isEmpty()) continue
            val rawName = cells.getOrNull(iName).orEmpty()
            if (rawName.isBlank()) continue

            // 形如 "[AIS21158302]机器学习"
            val m = Regex("""^\[([^\]]+)]\s*(.*)$""").find(rawName)
            val code = m?.groupValues?.get(1).orEmpty()
            val name = (m?.groupValues?.get(2) ?: rawName).trim()
            if (name.isBlank()) continue

            val whenText = cells.getOrNull(iWhen).orEmpty()
            courses += Course(
                code = code,
                name = name,
                credits = cells.getOrNull(iCredit)?.toDoubleOrNull() ?: 0.0,
                hours = cells.getOrNull(iHours)?.filter { it.isDigit() }?.toIntOrNull(),
                classNo = cells.getOrNull(iClassNo).orEmpty(),
                teachers = cells.getOrNull(iTeacher).orEmpty()
                    .split(';', '；', '、', ',').map { it.trim() }.filter { it.isNotEmpty() },
                sessions = parseSessions(whenText),
                studyType = cells.getOrNull(iStudy).orEmpty(),
                majorType = cells.getOrNull(iMajor).orEmpty(),
                categoryLabel = cells.getOrNull(iCategory).orEmpty(),
            )
        }
        return Schedule(term, studentId, studentName, className, courses)
    }

    /**
     * 解析上课时间地点串，例如：
     *   "2-8周 二[7-8] 四117(60),9-16周 二[7-8] 四117(60)"
     *   "1-16周 三[1-4] 在线教学(400)"
     *   "9,13,17周 四[1-4] 京师大厦9406(120)"
     *   "2-16周(单) 五[3-4] 教七301"
     */
    /**
     * 不按逗号切段 —— 「9,13,17周」里的逗号和分段逗号无法靠前后文区分。
     * 改为在整串上全局匹配「周次 星期[节次] 地点」，地点吃到下一个逗号为止。
     */
    private val SESSION_RE = Regex(
        """(\d[\d,，\-]*)\s*周\s*(?:[(（]([单双])[)）])?\s*([一二三四五六日天])\s*""" +
            """[\[【]\s*(\d+)\s*(?:-\s*(\d+))?\s*节?\s*[]】]\s*([^,，]*)""",
    )

    fun parseSessions(raw: String): List<ClassSession> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<ClassSession>()
        for (m in SESSION_RE.findAll(raw)) {
            val weeksRaw = m.groupValues[1].trim(',', '，')
            val parity = m.groupValues[2]
            val day = WEEKDAY[m.groupValues[3][0]] ?: continue
            val start = m.groupValues[4].toIntOrNull() ?: continue
            val end = m.groupValues[5].toIntOrNull() ?: start
            var place = m.groupValues[6].trim()

            var capacity: Int? = null
            Regex("""[(（](\d+)[)）]\s*$""").find(place)?.let {
                capacity = it.groupValues[1].toIntOrNull()
                place = place.removeRange(it.range).trim()
            }

            out += ClassSession(
                weeks = expandWeeks(weeksRaw, parity),
                weeksLabel = weeksRaw + if (parity.isNotEmpty()) "($parity)" else "",
                dayOfWeek = day,
                startPeriod = start,
                endPeriod = end,
                location = place.ifBlank { "未安排地点" },
                capacity = capacity,
            )
        }
        return out
    }

    /** "2-8" → 2..8；"9,13,17" → {9,13,17}；parity 为「单」「双」时再过滤。 */
    fun expandWeeks(raw: String, parity: String = ""): Set<Int> {
        val weeks = sortedSetOf<Int>()
        for (part in raw.split(',', '，')) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = Regex("""^(\d+)\s*-\s*(\d+)$""").find(p)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                for (w in minOf(a, b)..maxOf(a, b)) weeks += w
            } else {
                p.toIntOrNull()?.let { weeks += it }
            }
        }
        return when (parity) {
            "单" -> weeks.filter { it % 2 == 1 }.toSortedSet()
            "双" -> weeks.filter { it % 2 == 0 }.toSortedSet()
            else -> weeks
        }
    }

    // ------------------------------------------------------------------
    // 成绩
    // ------------------------------------------------------------------

    fun parseGrades(html: String): List<Grade> {
        val doc = Jsoup.parse(html)
        val table = pickDataTable(doc, listOf("课程", "成绩")) ?: return emptyList()
        val header = headerIndex(table)

        val iXn = header.findAny("学年")
        val iXq = header.findAny("学期")
        val iCode = header.findAny("课程号", "课程代码", "课程编号")
        val iName = header.findAny("课程名", "课程")
        val iCredit = header.findAny("学分")
        val iScore = header.findAny("总评成绩", "成绩", "最终成绩")
        val iPoint = header.findAny("学分绩点", "绩点")
        val iType = header.findAny("课程性质", "课程属性", "课程类别", "类别")
        val iExam = header.findAny("考核方式", "考试性质")
        val iRemark = header.findAny("备注", "重修标记", "补考")
        // 不同校区/报表会把「缓考」放在备注，也可能单列成成绩状态或成绩说明。
        // 合并进 remark，领域层只需统一判断 Grade.isDeferredExam。
        val iScoreStatus = header.findAny("缓考", "成绩状态", "成绩标志", "成绩说明", "考试状态")

        val out = ArrayList<Grade>()
        for (row in dataRows(table)) {
            val cells = row.select("td").map { it.cleanText() }
            if (cells.isEmpty()) continue
            val rawName = cells.getOrNull(iName).orEmpty()
            if (rawName.isBlank()) continue

            val m = Regex("""^\[([^\]]+)]\s*(.*)$""").find(rawName)
            val code = cells.getOrNull(iCode).orEmpty().ifBlank { m?.groupValues?.get(1).orEmpty() }
            val name = (m?.groupValues?.get(2) ?: rawName).trim()
            if (name.isBlank() || name == "合计") continue

            val scoreText = cells.getOrNull(iScore).orEmpty()
            val xn = cells.getOrNull(iXn).orEmpty()
            val xq = cells.getOrNull(iXq).orEmpty()

            out += Grade(
                xn = xn,
                xq = xq,
                termLabel = buildTermLabel(xn, xq),
                courseCode = code,
                courseName = name,
                credits = cells.getOrNull(iCredit)?.toDoubleOrNull() ?: 0.0,
                scoreText = scoreText,
                score = scoreText.trim().toDoubleOrNull(),
                officialPoint = cells.getOrNull(iPoint)?.toDoubleOrNull(),
                courseType = cells.getOrNull(iType).orEmpty(),
                examType = cells.getOrNull(iExam).orEmpty(),
                remark = listOf(cells.getOrNull(iRemark).orEmpty(), cells.getOrNull(iScoreStatus).orEmpty())
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(" · "),
            )
        }
        return out
    }

    /**
     * 解析「学业成绩与培养方案对比」报表（tableId=5327008）。
     * 该报表在成绩尚未发布时仍可能有课程模块，正好用于学分归类；
     * 返回课程号到模块的映射，空表时返回空映射。
     */
    fun parseCourseModules(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val table = pickDataTable(doc, listOf("课程模块", "课程代码", "课程名称")) ?: return emptyMap()
        val header = headerIndex(table)
        val iModule = header.findAny("课程模块", "模块")
        val iCode = header.findAny("课程代码", "课程号", "代码")
        if (iModule < 0 || iCode < 0) return emptyMap()
        return dataRows(table).mapNotNull { row ->
            val cells = row.select("td").map { it.cleanText() }
            val module = cells.getOrNull(iModule).orEmpty()
            val code = cells.getOrNull(iCode).orEmpty()
            if (module.isBlank() || code.isBlank() || module == "课程模块") null else code to module
        }.toMap()
    }

    /**
     * 解析网上选课「选课结果」等官方表格中的课程号 → 课程类别。
     * 页面版本会把列名写成「类别」「课程性质」或「课程模块」，因此只按表头定位。
     */
    fun parseCourseCategories(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { candidate ->
            val header = headerIndex(candidate)
            header.findAny("课程代码", "课程号", "代码", "课程") >= 0 &&
                header.findAny("课程类别", "课程性质", "课程模块", "类别", "类型") >= 0 &&
                candidate.select("tr").size >= 2
        } ?: return emptyMap()
        val header = headerIndex(table)
        val iCode = header.findAny("课程代码", "课程号", "代码", "课程")
        val iCategory = header.findAny("课程类别", "课程性质", "课程模块", "类别", "类型")
        if (iCode < 0 || iCategory < 0) return emptyMap()
        return dataRows(table).mapNotNull { row ->
            val cells = row.select("td").map { it.cleanText() }
            val rawCode = cells.getOrNull(iCode).orEmpty()
            val code = Regex("""\[([^\]]+)]""").find(rawCode)?.groupValues?.get(1)
                ?: rawCode.takeIf { it.matches(Regex("[A-Za-z0-9_-]{5,}")) }
                ?: return@mapNotNull null
            val category = cells.getOrNull(iCategory).orEmpty()
            if (category.isBlank() || category == "课程类别" || category == "课程性质") null
            else code to category
        }.toMap()
    }

    private fun buildTermLabel(xn: String, xq: String): String {
        if (xn.isBlank()) return "未分学期"
        val season = when (xq.trim()) {
            "0" -> "秋季学期"
            "1" -> "春季学期"
            "2" -> "夏季学期"
            else -> xq.trim()
        }
        val year = xn.trim()
        val next = year.toIntOrNull()?.plus(1)?.toString() ?: ""
        return if (next.isNotEmpty()) "$year-$next $season" else "$year $season"
    }

    // ------------------------------------------------------------------
    // 考试安排
    // ------------------------------------------------------------------

    fun parseExams(html: String): List<Exam> {
        val doc = Jsoup.parse(html)
        val table = pickDataTable(doc, listOf("考试")) ?: return emptyList()
        val header = headerIndex(table)

        val iRound = header.findAny("考试轮次名称", "轮次")
        val iName = header.findAny("课程")
        val iCredit = header.findAny("学分")
        val iCategory = header.findAny("类别")
        val iType = header.findAny("考核方式", "考核")
        val iTime = header.findAny("考试时间", "时间")
        val iPlace = header.findAny("考试地点", "地点")
        val iSeat = header.findAny("座位号", "座号")

        val out = ArrayList<Exam>()
        for (row in dataRows(table)) {
            val cells = row.select("td").map { it.cleanText() }
            if (cells.isEmpty()) continue
            val name = cells.getOrNull(iName).orEmpty()
            if (name.isBlank()) continue
            out += Exam(
                round = cells.getOrNull(iRound).orEmpty(),
                courseName = name.replace(Regex("""^\[[^\]]+]\s*"""), ""),
                credits = cells.getOrNull(iCredit).orEmpty(),
                category = cells.getOrNull(iCategory).orEmpty(),
                examType = cells.getOrNull(iType).orEmpty(),
                time = cells.getOrNull(iTime).orEmpty(),
                location = cells.getOrNull(iPlace).orEmpty(),
                seat = cells.getOrNull(iSeat).orEmpty(),
            )
        }
        return out
    }

    // ------------------------------------------------------------------
    // 教室课表（用于推算空闲教室）
    // ------------------------------------------------------------------

    /**
     * 解析 `kbbp/dykb.jsikb_data.10027.jsp` 的列表输出。
     *
     * 页面为每间教室输出「一组说明 div + 一张课程表格」。
     * 教室名写在表格**之前**的 `<label id="lbl_classroom">` 所在的 div 里，
     * 因此以这些 label 为锚点，再向后找紧邻的表格。
     */
    fun parseClassrooms(html: String): List<Classroom> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Classroom>()

        for (label in doc.select("label#lbl_classroom")) {
            // label 所在 div 的父级同时含有「楼房 / 教室类型 / 教室」三块
            val wrapper = label.parent()?.parent() ?: continue
            val info = wrapper.text()

            val roomRaw = Regex("""教室\s*[：:]\s*(\S+)""").find(info)?.groupValues?.get(1) ?: continue
            var room = roomRaw
            var capacity: Int? = null
            Regex("""[(（](\d+)[)）]\s*$""").find(roomRaw)?.let {
                capacity = it.groupValues[1].toIntOrNull()
                room = roomRaw.removeRange(it.range)
            }
            val building = Regex("""楼房\s*[：:]\s*(\S+)""").find(info)?.groupValues?.get(1).orEmpty()
            val type = Regex("""教室类型\s*[：:]\s*(\S+)""").find(info)?.groupValues?.get(1).orEmpty()

            // 向后找这间教室对应的课程表格
            var el = wrapper.nextElementSibling()
            while (el != null && !el.tagName().equals("table", ignoreCase = true)) {
                el = el.nextElementSibling()
            }
            val table = el ?: doc.select("table").firstOrNull { it.text().contains("节次") }
            val busy = if (table != null) parseRoomBusy(table) else emptyList()

            out += Classroom(building, room, capacity, type, busy)
        }
        return out
    }

    /** 教室课表表格：每行一门课，「周次」「节次」两列决定占用时段。 */
    private fun parseRoomBusy(table: Element): List<ClassSession> {
        val header = headerIndex(table)
        val iName = header.findAny("课程")
        val iTeacher = header.findAny("教师")
        val iWeeks = header.findAny("周次")
        val iPeriods = header.findAny("节次")
        if (iWeeks < 0 || iPeriods < 0) return emptyList()

        val busy = ArrayList<ClassSession>()
        for (row in dataRows(table)) {
            val cells = row.select("td").map { it.cleanText() }
            val weeksRaw = cells.getOrNull(iWeeks).orEmpty()
            val periodRaw = cells.getOrNull(iPeriods).orEmpty()
            if (weeksRaw.isBlank() || periodRaw.isBlank()) continue
            val name = cells.getOrNull(iName).orEmpty().replace(Regex("""^\[[^\]]+]\s*"""), "")
            val teacher = cells.getOrNull(iTeacher).orEmpty()

            // 节次形如 "二[7-8节]"，一行内可能有多段
            for (pm in Regex("""([一二三四五六日天])\s*[\[【]\s*(\d+)\s*(?:-\s*(\d+))?\s*节?\s*[]】]""").findAll(periodRaw)) {
                val day = WEEKDAY[pm.groupValues[1][0]] ?: continue
                val start = pm.groupValues[2].toIntOrNull() ?: continue
                val end = pm.groupValues[3].toIntOrNull() ?: start
                busy += ClassSession(
                    weeks = expandWeeks(weeksRaw),
                    weeksLabel = weeksRaw,
                    dayOfWeek = day,
                    startPeriod = start,
                    endPeriod = end,
                    location = listOf(name, teacher).filter { it.isNotBlank() }.joinToString(" · "),
                )
            }
        }
        return busy
    }

    // ------------------------------------------------------------------
    // 学籍信息
    // ------------------------------------------------------------------

    /**
     * 学籍信息。
     *
     * `STU_BaseInfoAction.do` 返回的是 XML（`<info><xm>姓名</xm>…</info>`）而不是 HTML 表格，
     * 字段名是拼音缩写且绝大多数为空，这里按白名单取有值的并翻译成中文标签。
     */
    private val STUDENT_FIELDS: List<Pair<String, String>> = listOf(
        "xm" to "姓名",
        "yhxh" to "学号",
        "xb" to "性别",
        "yxb" to "院系",
        "zymc" to "专业",
        "lqzy" to "录取专业",
        "fxzymc" to "辅修专业",
        "bjmc" to "班级",
        "rxnj" to "入学年级",
        "zsjj" to "入学季节",
        "pycc" to "培养层次",
        "xz" to "学制",
        "pydx" to "培养对象",
        "kslb" to "考生类别",
        "rxfs" to "入学方式",
        "csrq" to "出生日期",
        "mz" to "民族",
        "zzmm" to "政治面貌",
        "jg" to "籍贯",
        "syd" to "生源地",
        "whcd" to "文化程度",
        "dh" to "联系电话",
        "dzyx" to "电子邮箱",
        "txdz" to "通讯地址",
        "yzbm" to "邮政编码",
        "ss_mc" to "宿舍",
        "ssdh" to "宿舍电话",
        "jkzk" to "健康状况",
        "sydw" to "所属单位",
    )

    /** 不展示的敏感字段（身份证、准考证号等），即便教务返回了也不显示。 */
    private val SENSITIVE = setOf("sfzjh", "gkzkzh", "gkksh")

    fun parseStudentProfile(xml: String): StudentProfile? {
        fun tag(name: String): String =
            Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

        val name = tag("xm")
        val id = tag("yhxh").ifBlank { tag("xh") }
        if (name.isBlank() && id.isBlank()) return null

        val details = STUDENT_FIELDS
            .filterNot { it.first in SENSITIVE }
            .mapNotNull { (key, label) ->
                val v = tag(key)
                if (v.isBlank()) null else InfoItem(label, v)
            }

        return StudentProfile(
            name = name,
            studentId = id,
            gender = tag("xb"),
            department = tag("yxb"),
            major = tag("zymc").ifBlank { tag("lqzy") },
            className = tag("bjmc"),
            grade = tag("rxnj"),
            level = tag("pycc"),
            details = details,
        )
    }

    /**
     * 毕业学分要求：`DataTable.jsp?tableId=6033`，列为「序号 / 项目 / 学分」。
     * 这是一张普通数据表，不能用 label/value 配对的方式解析 ——
     * 那样会把表头「序号」「项目」当成一条记录。
     */
    fun parseCreditRequirements(html: String): List<InfoItem> {
        val doc = Jsoup.parse(html)
        val table = pickDataTable(doc, listOf("项目", "学分")) ?: return emptyList()
        val header = headerIndex(table)
        val iItem = header.findAny("项目")
        val iCredit = header.findAny("学分")
        if (iItem < 0 || iCredit < 0) return emptyList()

        val out = ArrayList<InfoItem>()
        for (row in dataRows(table)) {
            val cells = row.select("td").map { it.cleanText() }
            val name = cells.getOrNull(iItem).orEmpty()
            val credit = cells.getOrNull(iCredit).orEmpty()
            if (name.isBlank() || name == "项目") continue
            out += InfoItem(name, credit.ifBlank { "—" })
        }
        return out
    }

    /** 学籍页是 label/value 交替的表格。 */
    fun parseInfoTable(html: String): List<InfoItem> {
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, String>()
        for (row in doc.select("tr")) {
            val cells = row.select("td, th").map { it.cleanText() }
            var i = 0
            while (i + 1 < cells.size) {
                val label = cells[i].trimEnd('：', ':')
                val value = cells[i + 1]
                if (label.isNotBlank() && label.length <= 12 && value.isNotBlank() && !value.contains("　　")) {
                    out.putIfAbsent(label, value)
                }
                i += 2
            }
        }
        return out.map { InfoItem(it.key, it.value) }
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    /** 选出包含全部关键词的最大表格 —— 报表页往往嵌套多层布局表格。 */
    private fun pickDataTable(doc: Document, keywords: List<String>): Element? =
        doc.select("table")
            .filter { table -> keywords.all { table.text().contains(it) } }
            .filter { it.select("tr").size >= 2 }
            .minByOrNull { it.select("table").size }

    private class Header(private val names: List<String>) {
        /** 依次尝试候选词，返回第一个命中的列下标；都不中返回 -1。 */
        fun findAny(vararg candidates: String): Int {
            for (c in candidates) {
                val exact = names.indexOfFirst { it == c }
                if (exact >= 0) return exact
                val partial = names.indexOfFirst { it.contains(c) }
                if (partial >= 0) return partial
            }
            return -1
        }
    }

    /** 取表头行：优先 th，没有则用第一行 td。 */
    private fun headerIndex(table: Element): Header {
        val ths = table.select("tr").firstOrNull { it.select("th").isNotEmpty() }?.select("th")
        val cells = ths ?: table.select("tr").firstOrNull()?.select("td")
        return Header(cells?.map { it.cleanText().replace(Regex("\\s"), "") } ?: emptyList())
    }

    /** 数据行：跳过表头以及明显的合计/提示行。 */
    private fun dataRows(table: Element): List<Element> {
        val rows = table.select("tr")
        val headerRowIndex = rows.indexOfFirst { it.select("th").isNotEmpty() }
        val start = if (headerRowIndex >= 0) headerRowIndex + 1 else 1
        return rows.drop(start).filter { row ->
            val tds = row.select("td")
            tds.isNotEmpty() && tds.none { it.hasAttr("colspan") && it.text().contains("没有检索到") }
        }
    }

    /** 教务页面里混用了 nbsp / ensp，统一成普通空格再 trim。 */
    private fun Element.cleanText(): String =
        text().replace('\u00A0', ' ').replace('\u2002', ' ')
            .replace("&ensp;", " ").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ").trim()

    /** 教务在没有数据时会返回「没有检索到记录!」。 */
    fun isEmptyResult(html: String): Boolean =
        html.contains("没有检索到记录") || html.contains("暂无数据")
}
