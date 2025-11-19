package john.fucklocation.xposed.hooks.utils

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

/**
 * Task Hook统一处理器
 *
 * 提取Google Play Services和Huawei HMS中Task/Future模式的公共Hook逻辑
 * 统一处理异步定位请求的结果修改
 */
object TaskHookHelper {

    /**
     * Hook Google Task<Location> 类型的方法
     *
     * 适用于：
     * - FusedLocationProviderClient.getLastLocation()
     * - FusedLocationProviderClient.getCurrentLocation()
     * - 其他返回 Task<Location> 的方法
     *
     * @param clazz Hook目标类
     * @param methodName 方法名
     * @param locationHook 位置注入器
     */
    fun hookGoogleTask(
        clazz: Class<*>,
        methodName: String,
        locationHook: BaseLocationHook
    ) {
        kotlin.runCatching {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val task = param.result ?: return

                    // Hook Task.addOnSuccessListener
                    hookTaskOnSuccessListener(task, locationHook)

                    // Hook Task.getResult
                    hookTaskGetResult(task, locationHook)

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("Hooked Google Task: ${clazz.name}.$methodName")
                    }
                }
            })

            Logger.info("TaskHookHelper: Hooked Google Task method ${clazz.name}.$methodName")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook Google Task ${clazz.name}.$methodName: ${it.message}")
            }
        }
    }

    /**
     * Hook Huawei Task<Location> 类型的方法
     *
     * 适用于：
     * - HuaweiFusedLocationProviderClient.getLastLocation()
     * - HuaweiFusedLocationProviderClient.getLastLocationWithAddress()
     *
     * @param clazz Hook目标类
     * @param methodName 方法名
     * @param locationHook 位置注入器
     */
    fun hookHuaweiTask(
        clazz: Class<*>,
        methodName: String,
        locationHook: BaseLocationHook
    ) {
        kotlin.runCatching {
            XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val task = param.result ?: return

                    // Hook Task.addOnSuccessListener (Huawei版本)
                    hookHuaweiTaskOnSuccessListener(task, locationHook)

                    // Hook Task.getResult
                    hookTaskGetResult(task, locationHook)

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("Hooked Huawei Task: ${clazz.name}.$methodName")
                    }
                }
            })

            Logger.info("TaskHookHelper: Hooked Huawei Task method ${clazz.name}.$methodName")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook Huawei Task ${clazz.name}.$methodName: ${it.message}")
            }
        }
    }

    /**
     * Hook Task.addOnSuccessListener (Google版本)
     */
    private fun hookTaskOnSuccessListener(task: Any, locationHook: BaseLocationHook) {
        kotlin.runCatching {
            val taskClass = task.javaClass

            // 查找所有可能的 addOnSuccessListener 方法
            val methods = taskClass.methods.filter {
                it.name == "addOnSuccessListener"
            }

            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 包装原始的OnSuccessListener
                        for (i in param.args.indices) {
                            val arg = param.args[i] ?: continue

                            // 检查是否是OnSuccessListener接口
                            if (arg.javaClass.interfaces.any { it.name.contains("OnSuccessListener") }) {
                                param.args[i] = wrapGoogleSuccessListener(arg, locationHook)

                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("Wrapped Google OnSuccessListener")
                                }
                                break
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * Hook Task.addOnSuccessListener (Huawei版本)
     */
    private fun hookHuaweiTaskOnSuccessListener(task: Any, locationHook: BaseLocationHook) {
        kotlin.runCatching {
            val taskClass = task.javaClass

            val methods = taskClass.methods.filter {
                it.name == "addOnSuccessListener"
            }

            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        for (i in param.args.indices) {
                            val arg = param.args[i] ?: continue

                            if (arg.javaClass.interfaces.any { it.name.contains("OnSuccessListener") }) {
                                param.args[i] = wrapHuaweiSuccessListener(arg, locationHook)

                                if (FakeLoc.enableDebugLog) {
                                    Logger.debug("Wrapped Huawei OnSuccessListener")
                                }
                                break
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * Hook Task.getResult()
     */
    private fun hookTaskGetResult(task: Any, locationHook: BaseLocationHook) {
        kotlin.runCatching {
            val taskClass = task.javaClass

            val methods = taskClass.methods.filter {
                it.name == "getResult"
            }

            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        val result = param.result
                        if (result is Location) {
                            param.result = locationHook.injectLocation(result)

                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("Injected location in Task.getResult()")
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * 包装Google OnSuccessListener
     */
    private fun wrapGoogleSuccessListener(listener: Any, locationHook: BaseLocationHook): Any {
        return XposedHelpers.newInstance(
            listener.javaClass,
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onSuccess" && args != null && args.isNotEmpty()) {
                        val result = args[0]
                        if (result is Location) {
                            // 注入虚拟位置后调用原始listener
                            val fakeLocation = locationHook.injectLocation(result)
                            return XposedHelpers.callMethod(listener, "onSuccess", fakeLocation)
                        }
                    }
                    return XposedHelpers.callMethod(listener, method.name, *args ?: emptyArray())
                }
            }
        )
    }

    /**
     * 包装Huawei OnSuccessListener
     */
    private fun wrapHuaweiSuccessListener(listener: Any, locationHook: BaseLocationHook): Any {
        return XposedHelpers.newInstance(
            listener.javaClass,
            object : java.lang.reflect.InvocationHandler {
                override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                    if (method.name == "onSuccess" && args != null && args.isNotEmpty()) {
                        val result = args[0]

                        // Huawei可能返回Location或LocationWithAddress
                        when {
                            result is Location -> {
                                val fakeLocation = locationHook.injectLocation(result)
                                return XposedHelpers.callMethod(listener, "onSuccess", fakeLocation)
                            }
                            result?.javaClass?.name?.contains("LocationWithAddress") == true -> {
                                // 处理LocationWithAddress
                                kotlin.runCatching {
                                    val location = XposedHelpers.callMethod(result, "getLocation") as? Location
                                    if (location != null) {
                                        val fakeLocation = locationHook.injectLocation(location)
                                        XposedHelpers.setObjectField(result, "mLocation", fakeLocation)
                                    }
                                }
                                return XposedHelpers.callMethod(listener, "onSuccess", result)
                            }
                        }
                    }
                    return XposedHelpers.callMethod(listener, method.name, *args ?: emptyArray())
                }
            }
        )
    }

    /**
     * 批量Hook多个Google Task方法
     */
    fun hookGoogleTaskMethods(
        clazz: Class<*>,
        methodNames: List<String>,
        locationHook: BaseLocationHook
    ) {
        methodNames.forEach { methodName ->
            hookGoogleTask(clazz, methodName, locationHook)
        }
    }

    /**
     * 批量Hook多个Huawei Task方法
     */
    fun hookHuaweiTaskMethods(
        clazz: Class<*>,
        methodNames: List<String>,
        locationHook: BaseLocationHook
    ) {
        methodNames.forEach { methodName ->
            hookHuaweiTask(clazz, methodName, locationHook)
        }
    }
}
