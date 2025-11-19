package john.fucklocation.xposed.hooks.fused

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import john.fucklocation.xposed.BaseLocationHook
import john.fucklocation.xposed.hooks.blindhook.BlindHookLocation
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.beforeHook
import john.fucklocation.xposed.utils.hookMethodAfter
import john.fucklocation.xposed.utils.toClass

object AndroidFusedLocationProviderHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        val cFusedLocationProvider = "com.android.location.fused.FusedLocationProvider".toClass(classLoader)
        if (cFusedLocationProvider == null) {
            Logger.warn("Failed to find FusedLocationProvider")
            return
        }

        if(!initDivineService("AndroidFusedLocationProvider")) {
            Logger.error("Failed to init DivineService in AndroidFusedLocationProvider")
            return
        }

        // Hook chooseBestLocation - 融合算法的核心
        cFusedLocationProvider.hookMethodAfter("chooseBestLocation", Location::class.java, Location::class.java) {
            if (result == null) return@hookMethodAfter

            if (FakeLoc.enable) {
                result = injectLocation(result as Location)
            }
        }

        // 强制拦截所有输入位置，防止真实位置进入融合算法
        XposedBridge.hookAllMethods(cFusedLocationProvider, "onLocationChanged", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!FakeLoc.enable) return
                if (param.args.isEmpty() || param.args[0] == null) return

                val location = param.args[0] as? Location ?: return

                // 拦截所有非虚拟位置来源
                if (FakeLoc.disableNetworkLocation && location.provider == "network") {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("FusedProvider: 拦截真实网络定位 - ${location.latitude},${location.longitude}")
                    }
                    param.result = null
                    return
                }

                // 注入虚拟位置
                param.args[0] = injectLocation(location)
            }
        })

        // Hook 报告位置的方法，确保输出一致性
        XposedBridge.hookAllMethods(cFusedLocationProvider, "reportLocation", object : XC_MethodHook() {
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

        val cChildLocationListener = "com.android.location.fused.FusedLocationProvider\$ChildLocationListener".toClass(classLoader)
        if (cChildLocationListener == null) {
            Logger.warn("Failed to find ChildLocationListener")
            return
        }

        BlindHookLocation(cChildLocationListener, classLoader)
    }
}