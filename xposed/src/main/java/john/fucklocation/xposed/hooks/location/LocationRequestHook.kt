package john.fucklocation.xposed.hooks.location

import android.location.LocationRequest
import android.os.Build
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

/**
 * LocationRequest参数Hook
 *
 * 修改应用请求的定位参数，防止检测到异常的定位行为
 * - 限制更新频率避免过快
 * - 修改精度要求
 * - 限制最小更新距离
 */
object LocationRequestHook {
    operator fun invoke(classLoader: ClassLoader) {
        hookLocationRequestBuilder(classLoader)
        hookLocationRequestCreate(classLoader)
        Logger.info("LocationRequestHook initialized")
    }

    /**
     * Hook LocationRequest.Builder (Android 12+)
     */
    private fun hookLocationRequestBuilder(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        kotlin.runCatching {
            val cBuilder = XposedHelpers.findClass(
                "android.location.LocationRequest\$Builder",
                classLoader
            )

            // Hook build() 方法修改最终参数
            XposedBridge.hookAllMethods(cBuilder, "build", object : XC_MethodHook() {
                @RequiresApi(Build.VERSION_CODES.S)
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val request = param.result as? LocationRequest ?: return

                    // 限制更新间隔不少于5秒，避免频繁定位被检测
                    val interval = request.intervalMillis
                    if (interval < 5000) {
                        XposedHelpers.setLongField(request, "mIntervalMillis", 5000L)
                    }

                    // 限制最小更新距离
                    val minDistance = request.minUpdateDistanceMeters
                    if (minDistance < 10.0f) {
                        XposedHelpers.setFloatField(request, "mMinUpdateDistanceMeters", 10.0f)
                    }

                    // 修改精度要求为平衡模式
                    XposedHelpers.setIntField(request, "mQuality", LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationRequest modified: interval=${request.intervalMillis}ms, quality=${request.quality}")
                    }
                }
            })

            Logger.info("Hooked LocationRequest.Builder")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationRequest.Builder not found (Android < 12): ${it.message}")
            }
        }
    }

    /**
     * Hook LocationRequest.create() 静态方法 (Android 9-11)
     */
    private fun hookLocationRequestCreate(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationRequest = XposedHelpers.findClass(
                "android.location.LocationRequest",
                classLoader
            )

            // Hook create() 静态工厂方法
            XposedBridge.hookAllMethods(cLocationRequest, "create", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val request = param.result as? LocationRequest ?: return

                    // 尝试修改字段（字段名可能因Android版本而异）
                    kotlin.runCatching {
                        // Android 9-11 使用 mInterval
                        val intervalField = request.javaClass.getDeclaredField("mInterval")
                        intervalField.isAccessible = true
                        val interval = intervalField.getLong(request)
                        if (interval < 5000) {
                            intervalField.setLong(request, 5000L)
                        }
                    }

                    kotlin.runCatching {
                        // 修改 mQuality
                        val qualityField = request.javaClass.getDeclaredField("mQuality")
                        qualityField.isAccessible = true
                        qualityField.setInt(request, LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationRequest.create() modified")
                    }
                }
            })

            // Hook setInterval()
            XposedBridge.hookAllMethods(cLocationRequest, "setInterval", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val interval = param.args[0] as? Long ?: return
                    if (interval < 5000) {
                        param.args[0] = 5000L
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("LocationRequest.setInterval: $interval -> 5000ms")
                        }
                    }
                }
            })

            Logger.info("Hooked LocationRequest.create() and setters")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationRequest hook failed: ${it.message}")
            }
        }
    }
}
