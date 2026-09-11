package io.github.joyreverie.onebnu.data.model

/**
 * GPA 换算。
 *
 * 教务系统本身给出的绩点（「有效成绩」视图里的学分绩点）是官方口径，应用优先展示它。
 * 这里的换算表用于两种情况：教务未给出绩点时的本地推算，以及用户想按别的口径对比时的换算。
 * 不同口径差异很大，因此界面上必须显示当前用的是哪一种，不能混为一谈。
 */
enum class GpaScale(val label: String, val description: String) {

    /** 教务系统返回的官方绩点，直接加权，不做任何本地换算。 */
    OFFICIAL("教务绩点", "直接使用教务系统给出的绩点，未给出的记录不参与计算"),

    /** 常见的「(分数-50)/10」线性制，60 分=1.0，100 分=5.0。 */
    LINEAR_5("五分制 (分数-50)/10", "60 分记 1.0，100 分记 5.0，低于 60 分记 0"),

    /** 北美常见 4.0 分段制。 */
    STANDARD_4("四分制 分段", "90+ 记 4.0，80+ 记 3.0，70+ 记 2.0，60+ 记 1.0"),

    /** 4.0 线性制：(分数-60)/10 + 1，上限 4.0，常用于出国成绩换算。 */
    LINEAR_4("四分制 (分数-60)/10+1", "60 分记 1.0，90 分及以上记 4.0");

    fun pointOf(grade: Grade): Double? {
        // 教务会把缓考临时显示成 0 分、甚至给出 0 绩点；它不是最终成绩，必须先排除。
        if (grade.isDeferredExam) return null
        if (this == OFFICIAL) return grade.officialPoint
        val s = grade.score ?: GradeScale.letterToScore(grade.scoreText) ?: return null
        return when (this) {
            OFFICIAL -> null
            LINEAR_5 -> if (s < 60) 0.0 else ((s - 50) / 10).coerceIn(0.0, 5.0)
            STANDARD_4 -> when {
                s >= 90 -> 4.0
                s >= 80 -> 3.0
                s >= 70 -> 2.0
                s >= 60 -> 1.0
                else -> 0.0
            }
            LINEAR_4 -> if (s < 60) 0.0 else (((s - 60) / 10) + 1).coerceIn(0.0, 4.0)
        }
    }
}

object GradeScale {

    /**
     * 等第成绩映射到百分制中值，用于参与加权计算。
     * 「通过 / 合格」这类没有区分度的记录返回 null —— 它们计学分但不该拉高或拉低 GPA。
     */
    fun letterToScore(text: String): Double? {
        val t = text.trim()
        t.toDoubleOrNull()?.let { return it }
        return when {
            t.startsWith("优秀") || t == "优" || t.equals("A", true) -> 95.0
            t.startsWith("良好") || t == "良" || t.equals("B", true) -> 85.0
            t.startsWith("中等") || t == "中" || t.equals("C", true) -> 75.0
            t.startsWith("及格") || t.startsWith("合格") || t == "及" || t.equals("D", true) -> 65.0
            t.startsWith("不及格") || t.startsWith("不合格") || t.equals("F", true) -> 45.0
            else -> null
        }
    }

    /** 通过 / 未通过这类不计入 GPA 但计入学分的记录。 */
    fun isPassFail(text: String): Boolean {
        val t = text.trim()
        return t == "通过" || t == "合格" || t == "P" || t == "免修" || t == "免考"
    }
}

/** 一段区间（某学期或全部）的 GPA 统计结果。 */
data class GpaSummary(
    val scale: GpaScale,
    /** 参与加权的学分。 */
    val gradedCredits: Double,
    /** 已获得的总学分（含通过制课程）。 */
    val earnedCredits: Double,
    val gpa: Double?,
    /** 加权平均分；没有可用分数时为 null。 */
    val weightedAverage: Double?,
    val courseCount: Int,
    /** 因无绩点/无分数被排除在 GPA 之外的课程数，界面上要如实说明。 */
    val excludedCount: Int,
    /** 因缓考被自动排除的课程数；其成绩即使暂记为 0 也不参与计算。 */
    val deferredCount: Int = 0,
    /** 用户在「计算范围」中手动取消勾选的、原本可计算的课程数。 */
    val manuallyExcludedCount: Int = 0,
)

object GpaCalculator {

    /** 当前口径下有可用绩点、且不是缓考的课程，才允许在手动范围中勾选。 */
    fun isEligible(grade: Grade, scale: GpaScale): Boolean =
        grade.credits > 0 && !grade.isDeferredExam && scale.pointOf(grade) != null

    /**
     * [manuallyExcludedCourseKeys] 是用户在本机取消勾选的课程。自动排除（缓考、无绩点等）
     * 永远优先，不能被手动选择重新纳入。
     */
    fun summarize(
        grades: List<Grade>,
        scale: GpaScale,
        manuallyExcludedCourseKeys: Set<String> = emptySet(),
    ): GpaSummary {
        var pointSum = 0.0
        var gradedCredits = 0.0
        var scoreSum = 0.0
        var scoreCredits = 0.0
        var earned = 0.0
        var excluded = 0
        var deferred = 0
        var manuallyExcluded = 0

        for (g in grades) {
            val passed = isPassed(g)
            if (passed) earned += g.credits

            val point = scale.pointOf(g)
            // 与 [isEligible] 同一条件，但复用上面已取到的绩点，避免重复换算。
            val eligible = g.credits > 0 && point != null
            val manuallySkipped = eligible && g.calculationKey in manuallyExcludedCourseKeys
            if (point != null && eligible && !manuallySkipped) {
                pointSum += point * g.credits
                gradedCredits += g.credits

                // 手动选择的「计算范围」同时约束 GPA 与加权均分，避免两组统计口径不一致。
                val s = g.score ?: GradeScale.letterToScore(g.scoreText)
                if (s != null) {
                    scoreSum += s * g.credits
                    scoreCredits += g.credits
                }
            } else {
                excluded++
                if (g.isDeferredExam) deferred++
                if (manuallySkipped) manuallyExcluded++
            }
        }

        return GpaSummary(
            scale = scale,
            gradedCredits = gradedCredits,
            earnedCredits = earned,
            gpa = if (gradedCredits > 0) pointSum / gradedCredits else null,
            weightedAverage = if (scoreCredits > 0) scoreSum / scoreCredits else null,
            courseCount = grades.size,
            excludedCount = excluded,
            deferredCount = deferred,
            manuallyExcludedCount = manuallyExcluded,
        )
    }

    /** 判定是否取得学分。 */
    fun isPassed(g: Grade): Boolean {
        if (g.isDeferredExam) return false
        if (GradeScale.isPassFail(g.scoreText)) return true
        val s = g.score ?: GradeScale.letterToScore(g.scoreText) ?: return false
        return s >= 60
    }

    /** 按学期分组统计，最近的学期排在前面。 */
    fun byTerm(
        grades: List<Grade>,
        scale: GpaScale,
        manuallyExcludedCourseKeys: Set<String> = emptySet(),
    ): List<Pair<String, GpaSummary>> =
        grades.groupBy { it.termLabel }
            .map { (label, list) -> label to summarize(list, scale, manuallyExcludedCourseKeys) }
            .sortedByDescending { it.first }
}
