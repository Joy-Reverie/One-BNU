package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.data.model.InfoItem
import io.github.joyreverie.onebnu.data.model.Option
import io.github.joyreverie.onebnu.data.model.StudentProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 教务数据的按校区离线快照。
 *
 * 快照只放在应用私有目录，账号切换时由运行时清空；文件名只包含固定前缀和哈希，
 * 不把学号、课程名或楼宇名写进文件名。内容仍沿用教务原始 HTML/JSON，让线上解析器
 * 与离线解析保持同一套规则。
 */
class OfflineCache(context: Context, campus: Campus) {

    data class Snapshot(val text: String, val savedAt: Long)

    private val directory = File(context.filesDir, "offline_cache_${campus.storageKey}")

    @Synchronized
    fun saveText(key: String, text: String) {
        if (text.isBlank()) return
        directory.mkdirs()
        val file = file(key)
        val temp = File(directory, ".${file.name}.tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    @Synchronized
    fun loadText(key: String): Snapshot? {
        val file = file(key)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching { Snapshot(file.readText(Charsets.UTF_8), file.lastModified()) }.getOrNull()
    }

    @Synchronized
    fun saveOptions(key: String, options: List<Option>) {
        saveText(key, JSONArray().also { array ->
            options.forEach { option ->
                array.put(JSONArray().put(option.code).put(option.name))
            }
        }.toString())
    }

    @Synchronized
    fun loadOptions(key: String): List<Option>? {
        val text = loadText(key)?.text ?: return null
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONArray(index) ?: return@mapNotNull null
                val code = item.optString(0)
                val name = item.optString(1)
                if (code.isBlank() && name.isBlank()) null else Option(code, name)
            }
        }.getOrNull()
    }

    /** 只缓存已经过字段白名单处理的学籍键值，不保存接口原始 XML。 */
    @Synchronized
    fun saveInfoItems(key: String, items: List<InfoItem>) {
        saveText(key, JSONArray().also { array ->
            items.forEach { item -> array.put(JSONArray().put(item.label).put(item.value)) }
        }.toString())
    }

    @Synchronized
    fun loadInfoItems(key: String): List<InfoItem>? {
        val text = loadText(key)?.text ?: return null
        return runCatching {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONArray(index) ?: return@mapNotNull null
                val label = item.optString(0)
                val value = item.optString(1)
                if (label.isBlank() || value.isBlank()) null else InfoItem(label, value)
            }
        }.getOrNull()
    }

    /** [StudentProfile.details] 已由解析器排除身份证号等敏感字段，只保存这份安全视图。 */
    @Synchronized
    fun saveStudentProfile(key: String, profile: StudentProfile) {
        saveText(key, JSONObject().apply {
            put("name", profile.name)
            put("studentId", profile.studentId)
            put("gender", profile.gender)
            put("department", profile.department)
            put("major", profile.major)
            put("className", profile.className)
            put("grade", profile.grade)
            put("level", profile.level)
            put("details", JSONArray().also { array ->
                profile.details.forEach { item -> array.put(JSONArray().put(item.label).put(item.value)) }
            })
        }.toString())
    }

    @Synchronized
    fun loadStudentProfile(key: String): StudentProfile? {
        val text = loadText(key)?.text ?: return null
        return runCatching {
            val objectValue = JSONObject(text)
            val details = objectValue.optJSONArray("details")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONArray(index) ?: return@mapNotNull null
                    val label = item.optString(0)
                    val value = item.optString(1)
                    if (label.isBlank() || value.isBlank()) null else InfoItem(label, value)
                }
            }.orEmpty()
            StudentProfile(
                name = objectValue.optString("name"),
                studentId = objectValue.optString("studentId"),
                gender = objectValue.optString("gender"),
                department = objectValue.optString("department"),
                major = objectValue.optString("major"),
                className = objectValue.optString("className"),
                grade = objectValue.optString("grade"),
                level = objectValue.optString("level"),
                details = details,
            )
        }.getOrNull()
    }

    @Synchronized
    fun hasData(): Boolean = directory.listFiles()?.any { it.isFile && it.length() > 0L } == true

    @Synchronized
    fun clear() {
        directory.deleteRecursively()
    }

    fun key(prefix: String, vararg parts: String): String =
        "${prefix}_${sha256(parts.joinToString("\u001F"))}"

    private fun file(key: String): File = File(directory, key.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    companion object {
        const val TERMS = "terms"
        const val SCHEDULE = "schedule"
        const val GRADES_VALID = "grades_valid"
        const val GRADES_ALL = "grades_all"
        const val COURSE_MODULES = "course_modules"
        const val SELECTION_CATEGORIES = "selection_categories"
        const val EXAM_ROUNDS = "exam_rounds"
        const val EXAMS = "exams"
        const val CAMPUSES = "campuses"
        const val BUILDINGS = "buildings"
        const val CLASSROOMS = "classrooms"
        const val STUDENT_INFO = "student_info"
        const val STUDENT_PROFILE = "student_profile"
        const val STUDENT_INFO_ITEMS = "student_info_items"
        const val CREDIT_REQUIREMENTS = "credit_requirements"

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
