package john.fucklocation.portal.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import john.fucklocation.portal.Portal
import john.fucklocation.portal.android.root.ShellUtils
import john.fucklocation.portal.ext.altitude
import john.fucklocation.portal.ext.debug
import john.fucklocation.portal.ext.disableFusedProvider
import john.fucklocation.portal.ext.disableGetCurrentLocation
import john.fucklocation.portal.ext.disableRegisterLocationListener
import john.fucklocation.portal.ext.enableAGPS
import john.fucklocation.portal.ext.enableGetFromLocation
import john.fucklocation.portal.ext.enableNMEA
import john.fucklocation.portal.ext.enableRequestGeofence
import john.fucklocation.portal.ext.minSatelliteCount
import john.fucklocation.portal.ext.needDowngradeToCdma
import john.fucklocation.portal.ext.speed
import john.fucklocation.portal.ext.reportDuration
import john.fucklocation.portal.ext.loopBroadcastlocation
import john.fucklocation.xposed.utils.FakeLoc
import java.io.File

object MockServiceHelper {
    // 使用更隐蔽的 provider 名称，模拟系统服务
    const val PROVIDER_NAME = "passive"
    private lateinit var randomKey: String

    private var loopThread :Thread ?= null
    @Volatile private var isRunning = false

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                Log.d("MockServiceHelper", "Service init success, key: $randomKey")
            }
        } else {
            Log.e("MockServiceHelper", "Failed to init service")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        startLoopBroadcastLocation(locationManager)
        return if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            isMockStart(locationManager)
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return !isMockStart(locationManager)
        }
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        rely.putFloat("speed", speed)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setTransportMode(locationManager: LocationManager, mode: Int): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_transport_mode")
        rely.putInt("transport_mode", mode)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setStepFrequencyMultiplier(locationManager: LocationManager, multiplier: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_step_frequency_multiplier")
        rely.putDouble("step_frequency_multiplier", multiplier)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setAutoDetectTransportMode(locationManager: LocationManager, autoDetect: Boolean): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_auto_detect_transport_mode")
        rely.putBoolean("auto_detect_transport_mode", autoDetect)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setSensorSimulation(locationManager: LocationManager, enable: Boolean): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_sensor_simulation")
        rely.putBoolean("enable_sensor_simulation", enable)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setDisableGetFromLocation(locationManager: LocationManager, disable: Boolean): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_disable_get_from_location")
        rely.putBoolean("disable_get_from_location", disable)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setEnableRequestGeofence(locationManager: LocationManager, enable: Boolean): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_enable_request_geofence")
        rely.putBoolean("enable_request_geofence", enable)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getFloat("speed")
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getFloat("bearing")
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }

        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableGetCurrentLocation = context.disableGetCurrentLocation
        FakeLoc.disableRegisterLocationListener = context.disableRegisterLocationListener
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        rely.putBoolean("enable", FakeLoc.enable)
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }

    /**
     * 启用路线模拟模式
     */
    fun enableRouteMode(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "enable_route_mode")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    /**
     * 禁用路线模拟模式
     */
    fun disableRouteMode(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "disable_route_mode")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    /**
     * 获取当前位置模式
     * @return 0=DISABLED, 1=SINGLE_POINT, 2=ROUTE
     */
    fun getLocationMode(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location_mode")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("mode")
        }
        return null
    }


    private fun startLoopBroadcastLocation(locationManager: LocationManager){
        val appContext = Portal.appContext
        val delayTime=appContext.reportDuration.toLong()

        if(isRunning) return
        if(!appContext.loopBroadcastlocation) return

        isRunning=true
        loopThread=Thread{
            Log.d("MockServiceHelper","loopBoardcast: Start")
            while(isRunning){
                try {
                    broadcastLocation(locationManager)
                    Thread.sleep(delayTime)
                }catch (e:InterruptedException){
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceHelper","loopBoardcast: Stop")
                    }
                    break
                }
            }
        }
        loopThread!!.start()
    }

    private fun stopLoopBroadcastLocation(){
        isRunning =false
        loopThread?.interrupt()
        loopThread = null
    }


    @SuppressLint("DiscouragedPrivateApi")
    fun loadPortalLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val isX86: Boolean = runCatching {
            if (Build.SUPPORTED_ABIS.any { it.contains("x86") }) {
                return@runCatching true
            }
            val clazz = Class.forName("dalvik.system.VMRuntime")
            val method = clazz.getDeclaredMethod("getRuntime")
            val runtime = method.invoke(null)
            val field = clazz.getDeclaredField("vmInstructionSet")
            field.isAccessible = true
            val instructionSet = field.get(runtime) as String
            if (instructionSet.contains("x86") ) {
                true
            } else false
        }.getOrElse { false }
        // todo: support x86

        val soDir = File("/data/local/portal-lib")
        if (!soDir.exists()) {
            ShellUtils.executeCommand("mkdir ${soDir.absolutePath}")
        }
        val soFile = File(soDir, "libportal.so")
        runCatching {
            val tmpSoFile = File(soDir, "libportal.so.tmp").also { file ->
                var nativeDir = context.applicationInfo.nativeLibraryDir
                val apkSoFile = File(nativeDir, "libportal.so")
                if (apkSoFile.exists()) {
                    ShellUtils.executeCommand("cp ${apkSoFile.absolutePath} ${file.absolutePath}")
                } else {
                    Log.e("MockServiceHelper", "Failed to copy portal library: ${apkSoFile.absolutePath}")
                    return@runCatching
                }
            }
            if (soFile.exists()) {
                val originalHash = ShellUtils.executeCommandToBytes("head -c 4096 ${soFile.absolutePath}")
                val newHash = ShellUtils.executeCommandToBytes("head -c 4096 ${tmpSoFile.absolutePath}")
                if (originalHash.contentEquals(newHash)) {
                    ShellUtils.executeCommand("rm ${soFile.absolutePath}")
                    ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
                }
            } else if (tmpSoFile.exists()) {
                ShellUtils.executeCommand("mv ${tmpSoFile.absolutePath} ${soFile.absolutePath}")
            }
        }.onFailure {
            Log.w("MockServiceHelper", "Failed to copy portal library", it)
        }

        ShellUtils.executeCommand("chmod 777 ${soFile.absolutePath}")

        val result = loadLibrary(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager, soFile.absolutePath)

        Log.d("MockServiceHelper", "load portal library result: $result")

        return result == "success"
    }
}