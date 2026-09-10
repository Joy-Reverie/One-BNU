package io.github.joyreverie.onebnu.data.model

import android.content.Context
import io.github.joyreverie.onebnu.R
import org.json.JSONArray
import org.json.JSONObject

/** 一个可拨的号码：[number] 是校内 8 位号，[dial] 是带区号的实际拨号串，[ext] 如「转 8001」。 */
data class ContactTel(val number: String, val dial: String, val ext: String = "") {
    /**
     * 界面上显示的就是手机要拨的完整号码，如「010 5880 6110」：校内 5880 号段的座机
     * 用手机拨必须加 010，显示成 8 位会让人少拨区号。
     */
    val pretty: String get() = prettyDial(dial)

    companion object {
        /** 「01058806110」→「010 5880 6110」；非北京固话原样返回。 */
        fun prettyDial(dial: String): String =
            if (dial.length == 11 && dial.startsWith("010")) "010 " + dial.substring(3, 7) + " " + dial.substring(7) else dial
    }
}

/** 一条联系方式：办什么事 / 找谁 / 几个号码 / 在哪 / 补充（办公时间、邮箱、备注）。 */
data class ContactRow(
    val what: String,
    val who: String,
    val tels: List<ContactTel>,
    val where: String,
    val extra: String,
    val h24: Boolean = false,
    val unverified: Boolean = false,
) {
    /** 补充信息里的邮箱（若有）。 */
    val email: String? get() = EMAIL.find(extra)?.value

    /** 去掉邮箱后剩下的补充说明。 */
    val extraText: String
        get() = extra.replace(EMAIL, "").replace(Regex("^[\\s·]+|[\\s·]+$"), "").replace(Regex("\\s*·\\s*·\\s*"), " · ")

    fun matches(needle: String): Boolean =
        listOf(what, who, where, extra).any { normalize(it).contains(needle) } ||
            tels.any { it.number.contains(needle) || normalize(it.ext).contains(needle) }

    companion object {
        val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}

data class ContactSection(val title: String, val rows: List<ContactRow>)

data class ContactHours(val label: String, val value: String)

data class ContactGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val sourceLabel: String,
    val sourceUrl: String,
    /** 来源页面自己标注的发布 / 更新日期（ISO），页面没标就为空。 */
    val sourceDate: String,
    val notes: List<String>,
    val hours: List<ContactHours>,
    val sections: List<ContactSection>,
) {
    val rowCount: Int get() = sections.sumOf { it.rows.size }

    /** 「页面发布于 2024年4月2日」或「页面未标注发布日期」。 */
    val sourceDateLabel: String
        get() {
            val parts = sourceDate.split("-")
            if (parts.size != 3) return "页面未标注发布日期"
            return "页面发布于 ${parts[0]}年${parts[1].toInt()}月${parts[2].toInt()}日"
        }
}

data class EmergencyContact(val number: String, val dial: String, val label: String) {
    /** 显示用的完整拨号，如「010 5880 6110」。 */
    val pretty: String get() = ContactTel.prettyDial(dial)
}

/** 一条搜索结果：命中的行及其所属分区、小节。 */
data class ContactHit(val group: ContactGroup, val section: ContactSection, val row: ContactRow)

data class CampusDirectory(
    val updated: String,
    val emergency: List<EmergencyContact>,
    val groups: List<ContactGroup>,
) {
    val rowCount: Int get() = groups.sumOf { it.rowCount }

    /** 按部门、业务、老师、楼号、号码模糊搜索；空白查询返回空列表。 */
    fun search(query: String): List<ContactHit> {
        val needle = normalize(query)
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<ContactHit>()
        for (g in groups) {
            val groupHit = normalize(g.title).contains(needle) || normalize(g.subtitle).contains(needle)
            for (s in g.sections) {
                val sectionHit = normalize(s.title).contains(needle)
                for (r in s.rows) {
                    if (groupHit || sectionHit || r.matches(needle)) out += ContactHit(g, s, r)
                }
            }
        }
        return out
    }
}

/** 搜索用的归一化：去空白、小写、全角冒号等统一。 */
internal fun normalize(s: String): String = s.replace(Regex("\\s+"), "").lowercase()

/** `res/raw/campus_contacts.json` 的解析；格式见 README「校内联系方式」。 */
object CampusContactsJson {

    fun parse(text: String): CampusDirectory {
        val root = JSONObject(text)
        val emergency = root.optJSONArray("emergency").map { o ->
            EmergencyContact(o.getString("number"), o.getString("dial"), o.getString("label"))
        }
        val groups = root.getJSONArray("groups").map { g ->
            ContactGroup(
                id = g.getString("id"),
                title = g.getString("title"),
                subtitle = g.optString("subtitle"),
                sourceLabel = g.optString("sourceLabel"),
                sourceUrl = g.optString("sourceUrl"),
                sourceDate = g.optString("sourceDate"),
                notes = g.optJSONArray("notes").mapStrings(),
                hours = g.optJSONArray("hours").map { h -> ContactHours(h.getString("label"), h.getString("value")) },
                sections = g.getJSONArray("sections").map { s ->
                    ContactSection(
                        title = s.optString("title"),
                        rows = s.getJSONArray("rows").map { r ->
                            ContactRow(
                                what = r.getString("what"),
                                who = r.optString("who"),
                                tels = r.getJSONArray("tels").map { t ->
                                    ContactTel(t.getString("number"), t.getString("dial"), t.optString("ext"))
                                },
                                where = r.optString("where"),
                                extra = r.optString("extra"),
                                h24 = r.optBoolean("h24"),
                                unverified = r.optBoolean("unverified"),
                            )
                        },
                    )
                },
            )
        }
        return CampusDirectory(root.optString("updated"), emergency, groups)
    }

    private fun <T> JSONArray?.map(block: (JSONObject) -> T): List<T> =
        if (this == null) emptyList() else (0 until length()).map { block(getJSONObject(it)) }

    private fun JSONArray?.mapStrings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }
}

/** 内置通讯录的加载与缓存：337 条只解析一次。 */
object CampusContacts {
    @Volatile private var cached: CampusDirectory? = null

    fun load(context: Context): CampusDirectory = cached ?: synchronized(this) {
        cached ?: context.resources.openRawResource(R.raw.campus_contacts).bufferedReader().use { it.readText() }
            .let { CampusContactsJson.parse(it) }
            .also { cached = it }
    }
}
