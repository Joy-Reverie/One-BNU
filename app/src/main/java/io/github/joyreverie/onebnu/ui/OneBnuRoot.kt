package io.github.joyreverie.onebnu.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import io.github.joyreverie.onebnu.ui.components.CircleTabButton
import io.github.joyreverie.onebnu.ui.theme.LocalAccents
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.core.net.MailSso
import io.github.joyreverie.onebnu.core.net.PanSso
import io.github.joyreverie.onebnu.core.net.SsoWarmup
import io.github.joyreverie.onebnu.core.store.Campus
import io.github.joyreverie.onebnu.ui.campus.CalendarScreen
import io.github.joyreverie.onebnu.ui.diagnostics.DiagnosticsScreen
import io.github.joyreverie.onebnu.ui.campus.CampusMapScreen
import io.github.joyreverie.onebnu.ui.campus.ContactsScreen
import io.github.joyreverie.onebnu.ui.classroom.ClassroomScreen
import io.github.joyreverie.onebnu.ui.exam.ExamScreen
import io.github.joyreverie.onebnu.ui.grade.GradeScreen
import io.github.joyreverie.onebnu.ui.home.HomeScreen
import io.github.joyreverie.onebnu.ui.info.InfoScreen
import io.github.joyreverie.onebnu.ui.login.LoginScreen
import io.github.joyreverie.onebnu.ui.profile.CreditsScreen
import io.github.joyreverie.onebnu.ui.profile.CultivationPlanScreen
import io.github.joyreverie.onebnu.ui.profile.ProfileScreen
import io.github.joyreverie.onebnu.ui.profile.StudentInfoScreen
import io.github.joyreverie.onebnu.ui.schedule.ScheduleScreen
import io.github.joyreverie.onebnu.ui.settings.ServiceEntriesScreen
import io.github.joyreverie.onebnu.ui.settings.SettingsScreen
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import io.github.joyreverie.onebnu.ui.update.AutoUpdatePrompt
import io.github.joyreverie.onebnu.ui.announcement.AnnouncementPrompt
import io.github.joyreverie.onebnu.ui.announcement.AnnouncementHistoryScreen
import io.github.joyreverie.onebnu.ui.web.WebScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import io.github.joyreverie.onebnu.ui.components.OfflineBanner

object Routes {
    const val LOGIN = "login"
    const val HOME = "home"
    const val SCHEDULE = "schedule"
    const val GRADE = "grade"
    const val PROFILE = "profile"

    const val EXAM = "exam"
    const val CLASSROOM = "classroom"
    const val CALENDAR = "calendar"
    const val PHONE = "phone"
    const val MAP = "map"
    const val SETTINGS = "settings"
    const val STUDENT_INFO = "student_info"
    const val CULTIVATION_PLAN = "cultivation_plan"
    const val CREDITS = "credits"
    const val DIAGNOSTICS = "diagnostics"
    const val INFO = "info"
    const val ANNOUNCEMENT_HISTORY = "announcement_history"
    const val WEB = "web"
    const val PORTAL_WEB = "portal_web"
    const val ONEVPN_WEB = "onevpn_web"

    /** 师大邮箱：不是普通网页地址，页内先向门户要一条一次性免密链接。 */
    const val MAIL = "mail"

    /** 师大云盘：页内先用已保存的账号替网盘登录，再打开网盘手机版。 */
    const val PAN = "pan"

    /** 设置 → 校园服务：勾选首页显示哪些入口。 */
    const val SERVICE_ENTRIES = "service_entries"

    fun web(title: String, url: String, sso: Boolean): String {
        val t = android.net.Uri.encode(title)
        val u = android.net.Uri.encode(url)
        return "$WEB/$t/$u/$sso"
    }

    /** 数字京师专用电脑端入口；只影响校园服务里的北京门户按钮。 */
    fun portalWeb(title: String, url: String, sso: Boolean): String {
        val t = android.net.Uri.encode(title)
        val u = android.net.Uri.encode(url)
        return "$PORTAL_WEB/$t/$u/$sso"
    }

    /** 课程中心专用：OneVPN 的可信 CAS 中转使用当前北京 CAS 会话，不向网页传递密码。 */
    fun oneVpnWeb(title: String, url: String): String {
        val t = android.net.Uri.encode(title)
        val u = android.net.Uri.encode(url)
        return "$ONEVPN_WEB/$t/$u"
    }
}

/** [icon] 是未选中时的线框图标，[selectedIcon] 是选中时放在实心圆里的实心图标。 */
internal data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** NavigationRail 的标准宽度，用于把可用宽度从屏宽里扣掉。 */
private const val RAIL_WIDTH = 80

/** 手机底部导航栏放页签那一段的高度（不含下方留空），提醒浮层需要停在它上方。 */
private val BOTTOM_NAV_HEIGHT = 68.dp

/**
 * 页签下方至少留这么高的空。小米等隐藏手势条的全面屏几乎不给底部 inset，
 * 不兜底的话圆形页签会贴着屏幕下沿；有手势条、inset 本来就够的机型不再叠加。
 */
