package io.github.joyreverie.onebnu.core.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.joyreverie.onebnu.core.net.CasClient
import io.github.joyreverie.onebnu.core.net.Http
import io.github.joyreverie.onebnu.core.net.NetworkDiagnostics
import io.github.joyreverie.onebnu.core.net.OneVpnSso
import io.github.joyreverie.onebnu.core.net.SessionAuthenticator
import io.github.joyreverie.onebnu.core.net.PortalSso
import io.github.joyreverie.onebnu.core.notify.ClassReminder
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.core.store.CampusStore
import io.github.joyreverie.onebnu.core.store.CreditCategoryStore
import io.github.joyreverie.onebnu.core.store.DeviceIdentity
import io.github.joyreverie.onebnu.core.store.PersonalEventStore
import io.github.joyreverie.onebnu.core.store.OfflineCache
import io.github.joyreverie.onebnu.core.store.ScheduleCache
import io.github.joyreverie.onebnu.core.store.SecureStore
import io.github.joyreverie.onebnu.core.store.Settings
import io.github.joyreverie.onebnu.data.remote.ZhuhaiCasClient
import io.github.joyreverie.onebnu.data.remote.ZyfwApi
import io.github.joyreverie.onebnu.data.repo.AcademicRepository
import io.github.joyreverie.onebnu.data.repo.SessionRepository
import io.github.joyreverie.onebnu.widget.TodayWidgetProvider
import io.github.joyreverie.onebnu.widget.WidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private class CampusRuntime(
    val campus: Campus,
    val http: Http,
    val auth: SessionAuthenticator,
    val secure: SecureStore,
    val settings: Settings,
    val api: ZyfwApi,
    val scheduleCache: ScheduleCache,
    val offlineCache: OfflineCache,
    val events: PersonalEventStore,
    val creditCategories: CreditCategoryStore,
    val diagnostics: NetworkDiagnostics,
) {
    // 2026-2027 校历两校区周次一致；珠海也使用同一份官方周次基准。
    val repo = AcademicRepository(
        api,
        auth,
        secure,
        scheduleCache,
        offlineCache,
        useOfficialCalendar = true,
        campus = campus,
    )
    val session = SessionRepository(api, auth, secure, offlineCache)
}

/** 北京、珠海各自拥有完整运行时，不共享认证会话或业务缓存。 */
object ServiceLocator {
    lateinit var app: Context
        private set
    lateinit var campusStore: CampusStore
        private set

    private lateinit var runtimes: Map<Campus, CampusRuntime>
    private lateinit var beijingDevice: DeviceIdentity
    private val _activeCampus = MutableStateFlow(Campus.BEIJING)
    val activeCampusFlow: StateFlow<Campus> = _activeCampus.asStateFlow()
    val activeCampus: Campus get() = _activeCampus.value
    private val current: CampusRuntime get() = runtimes.getValue(activeCampus)

    val http: Http get() = current.http
    val auth: SessionAuthenticator get() = current.auth
    /** 旧调用点兼容别名；新代码按校区使用 [auth]。 */
    val cas: SessionAuthenticator get() = current.auth
    val api: ZyfwApi get() = current.api
    val secure: SecureStore get() = current.secure
    val settings: Settings get() = current.settings
    val repo: AcademicRepository get() = current.repo
    val session: SessionRepository get() = current.session
    val diagnostics: NetworkDiagnostics get() = current.diagnostics
    val scheduleCache: ScheduleCache get() = current.scheduleCache
    val offlineCache: OfflineCache get() = current.offlineCache
    val events: PersonalEventStore get() = current.events
    val creditCategories: CreditCategoryStore get() = current.creditCategories
    val device: DeviceIdentity? get() = currentDevice()

    fun init(context: Context) {
        app = context.applicationContext
        campusStore = CampusStore(app)
        beijingDevice = DeviceIdentity(app)

        fun createRuntime(campus: Campus): CampusRuntime {
            val isBeijing = campus == Campus.BEIJING
            val device = if (isBeijing) beijingDevice else null
            val http = Http.create(device, if (isBeijing) "cas.bnu.edu.cn" else "cas.bnuzh.edu.cn")
            val auth: SessionAuthenticator = if (isBeijing) CasClient(http, beijingDevice) else ZhuhaiCasClient(http)
            val secure = SecureStore.create(app, campus)
            val settings = Settings(app, campus)
            val changed = {
                TodayWidgetProvider.updateAll(app)
                ClassReminder.reschedule(app)
            }
            return CampusRuntime(
                campus = campus,
                http = http,
                auth = auth,
                secure = secure,
                settings = settings,
                api = if (isBeijing) {
                    ZyfwApi(
                        http = http,
                        auth = auth,
                        base = ZyfwApi.BASE,
                        proxyBase = OneVpnSso.proxyBase("http", "zyfw.bnu.edu.cn"),
                        preferProxy = { isCellularNetwork(app) },
                    )
                } else {
                    ZyfwApi(http, auth, ZhuhaiCasClient.JWXT_BASE)
                },
                scheduleCache = ScheduleCache(app, campus, changed),
                offlineCache = OfflineCache(app, campus),
                events = PersonalEventStore(app, campus, changed),
                creditCategories = CreditCategoryStore(app, campus),
                diagnostics = NetworkDiagnostics(http, campus),
            )
        }

        runtimes = Campus.values().associateWith(::createRuntime)
        _activeCampus.value = campusStore.selected
        ClassReminder.ensureChannel(app)
        if (settings.remindersEnabled) ClassReminder.reschedule(app)
    }

    fun selectCampus(campus: Campus) {
        if (!runtimes.containsKey(campus)) return
        _activeCampus.value = campus
        campusStore.selected = campus
        TodayWidgetProvider.updateAll(app)
        ClassReminder.reschedule(app)
    }

    fun signOut(forgetCredentials: Boolean) {
        PortalSso.clear(activeCampus)
        current.auth.logout()
        current.api.invalidate()
        current.session.clear()
        if (forgetCredentials) current.secure.clear()
        WidgetState(app, activeCampus).clear()
        current.scheduleCache.clear()
        current.offlineCache.clear()
        ClassReminder.reschedule(app)
    }

    /** 登录另一个账号前清理上一账号的教务快照与组件数据。 */
    fun clearAcademicData() {
        current.offlineCache.clear()
        current.scheduleCache.clear()
        current.session.clear()
        current.api.invalidate()
        PortalSso.clear(activeCampus)
        WidgetState(app, activeCampus).clear()
    }

    /** 允许已有本地快照在没有 CAS Cookie 时进入主界面。 */
    fun hasOfflineData(): Boolean = current.offlineCache.hasData() || current.scheduleCache.load() != null

    fun currentDevice(): DeviceIdentity? = if (activeCampus == Campus.BEIJING) beijingDevice else null

    private fun isCellularNetwork(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }
}
