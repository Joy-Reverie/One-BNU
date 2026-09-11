package io.github.joyreverie.onebnu.core.store

import android.content.Context
import io.github.joyreverie.onebnu.data.model.PersonalEvent
import io.github.joyreverie.onebnu.data.model.PersonalEventJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDate

/**
 * 个人日程的本地存储：一个 JSON 文件，改动后整份重写。
 * 日程量很小（一学期几十条），不值得上数据库。[onChanged] 用来通知小组件重绘、重排上课提醒。
 */
class PersonalEventStore(
    context: Context,
    campus: Campus = Campus.BEIJING,
    private val onChanged: () -> Unit = {},
) {

    private val file = File(
        context.filesDir,
        if (campus == Campus.BEIJING) "personal_events.json" else "personal_events_${campus.storageKey}.json",
    )

    private val _events = MutableStateFlow(read())
    val events: StateFlow<List<PersonalEvent>> = _events.asStateFlow()

    fun all(): List<PersonalEvent> = _events.value

    /** 这一天发生的日程（含重复日程在这天的那一次），按开始时间排序。 */
    fun on(date: LocalDate): List<PersonalEvent> = all().filter { it.occursOn(date) }.sortedBy { it.start }

    /** 在 [from]～[toInclusive] 之间至少发生一次的日程。 */
    fun between(from: LocalDate, toInclusive: LocalDate): List<PersonalEvent> {
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(toInclusive) }.toList()
        return all().filter { e -> days.any { e.occursOn(it) } }.sortedWith(compareBy({ it.date }, { it.start }))
    }

    @Synchronized
    fun upsert(event: PersonalEvent) {
        write(all().filter { it.id != event.id } + event)
    }

    @Synchronized
    fun delete(id: String) {
        write(all().filter { it.id != id })
    }

    @Synchronized
    fun clear() = write(emptyList())

    private fun read(): List<PersonalEvent> =
        if (file.exists()) runCatching { PersonalEventJson.decode(file.readText()) }.getOrDefault(emptyList()) else emptyList()

    private fun write(list: List<PersonalEvent>) {
        val sorted = list.sortedWith(compareBy({ it.date }, { it.start }))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(PersonalEventJson.encode(sorted))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        _events.value = sorted
        onChanged()
    }
}
