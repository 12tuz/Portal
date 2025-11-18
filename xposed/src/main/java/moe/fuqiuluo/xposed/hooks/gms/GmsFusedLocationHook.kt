package moe.fuqiuluo.xposed.hooks.gms

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.BaseLocationHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.BinderUtils

/**
 * Google Play Services FusedLocationProviderClient Hook
 *
 * 这是大多数第三方应用获取位置的主要方式
 * 包路径: com.google.android.gms.location
 */
object GmsFusedLocationHook : BaseLocationHook() {

    operator fun invoke(classLoader: ClassLoader) {
        // 只在 GMS 进程中执行
        hookFusedLocationProviderClient(classLoader)
        hookLocationCallback(classLoader)
        hookLocationResult(classLoader)
        hookLocationAvailability(classLoader)
    }

    private fun hookFusedLocationProviderClient(classLoader: ClassLoader) {
        // 尝试 Hook FusedLocationProviderClient 的内部实现类
        val internalClientNames = listOf(
            "com.google.android.gms.location.internal.zzf", // 旧版本
            "com.google.android.gms.location.internal.zzg",
            "com.google.android.gms.location.internal.zzh",
            "com.google.android.gms.internal.location.zzaz", // 新版本
            "com.google.android.gms.internal.location.zzba",
            "com.google.android.gms.internal.location.zzbb",
            "com.google.android.gms.location.FusedLocationProviderClient"
        )

        for (className in internalClientNames) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)
                hookLocationMethods(clazz)
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GMS class not found: $className")
                }
            }
        }
    }

    private fun hookLocationMethods(clazz: Class<*>) {
        // Hook getLastLocation
        XposedBridge.hookAllMethods(clazz, "getLastLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (!BinderUtils.isTargetApp()) return

                val result = param.result ?: return

                // 处理 Task<Location> 返回类型
                if (result.javaClass.name.contains("Task")) {
                    hookTaskResult(result)
                } else if (result is Location) {
                    param.result = injectLocation(result)
                }
            }
        })

        // Hook getCurrentLocation
        XposedBridge.hookAllMethods(clazz, "getCurrentLocation", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (!BinderUtils.isTargetApp()) return

                val result = param.result ?: return
                if (result.javaClass.name.contains("Task")) {
                    hookTaskResult(result)
                }
            }
        })

        // Hook requestLocationUpdates
        XposedBridge.hookAllMethods(clazz, "requestLocationUpdates", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty()) return

                // 查找 LocationCallback 参数并 Hook
                for (arg in param.args) {
                    if (arg == null) continue
                    val argClassName = arg.javaClass.name
                    if (argClassName.contains("LocationCallback") ||
                        argClassName.contains("LocationListener")) {
                        hookLocationCallbackInstance(arg)
                    }
                }
            }
        })

        if (FakeLoc.enableDebugLog) {
            Logger.debug("Hooked GMS FusedLocation class: ${clazz.name}")
        }
    }

    private fun hookLocationCallback(classLoader: ClassLoader) {
        val callbackNames = listOf(
            "com.google.android.gms.location.LocationCallback",
            "com.google.android.gms.location.LocationListener"
        )

        for (callbackName in callbackNames) {
            kotlin.runCatching {
                val callbackClass = XposedHelpers.findClass(callbackName, classLoader)

                // Hook onLocationResult
                XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.args.isEmpty() || param.args[0] == null) return

                        val locationResult = param.args[0]
                        injectLocationResult(locationResult)
                    }
                })

                // Hook onLocationChanged (for LocationListener)
                XposedBridge.hookAllMethods(callbackClass, "onLocationChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (param.args.isEmpty() || param.args[0] == null) return

                        when (val arg = param.args[0]) {
                            is Location -> {
                                param.args[0] = injectLocation(arg)
                            }
                            is List<*> -> {
                                param.args[0] = arg.filterIsInstance<Location>()
                                    .map { injectLocation(it) }
                            }
                        }
                    }
                })

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Hooked GMS callback: $callbackName")
                }
            }
        }
    }

    private fun hookLocationResult(classLoader: ClassLoader) {
        // Hook LocationResult 类的静态方法和实例方法
        kotlin.runCatching {
            val locationResultClass = XposedHelpers.findClass(
                "com.google.android.gms.location.LocationResult",
                classLoader
            )

            // Hook getLocations()
            XposedBridge.hookAllMethods(locationResultClass, "getLocations", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val locations = param.result as? List<*> ?: return
                    param.result = locations.filterIsInstance<Location>()
                        .map { injectLocation(it) }
                }
            })

            // Hook getLastLocation()
            XposedBridge.hookAllMethods(locationResultClass, "getLastLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val location = param.result as? Location ?: return
                    param.result = injectLocation(location)
                }
            })

            if (FakeLoc.enableDebugLog) {
                Logger.debug("Hooked GMS LocationResult")
            }
        }
    }

    private fun hookLocationAvailability(classLoader: ClassLoader) {
        // Hook LocationAvailability 确保总是返回可用
        kotlin.runCatching {
            val availabilityClass = XposedHelpers.findClass(
                "com.google.android.gms.location.LocationAvailability",
                classLoader
            )

            XposedBridge.hookAllMethods(availabilityClass, "isLocationAvailable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (FakeLoc.enable) {
                        param.result = true
                    }
                }
            })
        }
    }

    private fun hookTaskResult(task: Any) {
        // Hook Task 的 getResult 方法
        kotlin.runCatching {
            val taskClass = task.javaClass

            XposedBridge.hookAllMethods(taskClass, "getResult", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val result = param.result
                    if (result is Location) {
                        param.result = injectLocation(result)
                    }
                }
            })

            // Hook addOnSuccessListener 的回调
            XposedBridge.hookAllMethods(taskClass, "addOnSuccessListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (param.args.isEmpty()) return

                    // 查找 OnSuccessListener 参数
                    for (i in param.args.indices) {
                        val arg = param.args[i] ?: continue
                        if (arg.javaClass.name.contains("OnSuccessListener")) {
                            hookOnSuccessListener(arg)
                        }
                    }
                }
            })
        }
    }

    private fun hookOnSuccessListener(listener: Any) {
        kotlin.runCatching {
            XposedBridge.hookAllMethods(listener.javaClass, "onSuccess", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (param.args.isEmpty() || param.args[0] == null) return

                    val arg = param.args[0]
                    if (arg is Location) {
                        param.args[0] = injectLocation(arg)
                    }
                }
            })
        }
    }

    private fun hookLocationCallbackInstance(callback: Any) {
        val callbackClass = callback.javaClass

        // Hook onLocationResult
        kotlin.runCatching {
            XposedBridge.hookAllMethods(callbackClass, "onLocationResult", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (param.args.isEmpty() || param.args[0] == null) return

                    injectLocationResult(param.args[0])
                }
            })
        }

        // Hook onLocationChanged
        kotlin.runCatching {
            XposedBridge.hookAllMethods(callbackClass, "onLocationChanged", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (param.args.isEmpty() || param.args[0] == null) return

                    when (val arg = param.args[0]) {
                        is Location -> param.args[0] = injectLocation(arg)
                    }
                }
            })
        }
    }

    private fun injectLocationResult(locationResult: Any) {
        kotlin.runCatching {
            // 获取 LocationResult 内部的位置列表
            val locationsField = locationResult.javaClass.getDeclaredField("mLocations")
            locationsField.isAccessible = true

            val locations = locationsField.get(locationResult) as? MutableList<Location>
            if (locations != null) {
                for (i in locations.indices) {
                    locations[i] = injectLocation(locations[i])
                }
                locationsField.set(locationResult, locations)
            }
        }.onFailure {
            // 尝试另一个字段名
            kotlin.runCatching {
                val locationsField = locationResult.javaClass.getDeclaredField("zzb")
                locationsField.isAccessible = true

                val locations = locationsField.get(locationResult) as? MutableList<Location>
                if (locations != null) {
                    for (i in locations.indices) {
                        locations[i] = injectLocation(locations[i])
                    }
                    locationsField.set(locationResult, locations)
                }
            }
        }
    }
}
