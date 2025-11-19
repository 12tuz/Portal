package john.fucklocation.xposed.hooks.miui

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.hooks.blindhook.BlindHookLocation
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

object MiuiLocationManagerHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        // Hook 小米的位置融合服务
        hookMiuiFusedLocationProvider(classLoader)

        // Hook 小米定位管理器
        hookMiuiLocationManager(classLoader)
    }

    private fun hookMiuiFusedLocationProvider(classLoader: ClassLoader) {
        // 小米 Fused Location Provider 的可能类名
        val possibleClasses = listOf(
            "com.xiaomi.location.fused.FusedLocationProviderClient",
            "com.xiaomi.location.internal.FusedLocationProviderClientImpl",
            "com.android.location.fused.MiuiFusedLocationProvider"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook 所有可能返回 Location 的方法
                XposedBridge.hookAllMethods(clazz, "getLastLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        val result = param.result
                        if (result is Location) {
                            param.result = injectLocation(result)
                        }
                    }
                })

                XposedBridge.hookAllMethods(clazz, "getCurrentLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        val result = param.result
                        if (result is Location) {
                            param.result = injectLocation(result)
                        }
                    }
                })

                XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        // Hook 传入的 LocationCallback
                        for (arg in param.args) {
                            if (arg?.javaClass?.name?.contains("LocationCallback") == true) {
                                hookLocationCallback(arg)
                            }
                        }
                    }
                })

                Logger.info("Hooked Xiaomi FusedLocation: $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Xiaomi class not found: $className")
                }
            }
        }
    }

    private fun hookMiuiLocationManager(classLoader: ClassLoader) {
        // Hook MIUI 特有的位置管理器
        kotlin.runCatching {
            val miuiLocationManager = XposedHelpers.findClass(
                "com.android.server.location.MiuiLocationManagerService",
                classLoader
            )

            // 使用 BlindHook 自动覆盖所有 Location 相关方法
            BlindHookLocation(miuiLocationManager, classLoader)

            Logger.info("Hooked MiuiLocationManagerService")
        }
    }

    private fun hookLocationCallback(callback: Any) {
        XposedBridge.hookAllMethods(callback.javaClass, "onLocationResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                val locationResult = param.args[0]
                // 尝试修改 LocationResult 中的位置
                kotlin.runCatching {
                    val locationsField = locationResult.javaClass.getDeclaredField("mLocations")
                    locationsField.isAccessible = true
                    val value = locationsField.get(locationResult)
                    if (value is MutableList<*> && value.isNotEmpty() && value[0] is Location) {
                        @Suppress("UNCHECKED_CAST")
                        val locations = value as MutableList<Location>
                        for (i in locations.indices) {
                            locations[i] = injectLocation(locations[i])
                        }
                    }
                }
            }
        })

        XposedBridge.hookAllMethods(callback.javaClass, "onLocationChanged", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                val location = param.args[0]
                if (location is Location) {
                    param.args[0] = injectLocation(location)
                }
            }
        })
    }
}
