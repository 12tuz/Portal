@file:Suppress("LocalVariableName", "PrivateApi")
package moe.fuqiuluo.xposed.utils

import android.content.Context
import android.os.Binder
import android.os.Build
import de.robv.android.xposed.XposedBridge


object BinderUtils {
    private fun getActivityContext(): Context? {
        // public static ActivityManagerService self()
        // frameworks/base/services/java/com/android/server/am/ActivityManagerService.java
        try {
            val cam = Class.forName("com.android.server.am.ActivityManagerService")
            val am = cam.getMethod("self").invoke(null) ?: return null
            val mContext = cam.getDeclaredField("mContext")
            mContext.isAccessible = true
            return mContext.get(am) as? Context
        } catch (e: Throwable) {
            return null
        }
    }

    fun getSystemContext(): Context? {
        try {
            val cActivityThread = Class.forName("android.app.ActivityThread")
            val activityThread = cActivityThread.getMethod("currentActivityThread")
                .invoke(null) ?: return null
            return (cActivityThread.getMethod("getSystemContext").invoke(activityThread) as? Context) ?: getActivityContext()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return null
    }

    fun getUidPackageNames(context: Context = getSystemContext()!!, uid: Int = getCallerUid()): Array<String>? {
        val packageManager = context.packageManager
        return packageManager.getPackagesForUid(uid)
    }

    fun getCallerUid(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            kotlin.runCatching { Binder.getCallingUidOrThrow() }.getOrNull() ?: -1
        } else {
            Binder.getCallingUid()
        }
    }

    /**
     * Check whether the `PortalService` is started properly
     */
    fun isLocationProviderEnabled(uid: Int): Boolean {
        val packageNames = getUidPackageNames(uid = uid)
        if (uid > 10000 && packageNames?.any {
                !it.contains("moe.fuqiuluo.portal")
            } == false) {
            return true
        }
        Logger.warn("Someone try to find Portal: uid = $uid, packageName = ${packageNames?.joinToString()}")
        return uid < 10000
    }

    fun isSystemPackages(packageNames: String): Boolean {
        if (packageNames.contains("com.xiaomi.location.fused") ||
            packageNames.contains("com.xiaomi.metoknlp") ||
            //packageNames.contains("com.android.phone") ||
            packageNames.contains("com.android.location.fused")
            ) {
            return false
        }
        return packageNames.contains("com.android") ||
                packageNames.contains("com.miui") ||
                packageNames.contains("com.xiaomi") ||
                packageNames.contains("com.oplus") ||
                packageNames.contains("com.coloros") ||
                packageNames.contains("com.heytap") ||
                packageNames.contains("android.framework") ||
                packageNames.contains("com.qualcomm") ||
                packageNames.contains("com.google.android.permissioncontroller")
    }

    fun isSystemAppsCall(uid: Int = getCallerUid()): Boolean {
        if (uid > 10000) {
            val packageNames = kotlin.runCatching { getUidPackageNames(uid = uid)?.joinToString() }
                .getOrNull() ?: return true
            return isSystemPackages(packageNames)
        }
        return true
    }

    /**
     * 检查调用者是否是目标应用
     * 如果启用 UID 过滤且目标列表不为空，只对目标应用返回 true
     * 否则对所有非系统应用返回 true
     */
    fun isTargetApp(uid: Int = getCallerUid()): Boolean {
        // 系统应用始终跳过
        if (isSystemAppsCall(uid)) {
            return false
        }

        // 如果启用精确过滤且目标列表不为空
        if (FakeLoc.enableUidFilter && FakeLoc.targetUids.isNotEmpty()) {
            return FakeLoc.targetUids.contains(uid)
        }

        // 未启用精确过滤时，对所有非系统应用生效
        return true
    }

    /**
     * 添加目标应用 UID
     */
    fun addTargetUid(uid: Int) {
        FakeLoc.targetUids.add(uid)
    }

    /**
     * 移除目标应用 UID
     */
    fun removeTargetUid(uid: Int) {
        FakeLoc.targetUids.remove(uid)
    }

    /**
     * 清空目标应用列表
     */
    fun clearTargetUids() {
        FakeLoc.targetUids.clear()
    }

    /**
     * 根据包名添加目标应用
     */
    fun addTargetPackage(context: Context = getSystemContext()!!, packageName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val uid = packageInfo.applicationInfo!!.uid
            addTargetUid(uid)
            true
        } catch (e: Exception) {
            Logger.error("Failed to add target package: $packageName", e)
            false
        }
    }
}