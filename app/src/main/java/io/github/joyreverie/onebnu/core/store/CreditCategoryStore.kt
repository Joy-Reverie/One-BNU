package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.data.model.CourseCategory

/** 用户手动指定的课程模块归类：课程号 → 模块，存本机。 */
class CreditCategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("onebnu_credit_categories", Context.MODE_PRIVATE)

    fun all(): Map<String, CourseCategory> =
        prefs.all.mapNotNull { (k, v) -> CourseCategory.byName(v as? String)?.let { k to it } }.toMap()

    fun set(courseCode: String, category: CourseCategory?) {
        prefs.edit().apply {
            if (category == null) remove(courseCode) else putString(courseCode, category.name)
        }.apply()
    }
}
