@file:Suppress("UNCHECKED_CAST", "PrivateApi")
package john.fucklocation.xposed.hooks.wlan

import android.net.wifi.WifiInfo
import android.os.Build
import android.util.ArrayMap
import dalvik.system.PathClassLoader
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.BinderUtils
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.afterHook
import john.fucklocation.xposed.utils.beforeHook
import john.fucklocation.xposed.utils.hookAllMethods
import john.fucklocation.xposed.utils.hookMethodAfter
import john.fucklocation.xposed.utils.toClass

object WlanHook {
    operator fun invoke(classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cSystemServerClassLoaderFactory = XposedHelpers.findClassIfExists("com.android.internal.os.SystemServerClassLoaderFactory", classLoader)
            if (cSystemServerClassLoaderFactory == null) {
                Logger.warn("Failed to find SystemServerClassLoaderFactory")
                return
            }
            val sLoadedPaths = XposedHelpers.getStaticObjectField(cSystemServerClassLoaderFactory, "sLoadedPaths") as ArrayMap<String, PathClassLoader>
            val wifiClassLoader = sLoadedPaths.firstNotNullOfOrNull {
                if (it.key.contains("service-wifi.jar")) it.value else null
            }
            if (wifiClassLoader == null) {
                Logger.warn("Failed to find wifiClassLoader")
                return
            }
            val wifiClazz = "com.android.server.wifi.WifiServiceImpl".toClass(wifiClassLoader)
            if (wifiClazz == null) {
                Logger.warn("Failed to find WifiServiceImpl class")
                return
            }
            hookWifiServiceImpl(wifiClazz)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cSystemServiceManager = XposedHelpers.findClassIfExists("com.android.server.SystemServiceManager", classLoader)
            if (cSystemServiceManager == null) {
                Logger.warn("Failed to find SystemServiceManager")
                return
            }
            cSystemServiceManager.hookAllMethods("loadClassFromLoader", afterHook {
                if (args[0] == "com.android.server.wifi.WifiService") {
                    kotlin.runCatching {
                        val classloader = args[1] as PathClassLoader
                        val wifiClazz = classloader.loadClass("com.android.server.wifi.WifiServiceImpl")
                        hookWifiServiceImpl(wifiClazz)
                    }.onFailure {
                        Logger.error("Failed to hook WifiService", it)
                    }
                }
            })
        }
    }

    private fun hookWifiServiceImpl(wifiClazz: Class<*>) {
        if (!FakeLoc.hookWifi) return

        wifiClazz.hookAllMethods("getConnectionInfo", beforeHook {
            val packageName = args[0] as String
            if (FakeLoc.enableDebugLog)
                Logger.debug("In getConnectionInfo with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if (FakeLoc.enableMockWifi && !BinderUtils.isSystemPackages(packageName)) {
                val wifiInfo = WifiInfo::class.java.getConstructor().newInstance()

                // 基于位置生成稳定的随机MAC地址，避免固定值被检测
                val (lat, lon) = FakeLoc.getCoordinates()
                val seed = ((lat * 1000000).toLong() xor (lon * 1000000).toLong())
                val random = kotlin.random.Random(seed)

                // 生成本地管理MAC地址 (第一个字节第二位为1)
                val macBytes = ByteArray(6)
                random.nextBytes(macBytes)
                macBytes[0] = (macBytes[0].toInt() or 0x02).toByte() // 设置本地管理位
                macBytes[0] = (macBytes[0].toInt() and 0xFE.toByte().toInt()).toByte() // 清除多播位
                val macAddress = macBytes.joinToString(":") { "%02x".format(it) }

                XposedHelpers.callMethod(wifiInfo, "setMacAddress", macAddress)
                XposedHelpers.callMethod(wifiInfo, "setBSSID", macAddress)

                // 信号强度 (-50 到 -80 dBm 是正常范围)
                kotlin.runCatching {
                    XposedHelpers.callMethod(wifiInfo, "setRssi", -65 + kotlin.random.Random.nextInt(-10, 10))
                }

                // 链接速度 (Mbps)
                kotlin.runCatching {
                    XposedHelpers.callMethod(wifiInfo, "setLinkSpeed", 72 + kotlin.random.Random.nextInt(-20, 50))
                }

                // 频率 (2.4GHz: 2412-2484, 5GHz: 5180-5825)
                kotlin.runCatching {
                    val frequency = if (kotlin.random.Random.nextBoolean()) {
                        2412 + kotlin.random.Random.nextInt(0, 13) * 5  // 2.4GHz 频段
                    } else {
                        5180 + kotlin.random.Random.nextInt(0, 32) * 20  // 5GHz 频段
                    }
                    XposedHelpers.callMethod(wifiInfo, "setFrequency", frequency)
                }

                // 网络ID
                kotlin.runCatching {
                    XposedHelpers.callMethod(wifiInfo, "setNetworkId", -1)  // 表示未连接到已保存网络
                }

                // SSID (设置为空或虚拟名称)
                kotlin.runCatching {
                    val wifiSsidClass = android.net.wifi.WifiSsid::class.java
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        XposedHelpers.callStaticMethod(wifiSsidClass, "fromBytes", "".toByteArray())
                    } else {
                        @Suppress("DEPRECATION")
                        XposedHelpers.callStaticMethod(wifiSsidClass, "createFromAsciiEncoded", "")
                    }
                    XposedHelpers.callMethod(wifiInfo, "setSSID", ssid)
                }.onFailure {
                    Logger.error("Failed to set SSID", it)
                }

                result = wifiInfo
            }
        })

        wifiClazz.hookAllMethods("getScanResults", afterHook {
            val packageName = args[0] as? String
            if (packageName.isNullOrEmpty()) {
                return@afterHook
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("In getScanResults with caller: $packageName, state: ${FakeLoc.enableMockWifi}")

            if(FakeLoc.enableMockWifi) {
                if(result == null) {
                    return@afterHook
                }

                if (result is List<*>) {
                    result = arrayListOf<Any>()
                    return@afterHook
                } // 针对小米系列机型的wifi扫描返回

                if (result is Array<*>) {
                    result = arrayOf<Any>()
                    return@afterHook
                } // 针对一加系列机型的wifi扫描返回

                // 在高于安卓10的版本，Google 引入了 APEX（Android Pony EXpress）文件格式来封装系统组件，包括系统服务~！
                // 上面的代码在高版本将无效导致应用可以通过网络AGPS到正常的位置（现象就是位置拉回）
                // 这里针对一个普通的版本进行一个修复
                val resultClass = result.javaClass
                if (resultClass.name.contains("ParceledListSlice")) runCatching {
                    val constructor = resultClass.getConstructor(List::class.java)
                    if (!constructor.isAccessible) {
                        constructor.isAccessible = true
                    }
                    result = constructor.newInstance(emptyList<Any>())
                    return@afterHook
                }.onFailure {
                    Logger.error("getScanResults: ParceledListSlice failed", it)
                }

                if (FakeLoc.enableDebugLog) {
                    Logger.error("getScanResults: Unknown return type: ${result?.javaClass?.name}")
                }
            }
        })

        // Hook startScan 以阻止 WiFi 扫描请求
        wifiClazz.hookAllMethods("startScan", afterHook {
            if (!FakeLoc.enableMockWifi) return@afterHook

            val packageName = args.firstOrNull() as? String ?: return@afterHook

            if (FakeLoc.enableDebugLog)
                Logger.debug("startScan blocked for: $packageName")

            // 返回 false 表示扫描未启动（禁用模式）
            // 或返回 true 但 getScanResults 返回空列表（静默模式）
            if (!BinderUtils.isSystemPackages(packageName)) {
                result = true // 让应用认为扫描成功，但 getScanResults 会返回空
            }
        })

        // Hook startLocalOnlyHotspot (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiClazz.hookAllMethods("startLocalOnlyHotspot", beforeHook {
                if (!FakeLoc.enableMockWifi) return@beforeHook

                if (FakeLoc.enableDebugLog)
                    Logger.debug("startLocalOnlyHotspot intercepted")
            })
        }
    }
}
