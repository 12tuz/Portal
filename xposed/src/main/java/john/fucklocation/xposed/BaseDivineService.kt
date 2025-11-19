package john.fucklocation.xposed

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import john.fucklocation.xposed.utils.BinderUtils
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger

abstract class BaseDivineService {
    /**
     * if the hook is TelephonyService? or other service?
     * this service may not be in the same space as the `system_server`,
     * so a binder is used to talk.
     */
    protected fun initDivineService(from: String, retryCount: Int = 0): Boolean {
        if (FakeLoc.isSystemServerProcess) {
            Logger.info("[$from] Running in system_server, skip initDivineService")
            return true
        }

        Logger.info("[$from] Initializing DivineService, retry=$retryCount")

        // 步骤1: 获取 LocationManager (增强版，超时30秒，指数退避)
        val locationManager = tryFetchLocationManager(from)
        if (locationManager == null) {
            Logger.error("[$from] FATAL: LocationManager not available after all retries")
            return false
        }

        // 步骤2: 确保 passive provider 启用 (增强重试逻辑)
        if (!ensurePassiveProviderEnabled(locationManager, from, 0)) {
            Logger.error("[$from] FATAL: passive provider not available")
            return false
        }

        // 步骤3: exchange_key 握手 (增强重试和错误诊断)
        val randomKey = exchangeKey(locationManager, from)
        if (randomKey == null) {
            Logger.error("[$from] FATAL: key exchange failed after all retries")
            return false
        }

        // 步骤4: 同步配置
        syncConfig(locationManager, randomKey)

        // 步骤5: 注册 proxy binder
        val rely = Bundle()
        rely.putBinder("proxy", object: Binder() {
            override fun getInterfaceDescriptor(): String {
                return "john.fucklocation.portal.service.${from}Helper"
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val bundle = data.readBundle(javaClass.classLoader)!!
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("ProxyBinder($from): $bundle")
                    }
                    if(!RemoteCommandHandler.handleInstruction(randomKey, bundle)) {
                        Logger.error("Failed to handle instruction in $from")
                    }
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        })
        rely.putString("command_id", "set_proxy")
        if (!locationManager.sendExtraCommand("passive", randomKey, rely)) {
            Logger.error("[$from] FATAL: Failed to register proxy binder")
            return false
        }

        Logger.info("[$from] DivineService initialized successfully")
        return true
    }

    /**
     * 获取 LocationManager，支持指数退避重试，最多30秒
     */
    private fun tryFetchLocationManager(from: String): LocationManager? {
        var retryDelay = 100L
        var lastError: Throwable? = null

        repeat(30) { attempt ->
            try {
                val context = BinderUtils.getSystemContext()
                if (context == null) {
                    Logger.warn("[$from] SystemContext not available, attempt ${attempt + 1}/30")
                    Thread.sleep(retryDelay)
                    retryDelay = (retryDelay * 1.3).toLong().coerceAtMost(2000L)
                    return@repeat
                }

                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (locationManager != null) {
                    Logger.info("[$from] LocationManager obtained after ${attempt + 1} attempts")
                    return locationManager
                }

                Logger.warn("[$from] LocationManager null, attempt ${attempt + 1}/30, retry in ${retryDelay}ms")
            } catch (e: Exception) {
                lastError = e
                Logger.warn("[$from] Error getting LocationManager: ${e.message}")
            }

            Thread.sleep(retryDelay)
            retryDelay = (retryDelay * 1.3).toLong().coerceAtMost(2000L)
        }

        Logger.error("[$from] Failed to get LocationManager after 30 attempts", lastError ?: Throwable())
        return null
    }

    /**
     * 确保 passive provider 已启用，增强重试和异常处理
     */
    private fun ensurePassiveProviderEnabled(
        locationManager: LocationManager,
        from: String,
        retryCount: Int
    ): Boolean {
        if (retryCount > 20) {
            Logger.error("[$from] passive provider not enabled after 20 retries")
            return false
        }

        try {
            val isEnabled = locationManager.isProviderEnabled("passive")
            if (!isEnabled) {
                Logger.warn("[$from] passive provider not enabled, retry ${retryCount + 1}/20")
                Thread.sleep(200)
                return ensurePassiveProviderEnabled(locationManager, from, retryCount + 1)
            }

            Logger.info("[$from] passive provider confirmed enabled")
            return true
        } catch (e: Exception) {
            Logger.error("[$from] Error checking passive provider: ${e.message}", e)
            Thread.sleep(200)
            return ensurePassiveProviderEnabled(locationManager, from, retryCount + 1)
        }
    }

    /**
     * exchange_key 握手，增强重试逻辑和错误诊断
     */
    private fun exchangeKey(locationManager: LocationManager, from: String): String? {
        repeat(5) { attempt ->
            try {
                val bundle = Bundle()
                val success = locationManager.sendExtraCommand("passive", "exchange_key", bundle)

                if (!success) {
                    Logger.warn("[$from] sendExtraCommand returned false, attempt ${attempt + 1}/5")
                    Thread.sleep(500)
                    return@repeat
                }

                val key = bundle.getString("key")
                if (!key.isNullOrEmpty()) {
                    Logger.info("[$from] Successfully exchanged key: ${key.take(8)}...")
                    return key
                }

                Logger.warn("[$from] Received empty key, attempt ${attempt + 1}/5")
            } catch (e: Exception) {
                Logger.error("[$from] exchange_key exception: ${e.message}", e)
            }

            Thread.sleep(500)
        }

        return null
    }

    /**
     * Synchronize configurations in different processes
     */
    private fun syncConfig(locationManager: LocationManager, randomKey: String) {
        val rely = Bundle()
        rely.putString("command_id", "sync_config")
        if(locationManager.sendExtraCommand("passive", randomKey, rely)) {
            FakeLoc.enable = rely.getBoolean("enable", FakeLoc.enable)
            FakeLoc.latitude = rely.getDouble("latitude", FakeLoc.latitude)
            FakeLoc.longitude = rely.getDouble("longitude", FakeLoc.longitude)
            FakeLoc.altitude = rely.getDouble("altitude", FakeLoc.altitude)
            FakeLoc.speed = rely.getDouble("speed", FakeLoc.speed)
            FakeLoc.speedAmplitude = rely.getDouble("speed_amplitude", FakeLoc.speedAmplitude)
            FakeLoc.hasBearings = rely.getBoolean("has_bearings", FakeLoc.hasBearings)
            FakeLoc.bearing = rely.getDouble("bearing", FakeLoc.bearing)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                FakeLoc.lastLocation = rely.getParcelable("last_location", Location::class.java)
            } else {
                @Suppress("DEPRECATION")
                FakeLoc.lastLocation = rely.getParcelable("last_location")
            }
            FakeLoc.enableLog = rely.getBoolean("enable_log", FakeLoc.enableLog)
            FakeLoc.enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
            FakeLoc.disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
            FakeLoc.disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
            FakeLoc.disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
            FakeLoc.enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
            FakeLoc.enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
            FakeLoc.hookWifi = rely.getBoolean("hook_wifi", FakeLoc.hookWifi)
            FakeLoc.needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
            Logger.debug("Synced config for DivineService")
        } else {
            Logger.error("Failed to sync config for DivineService")
        }
    }
}