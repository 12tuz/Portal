package john.fucklocation.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.LocationPool
import john.fucklocation.xposed.utils.Logger
import moe.microbios.nmea.NMEA
import moe.microbios.nmea.NmeaValue
import java.lang.reflect.Method
import java.util.Collections
import kotlin.random.Random
import androidx.annotation.RequiresApi

abstract class BaseLocationHook: BaseDivineService() {
    companion object {
        // 方法缓存最大容量，防止无限增长
        private const val MAX_CACHE_SIZE = 100

        // 使用 LinkedHashMap 实现 LRU 缓存
        private val methodCache = Collections.synchronizedMap(
            object : LinkedHashMap<String, Method?>(MAX_CACHE_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Method?>?): Boolean {
                    val shouldRemove = size > MAX_CACHE_SIZE
                    if (shouldRemove && FakeLoc.enableDebugLog) {
                        Logger.debug("LRU cache evicting: ${eldest?.key}")
                    }
                    return shouldRemove
                }
            }
        )

        /**
         * 获取缓存的Method对象
         * 使用 LRU 策略自动淘汰最久未使用的条目
         */
        private fun getCachedMethod(obj: Any, methodName: String): Method? {
            val cacheKey = "${obj.javaClass.name}#$methodName"
            return methodCache.getOrPut(cacheKey) {
                try {
                    obj.javaClass.getDeclaredMethod(methodName).apply {
                        isAccessible = true
                    }
                } catch (e: NoSuchMethodException) {
                    null
                }
            }
        }

        /**
         * Android 13+: 检查Location是否完整
         */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun checkLocationComplete(location: Location): Boolean {
            return location.provider == LocationManager.GPS_PROVIDER && location.isComplete
        }

        /**
         * Android 8.0+: 设置速度精度
         */
        @RequiresApi(Build.VERSION_CODES.O)
        private fun setSpeedAccuracy(location: Location, speed: Double) {
            location.speedAccuracyMetersPerSecond = speed.toFloat()
        }

        /**
         * Android 8.0+: 设置方位角精度
         */
        @RequiresApi(Build.VERSION_CODES.O)
        private fun setBearingAccuracy(location: Location, bearing: Double) {
            location.bearingAccuracyDegrees = bearing.toFloat()
            if (location.hasBearingAccuracy() && location.bearingAccuracyDegrees == 0.0f) {
                location.bearingAccuracyDegrees = 1.0f
            }
        }

        /**
         * Android 10+: 设置elapsed realtime不确定性
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun setElapsedRealtimeUncertainty(location: Location) {
            location.elapsedRealtimeUncertaintyNanos = Random.nextDouble(5.0, 50.0)
        }

        /**
         * Android 8.0+: 设置垂直精度
         */
        @RequiresApi(Build.VERSION_CODES.O)
        private fun setVerticalAccuracy(location: Location, originLocation: Location) {
            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
        }

