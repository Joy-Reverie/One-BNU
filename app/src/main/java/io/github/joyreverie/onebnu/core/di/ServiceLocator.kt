package io.github.joyreverie.onebnu.core.di

import android.content.Context
import io.github.joyreverie.onebnu.core.net.CasClient
import io.github.joyreverie.onebnu.core.net.Http
import io.github.joyreverie.onebnu.core.net.NetworkDiagnostics
import io.github.joyreverie.onebnu.core.notify.ClassReminder
import io.github.joyreverie.onebnu.core.store.DeviceIdentity
import io.github.joyreverie.onebnu.core.store.CreditCategoryStore
import io.github.joyreverie.onebnu.core.store.PersonalEventStore
import io.github.joyreverie.onebnu.core.store.ScheduleCache
import io.github.joyreverie.onebnu.core.store.SecureStore
import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import io.github.joyreverie.onebnu.data.repo.AcademicRepository
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import io.github.joyreverie.onebnu.widget.TodayWidgetProvider
import io.github.joyreverie.onebnu.widget.WidgetState

/**
 * 依赖装配。应用规模不大，用一个显式的单例容器比引入 DI 框架更清楚。
 */
object ServiceLocator {

    lateinit var app: Context
        private set
    lateinit var http: Http
        private set
    lateinit var cas: CasClient
        private set
    lateinit var api: ZyfwApi
        private set
    lateinit var secure: SecureStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var device: DeviceIdentity
        private set
    lateinit var repo: AcademicRepository
        private set
    lateinit var session: SessionRepository
        private set
    lateinit var diagnostics: NetworkDiagnostics
        private set
    lateinit var scheduleCache: ScheduleCache
        private set
    lateinit var events: PersonalEventStore
        private set
    lateinit var creditCategories: CreditCategoryStore
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        this.app = app
        device = DeviceIdentity(app)
        http = Http.create(device)
        cas = CasClient(http, device)
        api = ZyfwApi(http, cas)
        secure = SecureStore.create(app)
        settings = Settings(app)
        // 课表缓存、个人日程一有变化就让桌面小组件重绘，并重排上课提醒
        val onTimetableChanged = {
            TodayWidgetProvider.updateAll(app)
            ClassReminder.reschedule(app)
        }
        scheduleCache = ScheduleCache(app, onTimetableChanged)
        events = PersonalEventStore(app, onTimetableChanged)
        creditCategories = CreditCategoryStore(app)
        repo = AcademicRepository(api, cas, secure, scheduleCache)
        session = SessionRepository(api, cas, secure)
        diagnostics = NetworkDiagnostics(http)
        ClassReminder.ensureChannel(app)
        // 进程重启后闹钟可能已丢，开着提醒就补排一次
        if (settings.remindersEnabled) ClassReminder.reschedule(app)
    }

    /** 退出登录：清会话、清 Cookie，并按需清凭据；桌面小组件上的课表也一并清掉。 */
    fun signOut(forgetCredentials: Boolean) {
        cas.logout()
        api.invalidate()
        session.clear()
        if (forgetCredentials) secure.clear()
        WidgetState(app).clear()
        scheduleCache.clear()
        ClassReminder.reschedule(app)
    }
}
