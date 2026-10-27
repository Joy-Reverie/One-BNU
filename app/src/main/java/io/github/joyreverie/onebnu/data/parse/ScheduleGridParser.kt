package io.github.joyreverie.onebnu.data.parse

import io.github.joyreverie.onebnu.data.model.Course
import io.github.joyreverie.onebnu.data.model.TeacherWeeks
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 「我的课表 → 按课表方式显示」（`student/wsxk.xskcb10319.jsp`）的解析。
 *
 * 列表视图把一门课的老师写成「王明;李华」，分不出谁上哪几周；网格视图每格写的是
 * 「课程 + 老师 + 周次 + 地点」，正好补上这一层。课程本身仍以列表视图为准，这里只负责
 * 把「谁在哪天上哪几周」挂回列表视图解析出的 [Course]。
 *
 * 网格的排版各校区、各版本不尽相同，所以不按位置取字段，而是拿已知的词去认：
 * 课程名 / 课程号做锚点把一格切成若干段，段里出现的、本来就在这门课教师名单上的名字算老师，
 * 「…周」算周次；表头的「星期一 … 星期日」决定这一列是周几。认不出来的一律放弃，
 * 交给 [Course.teachersOn] 退回整门课的教师名单 —— 最差也就是和以前一样。
 */
object ScheduleGridParser {

    /** 课表缓存里列表视图与网格视图拼在一起存，用它隔开；旧缓存没有它，照常只有列表。 */
    const val SEPARATOR = "\n<!--ONEBNU:SCHEDULE-GRID-->\n"

    fun join(list: String, grid: String?): String = if (grid.isNullOrBlank()) list else list + SEPARATOR + grid

    fun split(text: String): Pair<String, String?> {
        val i = text.indexOf(SEPARATOR)
        return if (i < 0) text to null else text.substring(0, i) to text.substring(i + SEPARATOR.length)
    }

    /**
     * 网格里认出来的一段：第 [course] 门课在 [day] 的 [periods] 节由 [teachers] 上 [weeks]。
     * 认不出的星期、节次为 null；没写周次时 [weeks] 为 null。
     */
    data class Entry(
        val course: Int,
        val day: Int?,
        val weeks: Set<Int>?,
        val teachers: List<String>,
        val periods: IntRange? = null,
    )

