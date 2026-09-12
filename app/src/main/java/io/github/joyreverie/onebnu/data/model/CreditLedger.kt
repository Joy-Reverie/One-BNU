package io.github.joyreverie.onebnu.data.model

/** 研究生课程的模块归类。顺序即界面上的展示顺序。 */
enum class CourseCategory(val label: String, val short: String) {
    PUBLIC_REQUIRED("公共必修", "公必"),
    PUBLIC_ELECTIVE("公共选修", "公选"),
    DEGREE_BASIC("学位基础", "基础"),
    DEGREE_MAJOR("学位专业", "专业"),
    EXPANSION("专业拓展", "拓展"),
    OTHER("其他", "其他");

    companion object {
        fun byName(name: String?): CourseCategory? = entries.firstOrNull { it.name == name }
    }
}

/** 归类来源；顺序同时表达可信度从高到低。 */
enum class CategorySource {
    MANUAL,
    SCHEDULE,
    MODULE,
    COURSE_CENTER,
    GRADE,
    SELECTION_RESULT,
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
 *  2. 课表本身带有官方课程类别时使用该类别；
 *  3. 教务培养方案对比页返回课程模块；
 *  4. 课程中心与成绩单里给了课程性质时使用对应类别；
 *  5. 网上选课结果只作为最后一个官方兜底，避免错误页面覆盖可靠数据；
 *  6. 都没有时先按课程名称识别公共课，再按课程号与学分推断。
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
            t.contains("其他") || t.contains("非学位") || t.contains("补修") -> CourseCategory.OTHER
            t.contains("公共") || t.contains("通识") || t.contains("全校") ->
                if (t.contains("必修")) CourseCategory.PUBLIC_REQUIRED else CourseCategory.PUBLIC_ELECTIVE
            t.contains("学位基础") || t.contains("专业基础") || t == "基础课" -> CourseCategory.DEGREE_BASIC
            t.contains("专业拓展") || t.contains("专业选修") || t.contains("专业任选") ||
                t.contains("自由选修") || t.contains("跨学科") ->
                CourseCategory.EXPANSION
            // 「专业必修 / 学位必修 / 学位专业」属于学位专业模块。
            t.contains("学位专业") || t.contains("专业必修") || t.contains("学位必修") ||
                t.contains("方向") -> CourseCategory.DEGREE_MAJOR
            // 没有公共/通识限定的「必修」「选修」在研究生教务中默认是专业模块。
            t.contains("必修") -> CourseCategory.DEGREE_MAJOR
            t.contains("选修") -> CourseCategory.EXPANSION
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
        val byModule = normalizeCategoryMap(modules)
        val bySelection = normalizeCategoryMap(selection)
        val byCourseCenter = normalizeCategoryMap(courseCenter)
        val byGradeCode = HashMap<String, CourseCategory>()
        val byGradeName = HashMap<String, CourseCategory>()
        grades.forEach { g ->
            fromGradeType(g.courseType)?.let { category ->
                codeAliases(g.courseCode).forEach { code -> byGradeCode.putIfAbsent(code, category) }
                normalizeLabel(g.courseName).takeIf { it.isNotBlank() }?.let { byGradeName[it] = category }
            }
        }
        val byManual = normalizeCategoryMap(manual)
        val terms = schedules
            .sortedWith(compareBy({ it.term.xn }, { it.term.xq }))
            .map { s ->
                val entries = s.courses
                    .sortedWith(compareBy<Course>({ CategoryRules.orderOf(it, byManual, bySelection, byCourseCenter, byModule, byGradeCode, byGradeName) }, { -it.credits }, { it.name }))
                        .map { c ->
                            val m = lookup(byManual, c.code)
                            val direct = fromGradeType(c.categoryLabel)
                            val center = lookup(byCourseCenter, c.code)
                            val module = lookup(byModule, c.code)
                            val g = lookup(byGradeCode, c.code) ?: byGradeName[normalizeLabel(c.name)]
                            val selectionCategory = lookup(bySelection, c.code)
                            when {
                                m != null -> LedgerEntry(s.term, c, m, CategorySource.MANUAL)
                                direct != null -> LedgerEntry(s.term, c, direct, CategorySource.SCHEDULE)
                                module != null -> LedgerEntry(s.term, c, module, CategorySource.MODULE)
                                center != null -> LedgerEntry(s.term, c, center, CategorySource.COURSE_CENTER)
                                g != null -> LedgerEntry(s.term, c, g, CategorySource.GRADE)
                                selectionCategory != null -> LedgerEntry(s.term, c, selectionCategory, CategorySource.SELECTION_RESULT)
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
    ): Int = (lookup(manual, c.code)
        ?: fromGradeType(c.categoryLabel)
        ?: lookup(modules, c.code)
        ?: lookup(courseCenter, c.code)
        ?: lookup(byGradeCode, c.code)
        ?: byGradeName[normalizeLabel(c.name)]
        ?: lookup(selection, c.code)
        ?: infer(c)).ordinal

    private fun normalizeCategoryMap(input: Map<String, CourseCategory>): Map<String, CourseCategory> =
        buildMap {
            input.forEach { (code, category) -> codeAliases(code).forEach { putIfAbsent(it, category) } }
        }

    private fun lookup(map: Map<String, CourseCategory>, code: String): CourseCategory? =
        codeAliases(code).firstNotNullOfOrNull { map[it] }

    private fun codeAliases(code: String): List<String> {
        val normalized = normalizeCode(code)
        if (normalized.isBlank()) return emptyList()
        val aliases = linkedSetOf(normalized)
        // 某些报表把课序号拼在课程号后面（如 CODE-01），课程本体仍应能匹配。
        Regex("^(.+)[-_]\\d{2}$").matchEntire(normalized)?.groupValues?.get(1)?.let { aliases += it }
        return aliases.toList()
    }
}