private val BOTTOM_NAV_MIN_GAP = 18.dp

private val TABS = listOf(
    Tab(Routes.HOME, "首页", Icons.Outlined.Home, Icons.Filled.Home),
    Tab(Routes.SCHEDULE, "课表", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    Tab(Routes.GRADE, "成绩", Icons.Outlined.School, Icons.Filled.School),
    Tab(Routes.PROFILE, "我的", Icons.Outlined.Person, Icons.Filled.Person),
)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun OneBnuRoot(windowSizeClass: WindowSizeClass) {
    OneBnuTheme {
        ProvideScreenInfo(windowSizeClass) {
            val activeCampus by ServiceLocator.activeCampusFlow.collectAsState()
            val screen = LocalScreenInfo.current
            val context = LocalContext.current
            val nav = rememberNavController()
            // rememberSaveable：旋转导致 Activity 重建时不要退回登录页
            var loggedIn by rememberSaveable {
                mutableStateOf(ServiceLocator.auth.hasSession() || ServiceLocator.hasOfflineData())
            }
            var networkAvailable by remember { mutableStateOf(hasInternet(context)) }

            LaunchedEffect(Unit) {
                while (isActive) {
                    networkAvailable = hasInternet(context)
                    delay(2_000)
                }
            }

            // 冷启动时如果只剩离线快照（CASTGC 随进程消失了），先用已保存的凭据静默重登，
            // 否则网页入口一点就回到统一认证登录页。重登失败也不拦人：离线内容照常可看。
            LaunchedEffect(loggedIn, activeCampus) {
                if (loggedIn) {
                    withContext(Dispatchers.IO) {
                        if (ServiceLocator.ensureSession()) {
                            SsoWarmup.warm(ServiceLocator.http, ServiceLocator.auth, activeCampus)
                            ServiceLocator.repo.prefetchBasicDataInBackground()
                        }
                    }
                }
            }

            // rememberSaveable 可能恢复上次的「登录页」状态；已有离线快照时，
            // 启动后重新判定一次，确保断网仍能进入基本功能。
            LaunchedEffect(activeCampus) {
                if (ServiceLocator.auth.hasSession() || ServiceLocator.hasOfflineData()) {
                    loggedIn = true
                    if (nav.currentBackStackEntry?.destination?.route == Routes.LOGIN) {
                        nav.navigate(Routes.HOME) {
                            popUpTo(Routes.LOGIN) { inclusive = true }
                        }
                    }
                }
            }

            val backStack by nav.currentBackStackEntryAsState()
            val current = backStack?.destination?.route
            val showNav = current in TABS.map { it.route }

            val onTab: (Tab) -> Unit = { tab ->
                if (current != tab.route) {
                    nav.navigate(tab.route) {
                        popUpTo(Routes.HOME) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            val graph: @Composable (Modifier) -> Unit = { mod ->
                NavHost(
                    navController = nav,
                    startDestination = if (loggedIn) Routes.HOME else Routes.LOGIN,
                    modifier = mod,
                ) {
                    composable(Routes.LOGIN) {
                        LoginScreen(
                            onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                        onLoggedIn = {
                            loggedIn = true
                            nav.navigate(Routes.HOME) {
                                popUpTo(Routes.LOGIN) { inclusive = true }
                                }
                            },
                        )
                    }
    composable(Routes.HOME) { HomeScreen(nav) }
                    composable(Routes.SCHEDULE) { ScheduleScreen() }
                    composable(Routes.GRADE) { GradeScreen() }
                    composable(Routes.PROFILE) {
                        ProfileScreen(
                            nav = nav,
                            onSignedOut = {
                                loggedIn = false
                                nav.navigate(Routes.LOGIN) { popUpTo(0) }
                            },
                        )
                    }
                    detailRoutes(nav, activeCampus)
                }
            }

            Box(Modifier.fillMaxSize()) {
                // 宽屏 / 横屏用侧边导航：横屏时底部栏会吃掉本就紧张的高度。
                // 这条分支没有 Scaffold，背景要自己铺，否则会透出系统窗口底色（深色模式下就是一片白）。
                if (showNav && screen.useNavRail) {
                    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        NavigationRail(
                            modifier = Modifier.windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Start + WindowInsetsSides.Vertical,
                                ),
                            ),
                        ) {
                            Spacer(Modifier.weight(1f))
                            TABS.forEach { tab ->
                                CircleTabButton(
                                    tab.icon,
                                    tab.selectedIcon,
                                    tab.label,
                                    selected = current == tab.route,
                                    onClick = { onTab(tab) },
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        // 侧栏分支没有 Scaffold，必须自己做状态栏 / 手势区避让；
                        // 同时把可用宽度告诉下游，限宽居中才不会被侧栏顶偏。
                        CompositionLocalProvider(
                            LocalScreenInfo provides screen.shrunkBy(RAIL_WIDTH),
                        ) {
                            graph(
                                Modifier
                                    .weight(1f)
                                    .windowInsetsPadding(
                                        WindowInsets.safeDrawing.only(
                                            WindowInsetsSides.End + WindowInsetsSides.Vertical,
                                        ),
                                    ),
                            )
                        }
                    }
                } else {
                    Scaffold(
                        // 不在这里消费 window insets：首页的渐变要铺到状态栏下面，
                        // 其余带 TopAppBar 的页面由各自的 Scaffold 处理。
                        contentWindowInsets = WindowInsets(0),
                        bottomBar = {
                            if (showNav) CircleTabBar(current, onTab)
                        },
                    ) { padding ->
                        graph(Modifier.padding(padding))
                    }
                }

                if (loggedIn && !networkAvailable) {
                    OfflineBanner(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            // 底部导航栏属于同一个根 Box，额外留出它的高度，
                            // 这样提醒会落在导航栏上方的空白处；导航栏模式切到侧栏时不需要这段偏移。
                            .padding(
                                bottom = if (showNav && !screen.useNavRail) BOTTOM_NAV_HEIGHT + BOTTOM_NAV_MIN_GAP else 0.dp,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // 启动时联网自动检查更新（设置里可关）；对话框浮在任何页面之上
            AutoUpdatePrompt()
            AnnouncementPrompt()
        }
    }
}

/**
 * 手机底部导航：四枚只有图标的圆形页签均匀排开，白底、顶上一道细线和内容分开。
 * 系统手势条的高度另算，页签始终落在手势条上方。
 */
@Composable
private fun CircleTabBar(current: String?, onTab: (Tab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        val hairline = LocalAccents.current.hairline
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawRect(hairline, size = Size(size.width, 1.dp.toPx())) }
                .padding(bottom = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), BOTTOM_NAV_MIN_GAP))
                .height(BOTTOM_NAV_HEIGHT),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEach { tab ->
                CircleTabButton(
                    tab.icon,
                    tab.selectedIcon,
                    tab.label,
                    selected = current == tab.route,
                    onClick = { onTab(tab) },
                )
            }
        }
    }
}

private fun hasInternet(context: android.content.Context): Boolean {
    val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    return connectivity.getNetworkCapabilities(network)
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

private fun NavGraphBuilder.detailRoutes(nav: NavHostController, campus: Campus) {
    composable(Routes.EXAM) { ExamScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.CLASSROOM) { ClassroomScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.CALENDAR) { CalendarScreen(onBack = { nav.popBackStack() }) }
    if (campus == Campus.BEIJING) {
        composable(Routes.PHONE) { ContactsScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.MAP) { CampusMapScreen(onBack = { nav.popBackStack() }) }
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { nav.popBackStack() },
            onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
            onServiceEntries = { nav.navigate(Routes.SERVICE_ENTRIES) },
        )
    }
    composable(Routes.SERVICE_ENTRIES) { ServiceEntriesScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.INFO) { InfoScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.ANNOUNCEMENT_HISTORY) { AnnouncementHistoryScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.STUDENT_INFO) { StudentInfoScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.CULTIVATION_PLAN) {
        CultivationPlanScreen(
            onBack = { nav.popBackStack() },
            onOpenOfficialPage = { title, url -> nav.navigate(Routes.oneVpnWeb(title, url)) },
        )
    }
    composable(Routes.CREDITS) { CreditsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
    composable("${Routes.WEB}/{title}/{url}/{sso}") { entry ->
        WebScreen(
            title = android.net.Uri.decode(entry.arguments?.getString("title").orEmpty()),
            url = android.net.Uri.decode(entry.arguments?.getString("url").orEmpty()),
            useSso = entry.arguments?.getString("sso") == "true",
            onBack = { nav.popBackStack() },
        )
    }
    composable("${Routes.PORTAL_WEB}/{title}/{url}/{sso}") { entry ->
        WebScreen(
            title = android.net.Uri.decode(entry.arguments?.getString("title").orEmpty()),
            url = android.net.Uri.decode(entry.arguments?.getString("url").orEmpty()),
            useSso = entry.arguments?.getString("sso") == "true",
            desktopMode = true,
            onBack = { nav.popBackStack() },
        )
    }
    composable(Routes.MAIL) {
        WebScreen(
            title = "师大邮箱",
            url = MailSso.FALLBACK,
            useSso = false,
            mailMode = true,
            onBack = { nav.popBackStack() },
        )
    }
    composable(Routes.PAN) {
        WebScreen(
            title = "师大云盘",
            url = PanSso.H5,
            useSso = false,
            panMode = true,
            onBack = { nav.popBackStack() },
        )
    }
    composable("${Routes.ONEVPN_WEB}/{title}/{url}") { entry ->
        WebScreen(
            title = android.net.Uri.decode(entry.arguments?.getString("title").orEmpty()),
            url = android.net.Uri.decode(entry.arguments?.getString("url").orEmpty()),
            useSso = false,
            useOneVpnSso = true,
            onBack = { nav.popBackStack() },
        )
    }
}