    private val WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7,
    )
    private val DAY_HEADER = Regex("""^(?:星期|周|礼拜)?([一二三四五六日天])$""")

    /** 左侧节次格：「第1节」「第一节」「1-2节」「第十一节 18:00」，或者单独一个数字。 */
    private val PERIOD_CELL = Regex("""^第?\s*([0-9一二三四五六七八九十]{1,3})\s*(?:[-－~～至]\s*第?\s*([0-9一二三四五六七八九十]{1,3}))?\s*节""")
    private val PERIOD_NUMBER = Regex("""^(\d{1,2})(?:\D|$)""")

    /**
     * 「2-8周」「9,13,17周」「1-16周(单)」「1-15单周」「第3-4周」都认；不吃「第 7-8 节」与「周二」。
     * 组 1 周次，组 2/3/4 是写在不同位置的单双周。
     */
    private val WEEK_TOKEN = Regex(
        """(\d+(?:\s*[-－~～,，、]\s*\d+)*)\s*(?:[(（]\s*([单双])\s*[)）])?\s*([单双])?\s*周\s*(?:[(（]\s*([单双])\s*[)）])?""",
    )

    fun parse(html: String, courses: List<Course>): List<Entry> {
        if (courses.isEmpty() || html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        val out = ArrayList<Entry>()
        // 有的版本每格里再套一层小表格：外层表认星期，内层表的段落没有星期，
        // 两边认出的是同一件事，[attach] 优先用带星期的，重复无妨
        for (table in doc.select("table")) out += parseTable(table, courses)
        if (out.isEmpty()) {
            // 没有表格或表头认不出：退一步在整页文字里认，只是不知道星期几
            out += entries(doc.body()?.let(::cellText).orEmpty(), null, null, courses)
        }
        return out
    }

    private class Placed(val cell: Element, val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

    private fun parseTable(table: Element, courses: List<Course>): List<Entry> {
        val rows = table.select("tr").toList().filter { it.closest("table") == table }
        // 按 rowspan / colspan 展开成矩阵，才知道每一格落在第几列、第几行
        val taken = HashMap<Int, MutableSet<Int>>()
        val placed = ArrayList<Placed>()
        rows.forEachIndexed { r, tr ->
            var col = 0
            for (cell in tr.children().toList().filter { it.tagName() == "td" || it.tagName() == "th" }) {
                val used = taken.getOrPut(r) { HashSet() }
                while (col in used) col++
                val rowSpan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 50) ?: 1
                val colSpan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 20) ?: 1
                for (dr in 0 until rowSpan) {
                    val set = taken.getOrPut(r + dr) { HashSet() }
                    for (dc in 0 until colSpan) set += col + dc
                }
                placed += Placed(cell, r, col, rowSpan, colSpan)
                col += colSpan
            }
        }

        // 表头：同一行里至少五格是「星期一」这类
        val dayOfCol = HashMap<Int, Int>()
        val headerCells = HashSet<Element>()
        placed.groupBy { it.row }.toSortedMap().values.forEach { cells ->
            if (dayOfCol.isNotEmpty()) return@forEach
            val hits = cells.mapNotNull { p ->
                DAY_HEADER.find(p.cell.text().replace(Regex("""\s"""), ""))?.let { p to WEEKDAY.getValue(it.groupValues[1][0]) }
            }
            if (hits.size >= 5) {
                hits.forEach { (p, day) ->
                    for (c in p.col until p.col + p.colSpan) dayOfCol[c] = day
                    headerCells += p.cell
                }
            }
        }

        // 节次：星期列左边的格子写着第几节，记下每一行对应的节次
        val firstDayCol = dayOfCol.keys.minOrNull()
        val periodsOfRow = HashMap<Int, IntRange>()
        if (firstDayCol != null) {
            placed.filter { it.col < firstDayCol && it.cell !in headerCells }.forEach { p ->
                val range = periodRange(p.cell.text().trim()) ?: return@forEach
                // 「上午」这类跨好几行的格子没有节次，不会走到这里；一格写一节、跨一行最常见
                if (p.rowSpan == 1) {
                    periodsOfRow[p.row] = periodsOfRow[p.row]?.let { minOf(it.first, range.first)..maxOf(it.last, range.last) } ?: range
                } else {
                    for (dr in 0 until p.rowSpan) periodsOfRow.putIfAbsent(p.row + dr, range)
                }
            }
        }

        val out = ArrayList<Entry>()
        for (p in placed) {
            if (p.cell in headerCells) continue
            val covered = (p.row until p.row + p.rowSpan).mapNotNull { periodsOfRow[it] }
            val periods = if (covered.isEmpty()) null else covered.minOf { it.first }..covered.maxOf { it.last }
            out += entries(cellText(p.cell), dayOfCol[p.col], periods, courses)
        }
        return out
    }

    private fun periodRange(text: String): IntRange? {
        PERIOD_CELL.find(text)?.let { m ->
            val a = chineseNumber(m.groupValues[1]) ?: return null
            val b = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(::chineseNumber) ?: a
            return if (a in 1..20 && b in a..20) a..b else null
        }
        return PERIOD_NUMBER.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..20 }?.let { it..it }
    }

    /** 「7」「七」「十一」「十二」。 */
    private fun chineseNumber(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        return when {
            s == "十" -> 10
            s.length == 1 -> digits.indexOf(s[0]).takeIf { it >= 0 }?.plus(1)
            s.length == 2 && s[0] == '十' -> digits.indexOf(s[1]).takeIf { it >= 0 }?.plus(11)
            else -> null
        }
    }

    /** 格子的可见文字，再补上 title 提示里的内容（有的版本把详情放在悬停提示里）。 */
    private fun cellText(cell: Element): String = buildString {
        append(cell.text())
        cell.select("[title]").forEach { append(' ').append(it.attr("title")) }
    }

    /** 以课程名 / 课程号为锚点把一段文字切开，各段里认老师与周次。 */
    internal fun entries(text: String, day: Int?, periods: IntRange?, courses: List<Course>): List<Entry> {
        if (text.isBlank()) return emptyList()
        data class Anchor(val start: Int, val end: Int, val course: Int)

        val found = ArrayList<Anchor>()
        courses.forEachIndexed { i, c ->
            listOf(c.name, c.code).filter { it.length >= 2 }.forEach { key ->
                var from = 0
                while (true) {
                    val at = text.indexOf(key, from)
                    if (at < 0) break
                    found += Anchor(at, at + key.length, i)
                    from = at + key.length
                }
            }
        }
        // 「机器学习」也是「机器学习实验」的一部分：被别门课更长的锚点盖住的不算
        val anchors = found
            .filterNot { a -> found.any { b -> b.course != a.course && b.start <= a.start && b.end >= a.end && b.end - b.start > a.end - a.start } }
            .sortedBy { it.start }
        if (anchors.isEmpty()) return emptyList()

        val out = ArrayList<Entry>()
        var k = 0
        while (k < anchors.size) {
            val course = anchors[k].course
            // 同一门课紧挨着的锚点（「[课程号]课程名」）并成一段
            var next = k + 1
            while (next < anchors.size && anchors[next].course == course &&
                WEEK_TOKEN.find(text.substring(anchors[k].start, anchors[next].start)) == null
            ) next++
            val end = if (next < anchors.size) anchors[next].start else text.length
            val chunk = text.substring(anchors[k].start, end)
            out += chunkEntries(chunk, course, courses[course].teachers, day, periods)
            k = next
        }
        return out
    }

    /**
     * 一段里的老师与周次。一段里只有一组时直接配对；一段里写了好几组
     * （「王明 2-8周 李华 9-16周」或「2-8周 王明 9-16周 李华」）时，老师与周次交替出现、个数相同，
     * 就按出现顺序两两配对；对不齐的不硬配，整段算作这几位老师合上这些周。
     */
    private fun chunkEntries(chunk: String, course: Int, roster: List<String>, day: Int?, periods: IntRange?): List<Entry> {
        val names = roster.filter { it.length >= 2 }
        val teacherHits = names.flatMap { name ->
            Regex(Regex.escape(name)).findAll(chunk).map { it.range.first to name }.toList()
        }.sortedBy { it.first }
        if (teacherHits.isEmpty()) return emptyList()
        val weekHits = WEEK_TOKEN.findAll(chunk).mapNotNull { m -> weeksOf(m)?.let { m.range.first to it } }.toList()

        val distinctTeachers = teacherHits.map { it.second }.distinct()
        if (teacherHits.size >= 2 && teacherHits.size == weekHits.size) {
            val merged = (teacherHits.map { it.first to true } + weekHits.map { it.first to false }).sortedBy { it.first }
            val alternates = merged.zipWithNext().all { (a, b) -> a.second != b.second }
            if (alternates) {
                return teacherHits.indices.map { i ->
                    Entry(course, day, weekHits[i].second, listOf(teacherHits[i].second), periods)
                }
            }
        }
        val weeks = if (weekHits.isEmpty()) null else weekHits.flatMap { it.second }.toSortedSet()
        return listOf(Entry(course, day, weeks, distinctTeachers, periods))
    }

    private fun weeksOf(m: MatchResult): Set<Int>? {
        val raw = m.groupValues[1].replace(Regex("""[－~～]"""), "-").replace('、', ',')
        val parity = listOf(m.groupValues[2], m.groupValues[3], m.groupValues[4]).firstOrNull { it.isNotEmpty() }.orEmpty()
        return Parsers.expandWeeks(raw, parity).takeIf { it.isNotEmpty() }
    }

    /**
     * 把网格里认出的老师挂到每次安排上。只有多位老师的课才需要；
     * 某一周谁都对不上就不记，那一周照旧显示全部老师。
     */
    fun attach(courses: List<Course>, entries: List<Entry>): List<Course> {
        if (entries.isEmpty()) return courses
        val byCourse = entries.groupBy { it.course }
        return courses.mapIndexed { i, course ->
            val mine = byCourse[i].orEmpty()
            if (course.teachers.size < 2 || mine.isEmpty()) return@mapIndexed course
            course.copy(
                sessions = course.sessions.map { s ->
                    // 先找同一天、同几节的；认不出节次就只看同一天；整页都认不出星期时才用不带星期的
                    val sameDay = mine.filter { it.day == s.dayOfWeek }
                    val sameSlot = sameDay.filter { e -> e.periods != null && e.periods.first <= s.endPeriod && e.periods.last >= s.startPeriod }
                    val candidates = sameSlot.ifEmpty { sameDay.filter { it.periods == null }.ifEmpty { sameDay } }
                        .ifEmpty { mine.filter { it.day == null } }
                    // 有写明周次的就只信写明的；不写周次的那段对哪一周都成立，会把别的老师也算进来
                    val pool = candidates.filter { it.weeks != null }.ifEmpty { candidates }
                    val perTeacher = LinkedHashMap<String, MutableSet<Int>>()
                    for (w in s.weeks.sorted()) {
                        pool.filter { it.weeks == null || w in it.weeks }
                            .flatMap { it.teachers }
                            .distinct()
                            .forEach { perTeacher.getOrPut(it) { sortedSetOf() } += w }
                    }
                    val teachers = course.teachers.mapNotNull { name -> perTeacher[name]?.let { TeacherWeeks(name, it) } }
                    // 每一周都是全体老师，等于什么也没说，不记
                    val informative = teachers.size < course.teachers.size || teachers.any { it.weeks != s.weeks }
                    if (informative) s.copy(teachers = teachers) else s
                },
            )
        }
    }
}
