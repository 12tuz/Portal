package john.fucklocation.xposed.hooks.oplus

import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.hooks.blindhook.BlindHookLocation
import john.fucklocation.xposed.hooks.blindhook.BlindHookLocation.invoke
import john.fucklocation.xposed.hooks.fused.ThirdPartyLocationHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.hookMethodAfter
import john.fucklocation.xposed.utils.onceHookMethodBefore
import john.fucklocation.xposed.utils.toClass
import java.lang.reflect.Modifier

/**
 * OPLUS/ColorOS 定位Hook
 *
 * 支持OPPO特有的定位服务：
 * - OplusLocationManager: ColorOS专有定位API
 * - OplusLocationService: 系统服务实现
 * - OplusNetworkLocationProvider: 网络定位提供者
 */
object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        // 通用第三方定位Hook
        ThirdPartyLocationHook(classLoader)

        // ColorOS专有API Hook
        hookOplusLocationManager(classLoader)
        hookOplusLocationService(classLoader)
        hookOplusNetworkLocationProvider(classLoader)

        Logger.info("OplusLocationHook initialized for ColorOS")
    }

    /**
     * Hook OplusLocationManager - ColorOS用户空间API
     */
    private fun hookOplusLocationManager(classLoader: ClassLoader) {
        val possibleClasses = listOf(
            "com.oplus.location.OplusLocationManager",
            "com.oppo.location.OppoLocationManager",
            "com.coloros.location.ColorOSLocationManager"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook getLastLocation
                XposedBridge.hookAllMethods(clazz, "getLastLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        val location = param.result as? Location ?: return
                        param.result = injectLocation(location)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("$className.getLastLocation hooked")
                        }
                    }
                })

                // Hook getLastKnownLocation
                XposedBridge.hookAllMethods(clazz, "getLastKnownLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        val location = param.result as? Location ?: return
                        param.result = injectLocation(location)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("$className.getLastKnownLocation hooked")
                        }
                    }
                })

                // Hook requestLocationUpdates
                XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 查找LocationListener参数并包装
                        for (i in param.args.indices) {
                            if (param.args[i] is LocationListener) {
                                val originalListener = param.args[i] as LocationListener
                                param.args[i] = LocationListener { location ->
                                    originalListener.onLocationChanged(injectLocation(location))
                                }

                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("$className.requestLocationUpdates listener wrapped")
                                }
                                break
                            }
                        }
                    }
                })

                Logger.info("Hooked $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("$className not found: ${it.message}")
                }
            }
        }
    }

    /**
     * Hook OplusLocationService - ColorOS系统服务
     */
    private fun hookOplusLocationService(classLoader: ClassLoader) {
        val possibleClasses = listOf(
            "com.oplus.server.location.OplusLocationService",
            "com.oppo.server.location.OppoLocationService",
            "com.coloros.server.location.ColorOSLocationService"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook getLocation 方法
                XposedBridge.hookAllMethods(clazz, "getLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        val location = param.result as? Location ?: return
                        param.result = injectLocation(location)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("$className.getLocation hooked")
                        }
                    }
                })

                // Hook reportLocation 方法（系统内部报告位置）
                XposedBridge.hookAllMethods(clazz, "reportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        for (i in param.args.indices) {
                            if (param.args[i] is Location) {
                                param.args[i] = injectLocation(param.args[i] as Location)

                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("$className.reportLocation hooked")
                                }
                                break
                            }
                        }
                    }
                })

                Logger.info("Hooked $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("$className not found: ${it.message}")
                }
            }
        }
    }

    /**
     * Hook OplusNetworkLocationProvider - ColorOS网络定位提供者
     */
    private fun hookOplusNetworkLocationProvider(classLoader: ClassLoader) {
        val possibleClasses = listOf(
            "com.oplus.location.OplusNetworkLocationProvider",
            "com.oppo.location.OppoNetworkLocationProvider",
            "com.coloros.location.ColorOSNetworkLocationProvider"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook getLocation
                XposedBridge.hookAllMethods(clazz, "getLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        val location = param.result as? Location ?: return
                        param.result = injectLocation(location)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("$className.getLocation hooked")
                        }
                    }
                })

                Logger.info("Hooked $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("$className not found: ${it.message}")
                }
            }
        }
    }
}