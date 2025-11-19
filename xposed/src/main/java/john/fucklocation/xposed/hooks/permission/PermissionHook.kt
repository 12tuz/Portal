package john.fucklocation.xposed.hooks.permission

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

/**
 * Android 13+ 权限Hook
 *
 * 处理Android 13引入的新权限，特别是NEARBY_WIFI_DEVICES
 * 确保位置伪造时WiFi扫描等功能正常工作
 */
object PermissionHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Logger.info("PermissionHook skipped: Android < 13")
            return
        }

        hookPermissionManager(classLoader)
        hookContextImpl(classLoader)
        Logger.info("PermissionHook initialized for Android 13+")
    }

    /**
     * Hook PermissionManager.checkPermission
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun hookPermissionManager(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cPermissionManager = XposedHelpers.findClass(
                "android.permission.PermissionManager",
                classLoader
            )

            XposedBridge.hookAllMethods(cPermissionManager, "checkPermission", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val permission = param.args.firstOrNull() as? String ?: return

                    // 自动授予WiFi和位置相关权限
                    when (permission) {
                        "android.permission.NEARBY_WIFI_DEVICES",
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION",
                        "android.permission.ACCESS_BACKGROUND_LOCATION" -> {
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("Auto-granted permission: $permission")
                            }
                            param.result = PackageManager.PERMISSION_GRANTED
                        }
                    }
                }
            })

            Logger.info("Hooked PermissionManager.checkPermission")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("PermissionManager not found: ${it.message}")
            }
        }
    }

    /**
     * Hook ContextImpl.checkPermission
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun hookContextImpl(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cContextImpl = XposedHelpers.findClass(
                "android.app.ContextImpl",
                classLoader
            )

            XposedBridge.hookAllMethods(cContextImpl, "checkPermission", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val permission = param.args.firstOrNull() as? String ?: return

                    when (permission) {
                        "android.permission.NEARBY_WIFI_DEVICES",
                        "android.permission.ACCESS_FINE_LOCATION",
                        "android.permission.ACCESS_COARSE_LOCATION" -> {
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("ContextImpl: Auto-granted permission: $permission")
                            }
                            param.result = PackageManager.PERMISSION_GRANTED
                        }
                    }
                }
            })

            Logger.info("Hooked ContextImpl.checkPermission")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("ContextImpl hook failed: ${it.message}")
            }
        }
    }
}
