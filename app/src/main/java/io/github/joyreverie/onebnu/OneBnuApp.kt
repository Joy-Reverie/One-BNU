package io.github.joyreverie.onebnu

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.github.joyreverie.onebnu.core.di.ServiceLocator

class OneBnuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppVisibility)
        ServiceLocator.init(this)
    }
}

/**
 * 应用当前是否有界面在前台。Android 10 起后台进程不能直接拉起 Activity，
 * 更新包下载完成时靠它决定是弹安装器还是发通知。
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {
    private var started = 0

    val foreground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
