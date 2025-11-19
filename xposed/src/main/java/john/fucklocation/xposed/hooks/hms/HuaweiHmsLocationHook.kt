package john.fucklocation.xposed.hooks.hms

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.BinderUtils

/**
 * 华为HMS定位服务Hook
 * 支持华为/荣耀设备的HMS Location Kit
 *
 * 核心API:
 * - com.huawei.hms.location.FusedLocationProviderClient
 * - com.huawei.location.service.FusedLocationProviderService
 */
object HuaweiHmsLocationHook : BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if (!initDivineService("HuaweiHmsLocation")) {
            Logger.error("Failed to init DivineService in HuaweiHmsLocationHook")
            return
        }

        // Hook HMS FusedLocationProviderClient
        hookHmsFusedLocationClient(classLoader)

        // Hook HMS LocationCallback
        hookHmsLocationCallback(classLoader)

        // Hook HMS Location Service (底层服务)
        hookHmsLocationService(classLoader)
    }

    /**
     * Hook HMS FusedLocationProviderClient API
     */
    private fun hookHmsFusedLocationClient(classLoader: ClassLoader) {
        // 华为HMS可能的类名
        val possibleClasses = listOf(
            "com.huawei.hms.location.FusedLocationProviderClient",
            "com.huawei.location.fusedlocation.FusedLocationProviderClient",
            "com.huawei.location.service.FusedLocationProviderService"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook getLastLocation
                XposedBridge.hookAllMethods(clazz, "getLastLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (!BinderUtils.isTargetApp()) return

                        val result = param.result
                        if (result != null) {
                            // HMS使用Task<Location>异步返回
                            hookHmsTask(result)
                        }
                    }
                })

                // Hook requestLocationUpdates
                XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (!BinderUtils.isTargetApp()) return

                        // Hook传入的LocationCallback
                        for (arg in param.args) {
                            if (arg?.javaClass?.name?.contains("LocationCallback") == true) {
                                hookHmsLocationCallbackInstance(arg)
                            }
                        }
                    }
                })

                // Hook removeLocationUpdates
                XposedBridge.hookAllMethods(clazz, "removeLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("HMS removeLocationUpdates called")
                        }
                    }
                })

                Logger.info("Hooked Huawei HMS FusedLocationClient: $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("HMS class not found: $className")
                }
            }
        }
    }

    /**
     * Hook HMS Task<Location> 异步结果
     */
    private fun hookHmsTask(task: Any) {
        kotlin.runCatching {
            // HMS Task API
            val taskClass = task.javaClass

            // Hook addOnSuccessListener
            XposedBridge.hookAllMethods(taskClass, "addOnSuccessListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    // 获取原始listener
                    val originalListener = param.args.firstOrNull() ?: return

                    // 包装listener以拦截Location结果
                    val wrappedListener = java.lang.reflect.Proxy.newProxyInstance(
                        originalListener.javaClass.classLoader,
                        originalListener.javaClass.interfaces
                    ) { _, method, args ->
                        if (method.name == "onSuccess" && args != null && args.isNotEmpty()) {
                            val location = args[0]
                            if (location is Location) {
                                // 注入虚拟位置
                                args[0] = injectLocation(location)
                            }
                        }
                        method.invoke(originalListener, *(args ?: emptyArray()))
                    }

                    // 替换为包装后的listener
                    param.args[param.args.indexOfFirst { it == originalListener }] = wrappedListener
                }
            })

            // Hook getResult() 同步获取
            XposedBridge.hookAllMethods(taskClass, "getResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val result = param.result
                    if (result is Location) {
                        param.result = injectLocation(result)
                    }
                }
            })
        }
    }

    /**
     * Hook HMS LocationCallback
     */
    private fun hookHmsLocationCallback(classLoader: ClassLoader) {
        val callbackClasses = listOf(
            "com.huawei.hms.location.LocationCallback",
            "com.huawei.location.callback.LocationCallback"
        )

        for (className in callbackClasses) {
            kotlin.runCatching {
                val callbackClass = XposedHelpers.findClass(className, classLoader)
                hookHmsLocationCallbackInstance(callbackClass)
                Logger.info("Hooked HMS LocationCallback: $className")
            }
        }
    }

    /**
     * Hook LocationCallback实例
     */
    private fun hookHmsLocationCallbackInstance(callback: Any) {
        val callbackClass = if (callback is Class<*>) callback else callback.javaClass

        // Hook onLocationResult
        XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                val locationResult = param.args[0]
                // 注入LocationResult中的位置
                injectHmsLocationResult(locationResult)
            }
        })

        // Hook onLocationAvailability
        XposedBridge.hookAllMethods(callbackClass, "onLocationAvailability", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("HMS onLocationAvailability called")
                }
            }
        })
    }

    /**
     * 注入HMS LocationResult
     */
    private fun injectHmsLocationResult(locationResult: Any) {
        // HMS LocationResult可能的字段名
        val possibleFieldNames = listOf(
            "mLocations",
            "locations",
            "mLocationList",
            "a", "b", "c" // 混淆后的可能名称
        )

        var injected = false

        // 策略1：尝试已知字段名
        for (fieldName in possibleFieldNames) {
            kotlin.runCatching {
                val locationsField = locationResult.javaClass.getDeclaredField(fieldName)
                locationsField.isAccessible = true
                val value = locationsField.get(locationResult)
                if (value is MutableList<*> && value.isNotEmpty() && value[0] is Location) {
                    @Suppress("UNCHECKED_CAST")
                    val locations = value as MutableList<Location>
                    for (i in locations.indices) {
                        locations[i] = injectLocation(locations[i])
                    }
                    injected = true
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("HMS LocationResult injected via field: $fieldName")
                    }
                    return
                }
            }
        }

        // 策略2：字段类型扫描
        if (!injected) {
            kotlin.runCatching {
                locationResult.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    val value = field.get(locationResult)
                    if (value is MutableList<*> && value.isNotEmpty() && value[0] is Location) {
                        @Suppress("UNCHECKED_CAST")
                        val locations = value as MutableList<Location>
                        for (i in locations.indices) {
                            locations[i] = injectLocation(locations[i])
                        }
                        injected = true
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("HMS LocationResult injected via field scan: ${field.name}")
                        }
                        return@forEach
                    }
                }
            }.onFailure {
                Logger.error("HMS LocationResult injection failed", it)
            }
        }

        if (!injected && FakeLoc.enableDebugLog) {
            Logger.warn("HMS LocationResult injection: No suitable field found")
        }
    }

    /**
     * Hook HMS底层定位服务
     */
    private fun hookHmsLocationService(classLoader: ClassLoader) {
        kotlin.runCatching {
            // 华为系统服务
            val serviceClass = XposedHelpers.findClass(
                "com.huawei.location.service.LocationManagerService",
                classLoader
            )

            // Hook getLastLocation
            XposedBridge.hookAllMethods(serviceClass, "getLastLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val result = param.result
                    if (result is Location) {
                        param.result = injectLocation(result)
                    }
                }
            })

            // Hook reportLocation
            XposedBridge.hookAllMethods(serviceClass, "reportLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (param.args.isEmpty()) return

                    for (i in param.args.indices) {
                        val arg = param.args[i]
                        if (arg is Location) {
                            param.args[i] = injectLocation(arg)
                        }
                    }
                }
            })

            Logger.info("Hooked HMS LocationManagerService")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("HMS LocationManagerService not found (normal on non-Huawei devices)")
            }
        }
    }
}