        /**
         * Android 14+: 设置MSL海拔
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun setMslAltitude(location: Location, originLocation: Location, altitude: Double) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = altitude
            }
            if (originLocation.hasVerticalAccuracy()) {
                location.mslAltitudeAccuracyMeters = altitude.toFloat()
            }
        }

        /**
         * Android 12+: 隐藏Mock标志
         */
        @RequiresApi(Build.VERSION_CODES.S)
        private fun hideMockFlag(location: Location) {
            location.isMock = false
        }
    }

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (realLocation) {
            val isGpsLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkLocationComplete(originLocation)
            } else {
                originLocation.provider == LocationManager.GPS_PROVIDER
            }

            if (isGpsLocation) {
                FakeLoc.lastLocation = originLocation
            }
        } else {
            originLocation.altitude = FakeLoc.altitude
        }

        if (!FakeLoc.enable)
            return originLocation

        if (originLocation.latitude + originLocation.longitude == FakeLoc.latitude + FakeLoc.longitude) {
            // Already processed
            return originLocation
        }

        if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            originLocation.provider = LocationManager.GPS_PROVIDER
        }

        // 使用对象池优化性能，减少GC压力
        val location = LocationPool.obtain(originLocation.provider ?: LocationManager.GPS_PROVIDER)
        location.accuracy = if (FakeLoc.accuracy != 0.0f) FakeLoc.accuracy else originLocation.accuracy
        val jitterLat = FakeLoc.jitterLocation()
        location.latitude = jitterLat.first
        location.longitude = jitterLat.second
        location.altitude = FakeLoc.altitude
        val speedAmp = Random.nextDouble(-FakeLoc.speedAmplitude, FakeLoc.speedAmplitude)
        location.speed = (originLocation.speed + speedAmp).toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && originLocation.hasSpeedAccuracy()) {
            setSpeedAccuracy(location, FakeLoc.speed + speedAmp)
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        // 时间戳同步：使用当前时间避免位置跳变但时间不变的检测
        location.time = System.currentTimeMillis()

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        var modBearing = FakeLoc.bearing % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setBearingAccuracy(location, modBearing)
        }

        // 速度随机化，避免固定1.2被检测
        if (location.speed == 0.0f) {
            location.speed = (1.2f + Random.nextFloat() * 0.3f - 0.15f)
        }

        // 时间戳同步：使用SystemClock保证单调性
        location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setElapsedRealtimeUncertainty(location)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setVerticalAccuracy(location, originLocation)
        }
        // 复制原始 extras，但移除可能暴露的标记
        val cleanExtras = Bundle()
        originLocation.extras?.let { original ->
            // 只复制标准的 GNSS extras
            if (original.containsKey("satellites")) {
                // 使用时间依赖的卫星数量，更真实地模拟卫星可见性变化
                cleanExtras.putInt("satellites", FakeLoc.getTimeDependentSatelliteCount())
            }
            if (original.containsKey("maxCn0")) {
                cleanExtras.putInt("maxCn0", Random.nextInt(35, 50))
            }
            if (original.containsKey("meanCn0")) {
                cleanExtras.putInt("meanCn0", Random.nextInt(25, 35))
            }
        }
        location.extras = cleanExtras

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            setMslAltitude(location, originLocation, FakeLoc.altitude)
        }
        // 始终隐藏 mock 标志以增强隐蔽性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hideMockFlag(location)
        }

        // 使用缓存的反射Method优化性能
        kotlin.runCatching {
            val makeCompleteMethod = getCachedMethod(location, "makeComplete")
            if (makeCompleteMethod != null) {
                makeCompleteMethod.invoke(location)
            } else {
                // Fallback到XposedHelpers
                XposedHelpers.callMethod(location, "makeComplete")
            }
        }.onFailure {
            Logger.error("makeComplete failed", it)
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("injectLocation success! $location")
        }

        return location
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.GSA -> {
                    // 伪造 GSA - 3D Fix 状态
                    // 生成用于定位的卫星 PRN 列表 (最多12颗)
                    val usedPrns = mutableListOf<Int?>()
                    val satCount = kotlin.math.min(12, FakeLoc.minSatellites)
                    for (i in 1..satCount) {
                        usedPrns.add(i + 160) // 北斗卫星 PRN 从 161 开始
                    }
                    // 填充到12个
                    while (usedPrns.size < 12) {
                        usedPrns.add(null)
                    }

                    val forgedGsa = NmeaValue.GSA(
                        mode = "A", // 自动模式
                        fixStatus = 3, // 3D fix
                        prn = usedPrns,
                        pdop = 1.2 + kotlin.random.Random.nextDouble(0.0, 0.5),
                        hdop = 0.8 + kotlin.random.Random.nextDouble(0.0, 0.3),
                        vdop = 1.0 + kotlin.random.Random.nextDouble(0.0, 0.4),
                        systemId = "4" // 北斗系统
                    )
                    return forgedGsa.toNmeaString()
                }
                is NmeaValue.GSV -> {
                    // 伪造 GSV - 卫星视图数据
                    val totalSats = kotlin.math.min(FakeLoc.minSatellites, 16)
                    val satsPerMessage = 4
                    val totalMessages = (totalSats + satsPerMessage - 1) / satsPerMessage

                    // 确定当前是第几条消息
                    val currentMsg = value.messageNumber
                    if (currentMsg > totalMessages) {
                        return null
                    }

                    val satellites = mutableListOf<NmeaValue.GSV.Satellite>()
                    val startIdx = (currentMsg - 1) * satsPerMessage
                    val endIdx = kotlin.math.min(startIdx + satsPerMessage, totalSats)

                    for (i in startIdx until endIdx) {
                        val prn = i + 1 + 160 // 北斗 PRN
                        val elevation = kotlin.random.Random.nextInt(10, 80)
                        val azimuth = kotlin.random.Random.nextInt(0, 360)
                        val snr = kotlin.random.Random.nextInt(25, 50)
                        satellites.add(NmeaValue.GSV.Satellite(prn, elevation, azimuth, snr))
                    }

                    val forgedGsv = NmeaValue.GSV(
                        totalMessages = totalMessages,
                        messageNumber = currentMsg,
                        totalSatellitesInView = totalSats,
                        satellites = satellites,
                        infoId = "4" // 北斗系统
                    )
                    return forgedGsv.toNmeaString()
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    var degree = FakeLoc.latitude.toInt()
                    var minute = (FakeLoc.latitude - degree) * 60
                    value.latitude = degree + minute / 100

                    degree = FakeLoc.longitude.toInt()
                    minute = (FakeLoc.longitude - degree) * 60
                    value.longitude = degree + minute / 100

                    return value.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    return null
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}