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

/** 归类来源；顺序同时表达可信度从高到低。 */
enum class CategorySource {
    MANUAL,
    SELECTION_RESULT,
    COURSE_CENTER,
    MODULE,
    GRADE,
    INFERRED,
}

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
 *  2. 网上选课结果中的官方课程类别；
 *  3. 课程中心 / 教务培养方案对比页返回课程模块；
 *  4. 成绩单里给了「课程性质」的照抄；
 *  5. 都没有时先按课程名称识别公共课，再按课程号与学分推断。
 *
 * 珠海校区的公共课课程号并不统一使用 GRA（例如政治理论课可能是 MAR），
 * 因此不能把「GRA 才是公共课」当成硬规则。
 */
object CategoryRules {

    private val PUBLIC_REQUIRED_NAME = Regex(
        "英语|外语|思想|理论与实践|马克思|当代科技|社会思潮|自然辩证法|教育改革|伦理|学术规范|学术道德|中国特色|政治|形势与政策|国家安全教育",
    )
    private val PUBLIC_ELECTIVE_NAME = Regex(
        "公共选修|通识选修|全校选修|体育|美育|艺术鉴赏|心理健康|创新创业|劳动教育|就业指导|职业发展|信息素养|第二外语",
    )

    /** 教务「课程模块」或成绩单「课程性质 / 课程类别」文字 → 模块；认不出返回 null。 */
    fun fromGradeType(type: String): CourseCategory? {
        val t = normalizeLabel(type)
        if (t.isBlank()) return null
        return when {
            t.contains("公共必修") || t.contains("公共学位必修") ||
                t.contains("通识必修") || t.contains("全校必修") -> CourseCategory.PUBLIC_REQUIRED
            t.contains("公共") || t.contains("通识") || t.contains("全校") ->
                if (t.contains("必修")) CourseCategory.PUBLIC_REQUIRED else CourseCategory.PUBLIC_ELECTIVE
            t.contains("学位基础") || t.contains("专业基础") || t == "基础课" -> CourseCategory.DEGREE_BASIC
            t.contains("拓展") || t.contains("自由选修") || t.contains("跨学科") || t.contains("专业任选") ->
                CourseCategory.EXPANSION
            // 「专业必修 / 专业选修 / 学位专业课」都属于专业模块，而非公共必修。
            t.contains("专业") || t.contains("学位") || t.contains("方向") -> CourseCategory.DEGREE_MAJOR
            t.contains("必修") -> CourseCategory.PUBLIC_REQUIRED
            t.contains("选修") -> CourseCategory.PUBLIC_ELECTIVE
            else -> null
        }
    }

    /** 没有任何依据时的推断；公共课名称判断必须独立于课程号前缀。 */
    fun infer(course: Course): CourseCategory {
        val name = normalizeLabel(course.name)
        if (PUBLIC_REQUIRED_NAME.containsMatchIn(name)) return CourseCategory.PUBLIC_REQUIRED
        if (PUBLIC_ELECTIVE_NAME.containsMatchIn(name)) return CourseCategory.PUBLIC_ELECTIVE
        val code = normalizeCode(course.code)
        if (code.startsWith("GRA")) return CourseCategory.PUBLIC_ELECTIVE
        return if (course.credits >= 3.0) CourseCategory.DEGREE_BASIC else CourseCategory.DEGREE_MAJOR
    }

    fun normalizeCode(code: String): String = code
        .replace(Regex("[\\[\\]\\s]"), "")
        .uppercase()

    private fun normalizeLabel(value: String): String = value
        .replace(Regex("\\s+"), "")
        .replace('（', '(')
        .replace('）', ')')

    /**
     * 把各学期课表、成绩单与手动指定合成一份台账。
     * [modules] 是教务培养方案对比页给出的「课程号 → 课程模块」；[manual] 键是课程号。
     */
    fun build(
        schedules: List<Schedule>,
        grades: List<Grade>,
        manual: Map<String, CourseCategory>,
        modules: Map<String, CourseCategory> = emptyMap(),
        selection: Map<String, CourseCategory> = emptyMap(),
        courseCenter: Map<String, CourseCategory> = emptyMap(),
    ): CreditLedger {
        val byModule = modules.mapKeys { normalizeCode(it.key) }
        val bySelection = selection.mapKeys { normalizeCode(it.key) }
        val byCourseCenter = courseCenter.mapKeys { normalizeCode(it.key) }
        val byGradeCode = HashMap<String, CourseCategory>()
        val byGradeName = HashMap<String, CourseCategory>()
        grades.forEach { g ->
            fromGradeType(g.courseType)?.let { category ->
                normalizeCode(g.courseCode).takeIf { it.isNotBlank() }?.let { byGradeCode[it] = category }
                normalizeLabel(g.courseName).takeIf { it.isNotBlank() }?.let { byGradeName[it] = category }
            }
        }
        val byManual = manual.mapKeys { normalizeCode(it.key) }
        val terms = schedules
            .sortedWith(compareBy({ it.term.xn }, { it.term.xq }))
            .map { s ->
                val entries = s.courses
                    .sortedWith(compareBy<Course>({ CategoryRules.orderOf(it, byManual, bySelection, byCourseCenter, byModule, byGradeCode, byGradeName) }, { -it.credits }, { it.name }))
                        .map { c ->
                            val code = normalizeCode(c.code)
                            val m = byManual[code]
                            val selectionCategory = bySelection[code]
                                ?: fromGradeType(c.categoryLabel)
                            val center = byCourseCenter[code]
                            val module = byModule[code]
                            val g = byGradeCode[code] ?: byGradeName[normalizeLabel(c.name)]
                            when {
                                m != null -> LedgerEntry(s.term, c, m, CategorySource.MANUAL)
                                selectionCategory != null -> LedgerEntry(s.term, c, selectionCategory, CategorySource.SELECTION_RESULT)
                                center != null -> LedgerEntry(s.term, c, center, CategorySource.COURSE_CENTER)
                                module != null -> LedgerEntry(s.term, c, module, CategorySource.MODULE)
                                g != null -> LedgerEntry(s.term, c, g, CategorySource.GRADE)
                            else -> LedgerEntry(s.term, c, infer(c), CategorySource.INFERRED)
                        }
                    }
                TermLedger(s.term, entries)
            }
        return CreditLedger(terms)
    }

    private fun orderOf(
        c: Course,
        manual: Map<String, CourseCategory>,
        selection: Map<String, CourseCategory>,
        courseCenter: Map<String, CourseCategory>,
        modules: Map<String, CourseCategory>,
        byGradeCode: Map<String, CourseCategory>,
        byGradeName: Map<String, CourseCategory>,
    ): Int = (manual[normalizeCode(c.code)]
        ?: selection[normalizeCode(c.code)]
        ?: fromGradeType(c.categoryLabel)
        ?: courseCenter[normalizeCode(c.code)]
        ?: modules[normalizeCode(c.code)]
        ?: byGradeCode[normalizeCode(c.code)]
        ?: byGradeName[normalizeLabel(c.name)]
        ?: infer(c)).ordinal
}
