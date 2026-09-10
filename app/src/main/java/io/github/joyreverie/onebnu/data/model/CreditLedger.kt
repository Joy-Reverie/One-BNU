package io.github.joyreverie.onebnu.data.model

/** 研究生课程的模块归类。顺序即界面上的展示顺序。 */
enum class CourseCategory(val label: String, val short: String) {
    PUBLIC_REQUIRED("公共必修", "公必"),
    PUBLIC_ELECTIVE("公共选修", "公选"),
    DEGREE_BASIC("学位基础课", "基础"),
    DEGREE_MAJOR("学位专业课", "专业"),
    EXPANSION("专业拓展课", "拓展"),
    OTHER("其他", "其他");

    companion object {
        fun byName(name: String?): CourseCategory? = entries.firstOrNull { it.name == name }
    }
}

/** 归类是怎么来的：用户手动指定 > 成绩单上的课程性质 > 按课程号与学分推断。 */
enum class CategorySource { MANUAL, GRADE, INFERRED }

data class LedgerEntry(
    val term: Term,
    val course: Course,
    val category: CourseCategory,
    val source: CategorySource,
) {
    /** 重修的课不重复计学分。 */
    val counted: Boolean get() = !course.studyType.contains("重修")
}

data class TermLedger(val term: Term, val entries: List<LedgerEntry>) {
    val total: Double get() = entries.filter { it.counted }.sumOf { it.course.credits }
    fun totalOf(c: CourseCategory): Double = entries.filter { it.counted && it.category == c }.sumOf { it.course.credits }
}

data class CreditLedger(val terms: List<TermLedger>) {
    val total: Double get() = terms.sumOf { it.total }
    fun totalOf(c: CourseCategory): Double = terms.sumOf { it.totalOf(c) }
    val entries: List<LedgerEntry> get() = terms.flatMap { it.entries }
}

/**
 * 归类规则。教务的选课课程表没有课程类别列，所以：
 *  1. 用户在应用里手动指定过的最优先；
 *  2. 成绩单里给了「课程性质」的照抄（成绩出来之后自动纠正推断）；
 *  3. 都没有时按课程号与学分推断：GRA 开头是研究生院开的公共课，按课名分必修 / 选修；
 *     院系开的课 3 学分及以上算学位基础课，其余算学位专业课。
 */
object CategoryRules {

    private val PUBLIC_REQUIRED_NAME = Regex(
        "英语|外语|思想|理论与实践|马克思|当代科技|社会思潮|自然辩证法|教育改革|伦理|学术规范|学术道德|中国特色|政治",
    )

    /** 成绩单「课程性质 / 课程类别」文字 → 模块；认不出返回 null。 */
    fun fromGradeType(type: String): CourseCategory? {
        val t = type.replace(Regex("\\s+"), "")
        if (t.isBlank()) return null
        return when {
            t.contains("公共") && (t.contains("必修") || t.contains("学位")) -> CourseCategory.PUBLIC_REQUIRED
            t.contains("公共") || t.contains("通识") || t.contains("全校") -> CourseCategory.PUBLIC_ELECTIVE
            t.contains("基础") -> CourseCategory.DEGREE_BASIC
            t.contains("拓展") || t.contains("自由") || t.contains("跨") || t.contains("任选") -> CourseCategory.EXPANSION
            t.contains("专业") || t.contains("学位") || t.contains("方向") -> CourseCategory.DEGREE_MAJOR
            t.contains("必修") -> CourseCategory.PUBLIC_REQUIRED
            t.contains("选修") -> CourseCategory.PUBLIC_ELECTIVE
            else -> null
        }
    }

    /** 没有任何依据时的推断。 */
    fun infer(course: Course): CourseCategory {
        val code = course.code.uppercase()
        if (code.startsWith("GRA")) {
            return if (PUBLIC_REQUIRED_NAME.containsMatchIn(course.name)) CourseCategory.PUBLIC_REQUIRED
            else CourseCategory.PUBLIC_ELECTIVE
        }
        return if (course.credits >= 3.0) CourseCategory.DEGREE_BASIC else CourseCategory.DEGREE_MAJOR
    }

    /**
     * 把各学期课表、成绩单与手动指定合成一份台账。
     * [manual] 键是课程号；[grades] 只取有课程性质的行。
     */
    fun build(
        schedules: List<Schedule>,
        grades: List<Grade>,
        manual: Map<String, CourseCategory>,
    ): CreditLedger {
        val byGrade = HashMap<String, CourseCategory>()
        grades.forEach { g -> fromGradeType(g.courseType)?.let { if (g.courseCode.isNotBlank()) byGrade[g.courseCode] = it } }
        val terms = schedules
            .sortedWith(compareBy({ it.term.xn }, { it.term.xq }))
            .map { s ->
                val entries = s.courses
                    .sortedWith(compareBy<Course>({ CategoryRules.orderOf(it, manual, byGrade) }, { -it.credits }, { it.name }))
                    .map { c ->
                        val m = manual[c.code]
                        val g = byGrade[c.code]
                        when {
                            m != null -> LedgerEntry(s.term, c, m, CategorySource.MANUAL)
                            g != null -> LedgerEntry(s.term, c, g, CategorySource.GRADE)
                            else -> LedgerEntry(s.term, c, infer(c), CategorySource.INFERRED)
                        }
                    }
                TermLedger(s.term, entries)
            }
        return CreditLedger(terms)
    }

    private fun orderOf(c: Course, manual: Map<String, CourseCategory>, byGrade: Map<String, CourseCategory>): Int =
        (manual[c.code] ?: byGrade[c.code] ?: infer(c)).ordinal
}
