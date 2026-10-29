package io.github.joyreverie.onebnu.widget

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * MIUI 独有的「桌面快捷方式」权限：没开时 `requestPinAppWidget` 会被桌面静默吞掉，
 * 接口却照样返回 true。系统没有公开的申请接口，只能查一下状态、把用户带到 MIUI 的权限编辑页。
 */
object MiuiShortcutPermission {

    /** MIUI 里「桌面快捷方式」对应的 AppOps 编号。 */
    private const val OP_INSTALL_SHORTCUT = 10017

    /** debug 预览页用：true 表示强行当成「小米且未授权」，模拟器上也能看这条提示。 */
    var previewOverride: Boolean? = null

    val isMiui: Boolean by lazy {
        val brand = (Build.MANUFACTURER + Build.BRAND).lowercase()
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") || miuiVersion().isNotBlank()
    }

    /** 是否需要提醒用户去开权限：只在 MIUI 且能确认未授权时为 true。 */
    fun needsGrant(context: Context): Boolean {
        previewOverride?.let { return it }
        if (!isMiui) return false
        return isAllowed(context) == false
    }

    /** true / false 为查到的结果，null 为查不到（非 MIUI 或反射失败）。 */
    fun isAllowed(context: Context): Boolean? = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        val method = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
        )
        val mode = method.invoke(ops, OP_INSTALL_SHORTCUT, android.os.Process.myUid(), context.packageName) as Int
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrNull()

    /** 打开 MIUI 的应用权限编辑页；机型不支持时退回系统的应用信息页。 */
    fun openSettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                .putExtra("extra_pkgname", pkg),
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in candidates) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return
        }
    }

    private fun miuiVersion(): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, "ro.miui.ui.version.name") as String
    }.getOrDefault("")
}

/**
 * MIUI 的「自启动」：关着时，进程被清理后系统不再替我们拉起提醒的广播接收器，
 * 到点的提醒要等用户自己打开应用才送出。同样没有公开接口，只能查状态、把人带到自启动管理页。
 */
object MiuiAutoStart {

    /** MIUI 里「自启动」对应的 AppOps 编号。 */
    private const val OP_AUTO_START = 10008

    /** debug 预览页用：true 表示强行当成「小米且自启动关着」。 */
    var previewOverride: Boolean? = null

    /** 只在 MIUI 上、且能确认自启动关着时为 true；查不到（反射失败、非小米）一律当作正常。 */
    fun needsGrant(context: Context): Boolean {
        previewOverride?.let { return it }
        if (!MiuiShortcutPermission.isMiui) return false
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            method.invoke(ops, OP_AUTO_START, android.os.Process.myUid(), context.packageName) as Int != AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /** 打开 MIUI 的自启动管理页；找不到时退回应用权限编辑页。 */
    fun openSettings(context: Context) {
        val intent = Intent()
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!runCatching { context.startActivity(intent); true }.getOrDefault(false)) {
            MiuiShortcutPermission.openSettings(context)
        }
    }
}
