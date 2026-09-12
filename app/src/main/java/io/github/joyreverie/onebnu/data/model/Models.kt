package io.github.joyreverie.onebnu.data.model

/** 学年学期。[xn] 如 "2026"，[xq] 0=秋季 1=春季 2=夏季。 */
data class Term(
    val xn: String,
    val xq: String,
    val name: String,
) {
    /** 教务下拉里使用的 "2026,0" 形式。 */
    val code: String get() = "$xn,$xq"

    companion object {
        fun parse(code: String, name: String): Term? {
            val parts = code.split(",")
            if (parts.size < 2) return null
            return Term(parts[0], parts[1], name)
        }
    }
}

/** 一门课在一周中的一次固定安排。 */
data class ClassSession(
    /** 实际上课周次集合，已展开区间与单双周。 */
    val weeks: Set<Int>,
    /** 周次原文，用于展示，如 "2-8" / "9,13,17" / "1-16(单)"。 */
    val weeksLabel: String,
    /** 1=周一 … 7=周日 */
    val dayOfWeek: Int,
    /** 节次区间，如 7..8。 */
    val startPeriod: Int,
    val endPeriod: Int,
    val location: String,
    /** 教室容量，教务在地点后以括号给出；无则为 null。 */
    val capacity: Int? = null,
) {
    val periodLabel: String
        get() = if (startPeriod == endPeriod) "第 $startPeriod 节" else "第 $startPeriod-$endPeriod 节"

    fun occursOn(week: Int) = weeks.contains(week)
}

/** 一门已选课程。 */
data class Course(
    val code: String,
    val name: String,
    val credits: Double,
    val hours: Int?,
    val classNo: String,
    val teachers: List<String>,
    val sessions: List<ClassSession>,
    /** 修读性质，如「初修」「重修」。 */
    val studyType: String = "",
    /** 主修 / 辅修 / 微专业。 */
    val majorType: String = "",
    /** 官方选课结果若直接返回课程性质/类别，保留原文供学分核算使用。 */
    val categoryLabel: String = "",
) {
    val teacherLabel: String get() = teachers.joinToString("、")
}

/** 课表整体。 */
data class Schedule(
    val term: Term,
    val studentId: String,
    val studentName: String,
    val className: String,
    val courses: List<Course>,
) {
    val totalCredits: Double get() = courses.sumOf { it.credits }

    /** 展开成 (课程, 单次安排) 便于按格渲染。 */
    fun slotsOn(week: Int, dayOfWeek: Int): List<Pair<Course, ClassSession>> =
        courses.flatMap { c -> c.sessions.map { c to it } }
            .filter { (_, s) -> s.dayOfWeek == dayOfWeek && s.occursOn(week) }
            .sortedBy { (_, s) -> s.startPeriod }

    val maxWeek: Int
        get() = courses.flatMap { it.sessions }.flatMap { it.weeks }.maxOrNull() ?: 20
}

/** 一条成绩记录。 */
data class Grade(
    val xn: String,
    val xq: String,
    val termLabel: String,
    val courseCode: String,
    val courseName: String,
    val credits: Double,
    /** 成绩原文：可能是数字，也可能是「优秀」「通过」「缓考」等。 */
    val scoreText: String,
    /** 能解析成百分制时的数值，否则为 null。 */
    val score: Double?,
    /** 教务给出的绩点；教务未给出时为 null，由本地按所选算法推算。 */
    val officialPoint: Double?,
    /** 课程性质／类别，如「学位必修课」。 */
    val courseType: String = "",
    /** 考核方式，如「考试」「考查」。 */
    val examType: String = "",
    /** 补考、重修等标识。 */
    val remark: String = "",
) {
    /**
     * 教务有时把缓考暂记为 0 分，同时在备注、考核方式或成绩状态列标出「缓考」。
     * 这个 0 不是一次实际考核成绩，不能拉低绩点或加权均分。
     */
    val isDeferredExam: Boolean
        get() = listOf(scoreText, remark, examType).any { it.replace(Regex("\\s"), "").contains("缓考") }

    /**
     * 手动选择计算范围时使用的稳定、本机键。只由学期和课程标识组成，不含姓名、学号等身份信息。
     * 用哈希保存到设置，避免把课程名称等原文作为偏好项键写入磁盘。
     */
    val calculationKey: String
        get() = listOf(xn.trim(), xq.trim(), courseCode.trim(), courseName.trim(), credits.toString())
            .joinToString("\u001F")
            .sha256()

    /** 是否具备可用于本地绩点换算的成绩；缓考即使显示为 0 也一律不算。 */
    val countable: Boolean
        get() = !isDeferredExam && (score != null || GradeScale.letterToScore(scoreText) != null)
}

private fun String.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** 一场考试。 */
data class Exam(
    val round: String,
    val courseName: String,
    val credits: String,
    val category: String,
    val examType: String,
    /** 考试时间原文，如 "2026-01-12 08:00-10:00"。 */
    val time: String,
    val location: String,
    val seat: String,
) {
    /** 从时间原文里取出日期部分，便于排序与倒计时。 */
    val date: String?
        get() = Regex("""\d{4}-\d{2}-\d{2}""").find(time)?.value
}

/** 教室及其占用情况。 */
data class Classroom(
    val building: String,
    val name: String,
    val capacity: Int?,
    val type: String,
    /** 该教室已被占用的时段。 */
    val busy: List<ClassSession>,
) {
    fun isFreeAt(week: Int, dayOfWeek: Int, periods: IntRange): Boolean = busy.none { s ->
        s.dayOfWeek == dayOfWeek && s.occursOn(week) &&
            s.startPeriod <= periods.last && s.endPeriod >= periods.first
    }
}

/** 校区 / 楼房 / 教室类型等下拉项。 */
data class Option(val code: String, val name: String)

/** 学籍基本信息中的一项。 */
data class InfoItem(val label: String, val value: String)

/** 学籍信息。教务返回的是 XML，字段很多且大量为空，这里只保留有值的。 */
data class StudentProfile(
    val name: String,
    val studentId: String,
    val gender: String,
    val department: String,
    val major: String,
    val className: String,
    val grade: String,
    /** 培养层次，如「硕士」「本科」。 */
    val level: String,
    /** 全部非空字段，按固定顺序，用于详情页逐条展示。 */
    val details: List<InfoItem>,
) {
    /** 头像占位用的首字。 */
    val initial: String get() = name.take(1).ifBlank { "京" }

    /** 「人工智能学院 · 计算机科学与技术」这类一行摘要。 */
    val summary: String
        get() = listOf(department, major).filter { it.isNotBlank() }.joinToString(" · ")
}
