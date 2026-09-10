package io.github.joyreverie.onebnu.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import io.github.joyreverie.onebnu.core.di.ServiceLocator
import io.github.joyreverie.onebnu.data.repo.AcademicRepository.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * 在后台把当前学期课表拉一次、写进缓存，供桌面小组件使用。
 *
 * 用系统 JobScheduler 而不是在广播里直接联网：广播接收器只给几秒钟，而重新登录 + SSO + 拉课表
 * 在校园网外可能要十几秒。登录复用应用里保存的账号密码（没保存就不会联网，直接提示去登录），
 * 需要短信二次验证时不会替用户发短信，而是提示打开应用。
 */
class WidgetRefreshJob : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            runCatching { refresh(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        scope.coroutineContext.cancelChildren()
        WidgetState(this).refreshing = false
        TodayWidgetProvider.updateAll(this)
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 1001

        /** 缓存超过这么久就顺手刷一次（由半小时一次的周期更新触发）。 */
        private const val STALE_MS = 6 * 60 * 60 * 1000L

        fun schedule(context: Context, urgent: Boolean) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, WidgetRefreshJob::class.java)

            // 不把「有网络」设成硬性条件：国内网络常常通不过系统的联网校验（状态栏信号旁带感叹号），
            // 那样任务会一直等不到条件满足。没网时任务会很快失败并在小组件上给出提示。
            fun info(expedited: Boolean): JobInfo = JobInfo.Builder(JOB_ID, component)
                .apply {
                    when {
                        expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> setExpedited(true)
                        urgent -> setOverrideDeadline(2_000L)
                        else -> {
                            setMinimumLatency(1_000L)
                            setOverrideDeadline(60_000L)
                        }
                    }
                }
                .build()

            var result = scheduler.schedule(info(expedited = urgent))
            if (result != JobScheduler.RESULT_SUCCESS && urgent) {
                // 加急配额用完时退回普通任务，宁可慢一点也要刷
                result = scheduler.schedule(info(expedited = false))
            }
            if (result != JobScheduler.RESULT_SUCCESS) {
                WidgetState(context).refreshing = false
                TodayWidgetProvider.updateAll(context)
            }
        }

        fun scheduleIfStale(context: Context) {
            val state = WidgetState(context)
            if (state.refreshing) return
            val newest = maxOf(ServiceLocator.scheduleCache.savedAt, state.lastAttempt)
            if (System.currentTimeMillis() - newest > STALE_MS) schedule(context, urgent = false)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }

        /** 拉一次课表。成功与否都会把 refreshing 复位并重绘。 */
        suspend fun refresh(context: Context): Boolean {
            val state = WidgetState(context)
            state.lastAttempt = System.currentTimeMillis()
            state.refreshing = true
            TodayWidgetProvider.updateAll(context)
            try {
                if (!ServiceLocator.secure.hasCredentials && !ServiceLocator.cas.hasSession()) {
                    state.lastError = "尚未登录"
                    return false
                }
                val repo = ServiceLocator.repo
                val terms = when (val t = repo.terms()) {
                    is Outcome.Ok -> t.data
                    is Outcome.Empty -> return fail(state, t.reason)
                    is Outcome.Error -> return fail(state, if (t.needLogin) LOGIN_EXPIRED else t.message)
                }
                val term = repo.currentTerm(terms) ?: return fail(state, "没有可用学期")
                return when (val s = repo.schedule(term)) {
                    // 空课表也算成功：缓存里已经写入了「没有选课记录」的课表
                    is Outcome.Ok, is Outcome.Empty -> {
                        state.lastError = null
                        true
                    }
                    is Outcome.Error -> fail(state, if (s.needLogin) LOGIN_EXPIRED else s.message)
                }
            } finally {
                state.refreshing = false
                TodayWidgetProvider.updateAll(context)
            }
        }

        private fun fail(state: WidgetState, message: String): Boolean {
            state.lastError = message
            return false
        }

        private const val LOGIN_EXPIRED = "登录已失效，请打开 One BNU 重新登录"
    }
}
