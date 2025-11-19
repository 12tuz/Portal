@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package john.fucklocation.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import john.fucklocation.xposed.hooks.LocationManagerHook
import john.fucklocation.xposed.hooks.LocationServiceHook
import john.fucklocation.xposed.hooks.activity.ActivityRecognitionHook
import john.fucklocation.xposed.hooks.location.LocationRequestHook
import john.fucklocation.xposed.hooks.location.Android14PlusHook
import john.fucklocation.xposed.hooks.geocoder.GeocoderHook
import john.fucklocation.xposed.hooks.network.IPLocationHook
import john.fucklocation.xposed.hooks.fused.AndroidFusedLocationProviderHook
import john.fucklocation.xposed.hooks.fused.ThirdPartyLocationHook
import john.fucklocation.xposed.hooks.gms.GmsFusedLocationHook
import john.fucklocation.xposed.hooks.gms.GmsGeofencingHook
import john.fucklocation.xposed.hooks.hms.HuaweiHmsLocationHook
import john.fucklocation.xposed.hooks.oplus.OplusLocationHook
import john.fucklocation.xposed.hooks.permission.PermissionHook
import john.fucklocation.xposed.hooks.miui.MiuiLocationManagerHook
import john.fucklocation.xposed.hooks.miui.XiaomiLocationHook
import john.fucklocation.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import john.fucklocation.xposed.hooks.sensor.SystemSensorManagerHook
import john.fucklocation.xposed.hooks.telephony.TelephonyHook
import john.fucklocation.xposed.hooks.wlan.WlanHook
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.LocationPool
import john.fucklocation.xposed.utils.MemoryLeakPrevention

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return

//        // 宇宙安全声明：以下代码仅供学习交流使用，切勿用于非法用途?
//        System.setProperty("portal.enable", "true")
    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        lpparam ?: return

        val targetPackages = setOf(
            "android",
            "com.android.phone",
            "com.android.location.fused",
            "com.xiaomi.location.fused",
            "com.oplus.location",
            "com.google.android.gms", // Google Play Services
            "com.huawei.hwid", // Huawei HMS Core
            "com.huawei.hms" // Huawei Mobile Services
        )

        if (lpparam.packageName !in targetPackages) {
            return
        }

        val systemClassLoader = (kotlin.runCatching {
            lpparam.classLoader.loadClass("android.app.ActivityThread")
                ?: Class.forName("android.app.ActivityThread")
        }.onFailure {
            Logger.error("Failed to find ActivityThread", it)
        }.getOrNull() ?: return)
            .getMethod("currentActivityThread")
            .invoke(null)
            .javaClass
            .getClassLoader()

        if (systemClassLoader == null) {
            Logger.error("Failed to get system class loader")
            return
        }

        if(System.getProperty("portal.injected_${lpparam.packageName}") == "true") {
            return
        } else {
            System.setProperty("portal.injected_${lpparam.packageName}", "true")
        }

        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found com.android.phone")
                TelephonyHook(lpparam.classLoader)
                MiuiTelephonyManagerHook(lpparam.classLoader)
            }
            "android" -> {
                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true

                // 初始化内存泄漏防护
                MemoryLeakPrevention.initialize()

                // 初始化Location对象池
                LocationPool.warmUp()
                Logger.info("LocationPool initialized: ${LocationPool.size()} objects")

                startFakeLocHook(systemClassLoader)
                TelephonyHook.hookSubOnTransact(lpparam.classLoader)
                WlanHook(systemClassLoader)
                AndroidFusedLocationProviderHook(lpparam.classLoader)
                SystemSensorManagerHook(lpparam.classLoader)
                ActivityRecognitionHook(lpparam.classLoader)
                GeocoderHook(lpparam.classLoader)
                PermissionHook(systemClassLoader)  // Android 13+ 权限Hook
                LocationRequestHook(systemClassLoader)  // LocationRequest参数Hook
                Android14PlusHook(systemClassLoader)  // Android 14/15+ 新API Hook
                IPLocationHook(systemClassLoader)  // IP定位Hook

                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.android.location.fused" -> {
                AndroidFusedLocationProviderHook(lpparam.classLoader)
            }
            "com.xiaomi.location.fused" -> {
                // 小米融合定位服务 - 使用专用深度Hook
                Logger.info("Found com.xiaomi.location.fused")
                XiaomiLocationHook(lpparam.classLoader)
                MiuiLocationManagerHook(lpparam.classLoader)  // 兼容旧版本
            }
            "com.oplus.location" -> {
                OplusLocationHook(lpparam.classLoader)
            }
            "com.google.android.gms" -> {
                // Google Play Services FusedLocationProviderClient
                Logger.info("Found com.google.android.gms")
                GmsFusedLocationHook(lpparam.classLoader)
                GmsGeofencingHook(lpparam.classLoader)
            }
            "com.huawei.hwid", "com.huawei.hms" -> {
                // Huawei Mobile Services FusedLocationProviderClient
                Logger.info("Found Huawei HMS: ${lpparam.packageName}")
                HuaweiHmsLocationHook(lpparam.classLoader)
            }
        }
    }

    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }
}