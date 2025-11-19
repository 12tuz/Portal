package john.fucklocation.xposed.hooks.miui

import android.location.Location
import android.location.LocationProvider
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

/**
 * 小米融合定位服务深度Hook
 *
 * 针对 com.xiaomi.location.fused 进程的专门优化
 * 覆盖小米定位服务的所有核心API
 */
object XiaomiLocationHook : BaseLocationHook() {

    operator fun invoke(classLoader: ClassLoader) {
        Logger.info("Initializing Xiaomi Location Hook")

        // 1. Hook 小米融合定位核心服务
        hookXiaomiFusedLocationProvider(classLoader)

        // 2. Hook 小米融合定位内部实现
        hookXiaomiFusedLocationProviderImpl(classLoader)

        // 3. Hook 小米位置聚合算法
        hookXiaomiLocationAggregator(classLoader)

        // 4. Hook 小米 Task 异步处理
        hookXiaomiTaskHandlers(classLoader)

        // 5. Hook 小米 NetworkLocationProvider
        hookXiaomiNetworkLocationProvider(classLoader)

        Logger.info("Xiaomi Location Hook initialized")
    }

    /**
     * Hook 小米融合定位核心服务类
     */
    private fun hookXiaomiFusedLocationProvider(classLoader: ClassLoader) {
        val possibleClasses = listOf(
            "com.xiaomi.location.fused.FusedLocationProvider",
            "com.xiaomi.location.fused.FusedLocationProviderService",
            "com.android.location.fused.XiaomiFusedLocationProvider"
        )

        possibleClasses.forEach { className ->
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook reportLocation - 融合定位的核心输出方法
                XposedBridge.hookAllMethods(clazz, "reportLocation", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        param.args.forEachIndexed { index, arg ->
                            if (arg is Location) {
                                param.args[index] = injectLocation(arg)
                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("Xiaomi reportLocation hooked: ${arg.provider}")
                                }
                            }
                        }
                    }
                })

                // Hook getLastLocation
                XposedBridge.hookAllMethods(clazz, "getLastLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.result is Location) {
                            param.result = injectLocation(param.result as Location)
                        }
                    }
                })

                // Hook chooseBestLocation - 小米的位置选择算法
                XposedBridge.hookAllMethods(clazz, "chooseBestLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.result is Location) {
                            param.result = injectLocation(param.result as Location)
                        }
                    }
                })

                // Hook combineLocation - 小米的位置合并算法
                XposedBridge.hookAllMethods(clazz, "combineLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.result is Location) {
                            param.result = injectLocation(param.result as Location)
                        }
                    }
                })

                Logger.info("Hooked Xiaomi FusedLocationProvider: $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Xiaomi class not found: $className")
                }
            }
        }
    }

    /**
     * Hook 小米融合定位实现类（通常是混淆后的内部类）
     */
    private fun hookXiaomiFusedLocationProviderImpl(classLoader: ClassLoader) {
        val possibleImplClasses = listOf(
            "com.xiaomi.location.fused.FusedLocationProviderClientImpl",
            "com.xiaomi.location.internal.FusedLocationProviderClientImpl",
            "com.xiaomi.location.fused.impl.FusedLocationProviderImpl"
        )

        possibleImplClasses.forEach { className ->
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook requestLocationUpdates
                XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // Hook LocationCallback
                        param.args.forEach { arg ->
                            if (arg?.javaClass?.name?.contains("LocationCallback") == true) {
                                hookXiaomiLocationCallback(arg)
                            }
                        }
                    }
                })

                // Hook getCurrentLocation
                XposedBridge.hookAllMethods(clazz, "getCurrentLocation", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 可能返回 Task<Location>
                        if (param.result != null && param.result.javaClass.name.contains("Task")) {
                            hookXiaomiTask(param.result)
                        }
                    }
                })

                // Hook flushLocations - 小米特有的位置刷新方法
                XposedBridge.hookAllMethods(clazz, "flushLocations", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("Xiaomi flushLocations intercepted")
                        }
                    }
                })

                Logger.info("Hooked Xiaomi FusedLocationProviderImpl: $className")
            }
        }
    }

    /**
     * Hook 小米位置聚合算法
     */
    private fun hookXiaomiLocationAggregator(classLoader: ClassLoader) {
        val aggregatorClasses = listOf(
            "com.xiaomi.location.fused.LocationAggregator",
            "com.xiaomi.location.fused.FusedLocationAggregator"
        )

        aggregatorClasses.forEach { className ->
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook aggregate - 聚合多个位置源的方法
                XposedBridge.hookAllMethods(clazz, "aggregate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.result is Location) {
                            param.result = injectLocation(param.result as Location)
                        }
                    }
                })

                Logger.info("Hooked Xiaomi LocationAggregator: $className")
            }
        }
    }

    /**
     * Hook 小米 Task 异步处理
     */
    private fun hookXiaomiTaskHandlers(classLoader: ClassLoader) {
        val taskClasses = listOf(
            "com.xiaomi.location.task.Task",
            "com.xiaomi.location.internal.Task"
        )

        taskClasses.forEach { className ->
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook getResult
                XposedBridge.hookAllMethods(clazz, "getResult", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.result is Location) {
                            param.result = injectLocation(param.result as Location)
                        }
                    }
                })

                Logger.info("Hooked Xiaomi Task: $className")
            }
        }
    }

    /**
     * Hook 小米 NetworkLocationProvider
     */
    private fun hookXiaomiNetworkLocationProvider(classLoader: ClassLoader) {
        val providerClasses = listOf(
            "com.xiaomi.location.providers.NetworkLocationProvider",
            "com.android.location.provider.XiaomiNetworkLocationProvider"
        )

        providerClasses.forEach { className ->
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook onGetStatus
                XposedBridge.hookAllMethods(clazz, "onGetStatus", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 返回 Bundle，包含 status 信息
                        if (param.result is Bundle) {
                            val bundle = param.result as Bundle
                            // 确保返回正常状态
                            bundle.putInt("status", LocationProvider.AVAILABLE)
                        }
                    }
                })

                // Hook onSetRequest
                XposedBridge.hookAllMethods(clazz, "onSetRequest", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("Xiaomi NetworkLocationProvider request intercepted")
                        }
                    }
                })

                Logger.info("Hooked Xiaomi NetworkLocationProvider: $className")
            }
        }
    }

    /**
     * Hook 小米 LocationCallback
     */
    private fun hookXiaomiLocationCallback(callback: Any) {
        // Hook onLocationResult
        XposedBridge.hookAllMethods(callback.javaClass, "onLocationResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                val locationResult = param.args[0]
                kotlin.runCatching {
                    // 尝试多种可能的字段名
                    val fieldNames = listOf("mLocations", "locations", "mLocationList")

                    for (fieldName in fieldNames) {
                        val field = locationResult.javaClass.getDeclaredField(fieldName)
                        field.isAccessible = true
                        val value = field.get(locationResult)

                        if (value is MutableList<*> && value.isNotEmpty() && value[0] is Location) {
                            @Suppress("UNCHECKED_CAST")
                            val locations = value as MutableList<Location>
                            for (i in locations.indices) {
                                locations[i] = injectLocation(locations[i])
                            }
                            return@beforeHookedMethod
                        }
                    }
                }
            }
        })

        // Hook onLocationChanged
        XposedBridge.hookAllMethods(callback.javaClass, "onLocationChanged", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                if (param.args[0] is Location) {
                    param.args[0] = injectLocation(param.args[0] as Location)
                }
            }
        })
    }

    /**
     * Hook 小米 Task 异步结果
     */
    private fun hookXiaomiTask(task: Any) {
        XposedBridge.hookAllMethods(task.javaClass, "getResult", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.result is Location) {
                    param.result = injectLocation(param.result as Location)
                }
            }
        })

        // Hook addOnSuccessListener
        XposedBridge.hookAllMethods(task.javaClass, "addOnSuccessListener", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return

                // Hook 传入的 OnSuccessListener
                param.args.forEach { arg ->
                    if (arg != null && arg.javaClass.name.contains("OnSuccessListener")) {
                        hookOnSuccessListener(arg)
                    }
                }
            }
        })
    }

    /**
     * Hook OnSuccessListener
     */
    private fun hookOnSuccessListener(listener: Any) {
        XposedBridge.hookAllMethods(listener.javaClass, "onSuccess", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                if (param.args[0] is Location) {
                    param.args[0] = injectLocation(param.args[0] as Location)
                }
            }
        })
    }
}
