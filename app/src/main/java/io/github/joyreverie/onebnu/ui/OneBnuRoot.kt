package io.github.joyreverie.onebnu.ui

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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import io.github.joyreverie.onebnu.ui.settings.SettingsScreen
import io.github.joyreverie.onebnu.ui.theme.LocalScreenInfo
import io.github.joyreverie.onebnu.ui.theme.OneBnuTheme
import io.github.joyreverie.onebnu.ui.theme.ProvideScreenInfo
import io.github.joyreverie.onebnu.ui.update.AutoUpdatePrompt
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
    const val WEB = "web"
    const val PORTAL_WEB = "portal_web"
    const val ONEVPN_WEB = "onevpn_web"

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

internal data class Tab(val route: String, val label: String, val icon: ImageVector)

/** NavigationRail 的标准宽度，用于把可用宽度从屏宽里扣掉。 */
private const val RAIL_WIDTH = 80

/** 手机底部导航栏的 Material 3 标准高度，提醒浮层需要停在它上方。 */
private val BOTTOM_NAV_HEIGHT = 80.dp

private val TABS = listOf(
    Tab(Routes.HOME, "首页", Icons.Filled.Home),
    Tab(Routes.SCHEDULE, "课表", Icons.Filled.CalendarMonth),
    Tab(Routes.GRADE, "成绩", Icons.Filled.School),
    Tab(Routes.PROFILE, "我的", Icons.Filled.Person),
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

            LaunchedEffect(loggedIn, activeCampus) {
                if (loggedIn && ServiceLocator.auth.hasSession()) {
                    val auth = ServiceLocator.auth
                    withContext(Dispatchers.IO) {
                        SsoWarmup.warm(ServiceLocator.http, auth, activeCampus)
                        ServiceLocator.repo.prefetchBasicData()
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
                                NavigationRailItem(
                                    selected = current == tab.route,
                                    onClick = { onTab(tab) },
                                    icon = { Icon(tab.icon, tab.label) },
                                    label = { Text(tab.label) },
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
                            if (showNav) {
                                NavigationBar {
                                    TABS.forEach { tab ->
                                        NavigationBarItem(
                                            selected = current == tab.route,
                                            onClick = { onTab(tab) },
                                            icon = { Icon(tab.icon, tab.label) },
                                            label = { Text(tab.label) },
                                        )
                                    }
                                }
                            }
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
                                bottom = if (showNav && !screen.useNavRail) BOTTOM_NAV_HEIGHT else 0.dp,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // 启动时联网自动检查更新（设置里可关）；对话框浮在任何页面之上
            AutoUpdatePrompt()
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
        )
    }
    composable(Routes.INFO) { InfoScreen(onBack = { nav.popBackStack() }) }
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
