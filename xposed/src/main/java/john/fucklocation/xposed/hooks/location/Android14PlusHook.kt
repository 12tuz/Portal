package john.fucklocation.xposed.hooks.location

import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

/**
 * Android 14+ (API 34+) 新增定位API Hook
 *
 * 支持的新API:
 * - getLocationProviderProperties() (Android 14+)
 * - hasProvider() (Android 15+)
 * - isLocationEnabledForUser() (Android 15+)
 * - getProviderProperties() (Android 14+)
 * - 精确/粗略位置区分
 * - 增强的后台定位限制
 */
object Android14PlusHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Logger.info("Android14PlusHook skipped: Android < 14")
            return
        }

        hookLocationProviderProperties(classLoader)
        hookGetProviderProperties(classLoader)

        if (Build.VERSION.SDK_INT >= 35) { // Android 15
            hookHasProvider(classLoader)
            hookIsLocationEnabledForUser(classLoader)
            hookBackgroundLocationThrottle(classLoader)
        }

        Logger.info("Android14PlusHook initialized (API ${Build.VERSION.SDK_INT})")
    }

    /**
     * Hook getLocationProviderProperties() - Android 14+
     *
     * 返回Provider的精度、功耗、成本等特性，需要伪造GPS特性避免检测
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookLocationProviderProperties(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationManager = XposedHelpers.findClass(
                "android.location.LocationManager",
                classLoader
            )

            XposedBridge.hookAllMethods(cLocationManager, "getLocationProviderProperties", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val provider = param.args.firstOrNull() as? String ?: return
                    val properties = param.result ?: return

                    // 只修改GPS和Network provider的属性
                    when (provider) {
                        LocationManager.GPS_PROVIDER -> {
                            // 伪造GPS特性：高精度、高功耗、无成本
                            kotlin.runCatching {
                                XposedHelpers.setBooleanField(properties, "mHasAltitudeSupport", true)
                                XposedHelpers.setBooleanField(properties, "mHasSpeedSupport", true)
                                XposedHelpers.setBooleanField(properties, "mHasBearingSupport", true)

                                // 设置精度 (5-10米)
                                XposedHelpers.setIntField(properties, "mAccuracy", 1) // ACCURACY_FINE

                                // 设置功耗 (高功耗是GPS特征)
                                XposedHelpers.setIntField(properties, "mPowerUsage", 3) // POWER_HIGH
                            }

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("Modified GPS provider properties")
                            }
                        }
                        LocationManager.NETWORK_PROVIDER -> {
                            // 伪造Network特性：中等精度、低功耗
                            kotlin.runCatching {
                                XposedHelpers.setBooleanField(properties, "mHasAltitudeSupport", false)
                                XposedHelpers.setBooleanField(properties, "mHasSpeedSupport", false)
                                XposedHelpers.setBooleanField(properties, "mHasBearingSupport", false)

                                XposedHelpers.setIntField(properties, "mAccuracy", 2) // ACCURACY_COARSE
                                XposedHelpers.setIntField(properties, "mPowerUsage", 1) // POWER_LOW
                            }

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("Modified Network provider properties")
                            }
                        }
                    }
                }
            })

            Logger.info("Hooked LocationManager.getLocationProviderProperties()")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook getLocationProviderProperties: ${it.message}")
            }
        }
    }

    /**
     * Hook getProviderProperties() - Android 14+
     *
     * 备选API，与getLocationProviderProperties类似
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun hookGetProviderProperties(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationManager = XposedHelpers.findClass(
                "android.location.LocationManager",
                classLoader
            )

            XposedBridge.hookAllMethods(cLocationManager, "getProviderProperties", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val provider = param.args.firstOrNull() as? String ?: return
                    val properties = param.result ?: return

                    when (provider) {
                        LocationManager.GPS_PROVIDER -> {
                            kotlin.runCatching {
                                XposedHelpers.setBooleanField(properties, "mHasAltitudeSupport", true)
                                XposedHelpers.setBooleanField(properties, "mHasSpeedSupport", true)
                                XposedHelpers.setBooleanField(properties, "mHasBearingSupport", true)
                                XposedHelpers.setIntField(properties, "mAccuracy", 1)
                                XposedHelpers.setIntField(properties, "mPowerUsage", 3)
                            }
                        }
                    }
                }
            })

            Logger.info("Hooked LocationManager.getProviderProperties()")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook getProviderProperties: ${it.message}")
            }
        }
    }

    /**
     * Hook hasProvider() - Android 15+
     *
     * 新的Provider检测API，需要确保返回一致性
     */
    private fun hookHasProvider(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationManager = XposedHelpers.findClass(
                "android.location.LocationManager",
                classLoader
            )

            XposedBridge.hookAllMethods(cLocationManager, "hasProvider", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val provider = param.args.firstOrNull() as? String ?: return

                    // 确保GPS和Network provider总是可用
                    when (provider) {
                        LocationManager.GPS_PROVIDER,
                        LocationManager.NETWORK_PROVIDER,
                        LocationManager.FUSED_PROVIDER -> {
                            param.result = true

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("hasProvider($provider) -> true")
                            }
                        }
                    }
                }
            })

            Logger.info("Hooked LocationManager.hasProvider() for Android 15")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook hasProvider: ${it.message}")
            }
        }
    }

    /**
     * Hook isLocationEnabledForUser() - Android 15+
     *
     * 新增的per-user位置开关检测API
     */
    private fun hookIsLocationEnabledForUser(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationManager = XposedHelpers.findClass(
                "android.location.LocationManager",
                classLoader
            )

            XposedBridge.hookAllMethods(cLocationManager, "isLocationEnabledForUser", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    // 总是返回true，表示位置服务已启用
                    param.result = true

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("isLocationEnabledForUser() -> true")
                    }
                }
            })

            Logger.info("Hooked LocationManager.isLocationEnabledForUser() for Android 15")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook isLocationEnabledForUser: ${it.message}")
            }
        }
    }

    /**
     * Hook Android 15增强的后台定位限流
     *
     * Android 15加强了后台应用的定位频率限制
     */
    private fun hookBackgroundLocationThrottle(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cLocationManagerService = XposedHelpers.findClass(
                "com.android.server.location.LocationManagerService",
                classLoader
            )

            // Hook isThrottlingExemptLocked - 豁免后台限流
            XposedBridge.hookAllMethods(cLocationManagerService, "isThrottlingExemptLocked", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    // 返回true表示豁免限流
                    param.result = true

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("isThrottlingExemptLocked() -> true (exempt from throttling)")
                    }
                }
            })

            Logger.info("Hooked background location throttling for Android 15")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook background throttling: ${it.message}")
            }
        }
    }
}
