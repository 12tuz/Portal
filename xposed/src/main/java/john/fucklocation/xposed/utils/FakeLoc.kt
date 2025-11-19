package john.fucklocation.xposed.utils

import android.location.Location
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FakeLoc {
    /**
     * 是否允许打印日志
     */
    var enableLog = true

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog = true

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 模拟Gnss卫星数据开关
     */
    @Volatile
    var enableMockGnss = false

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    /**
     * 是否禁用getCurrentLocation API (Android 11+)
     *
     * getCurrentLocation是Android 11引入的推荐单次定位API，应该默认启用Hook
     * 仅在特定ROM（如MIUI Android 11）存在兼容性问题时才禁用
     */
    var disableGetCurrentLocation = false

    /**
     * 是否禁用RegisterLocationListener方法
     */
    var disableRegisterLocationListener = false

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    var disableFusedLocation = true
    var disableNetworkLocation = true

    var disableRequestGeofence = false
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    var enableAGPS = false

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA = false

    /**
     * may cause system to crash
     */
    var hookWifi = true

    /**
     * 将网络定位降级为Cdma
     */
    var needDowngradeToCdma = true
    var isSystemServerProcess = false

    /**
     * 目标应用 UID 集合 - 只对这些应用返回伪造位置
     * 如果为空则对所有非系统应用生效
     */
    val targetUids = mutableSetOf<Int>()

    /**
     * 是否启用精确 UID 过滤模式
     */
    @Volatile
    var enableUidFilter = false

    /**
     * 模拟最小卫星数量
     */
    var minSatellites = 12

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭Portal后需要重新启动才能重新获取定位）
     */
    var loopBroadcastLocation = false

    /**
     * 上一次的位置
     */
    @Volatile var lastLocation: Location? = null

    // 使用原子引用保证经纬度读写的原子性
    private val _coordinates = java.util.concurrent.atomic.AtomicReference(Pair(0.0, 0.0))

    var latitude: Double
        get() = _coordinates.get().first
        set(value) {
            _coordinates.updateAndGet { Pair(value, it.second) }
        }

    var longitude: Double
        get() = _coordinates.get().second
        set(value) {
            _coordinates.updateAndGet { Pair(it.first, value) }
        }

    // 高度随机化，避免固定80m被检测
    @Volatile var altitude = 80.0 + kotlin.random.Random.nextDouble(-30.0, 70.0)

    // 基础速度，会根据交通模式动态调整
    @Volatile private var _baseSpeed = 1.5

    // 速度属性：根据交通模式动态计算，避免固定值被检测
    var speed: Double
        get() {
            val mode = getEffectiveTransportMode()
            val range = mode.speedRange
            // 在交通模式速度范围内生成随机速度
            val modeSpeed = range.start + (range.endInclusive - range.start) * kotlin.random.Random.nextDouble()
            // 添加时间依赖的微小波动
            val timeVariation = kotlin.math.sin(System.currentTimeMillis() / 3000.0) * 0.2
            return (modeSpeed + timeVariation).coerceIn(range.start, range.endInclusive)
        }
        set(value) {
            _baseSpeed = value
        }

    var speedAmplitude = 1.0

    /**
     * 交通模式
     */
    enum class TransportMode(
        val speedRange: ClosedRange<Double>,
        val stepFrequency: Double, // 步频 (Hz)
        val accelerationRange: ClosedRange<Float> // 加速度幅度范围
    ) {
        STATIONARY(0.0..0.5, 0.0, 0.0f..0.2f),      // 静止
        WALKING(0.5..2.0, 1.8, 0.5f..1.5f),         // 行走 (约 1-2 m/s, 步频 1.8 Hz)
        RUNNING(2.0..5.0, 2.5, 1.5f..3.0f),         // 跑步 (约 2-5 m/s, 步频 2.5 Hz)
        CYCLING(3.0..8.0, 0.0, 0.3f..0.8f),         // 骑行 (约 3-8 m/s, 无步频)
        DRIVING(5.0..30.0, 0.0, 0.2f..0.5f),        // 开车 (约 5-30 m/s)
        HIGH_SPEED(30.0..50.0, 0.0, 0.1f..0.3f)     // 高速 (约 30-50 m/s)
    }

    /**
     * 当前交通模式
     */
    @Volatile
    var transportMode: TransportMode = TransportMode.WALKING

    /**
     * 步频倍率 (1.0 = 正常速度, 0.5 = 半速, 2.0 = 2倍速)
     */
    @Volatile
    var stepFrequencyMultiplier: Double = 1.0

    /**
     * 是否启用自动交通模式检测
     */
    @Volatile
    var autoDetectTransportMode: Boolean = true

    /**
     * 根据速度自动检测交通模式
     * 注意：使用_baseSpeed而不是speed属性，避免循环依赖
     */
    private fun detectTransportMode(): TransportMode {
        val currentSpeed = _baseSpeed
        return when {
            currentSpeed < 0.5 -> TransportMode.STATIONARY
            currentSpeed < 2.0 -> TransportMode.WALKING
            currentSpeed < 5.0 -> TransportMode.RUNNING
            currentSpeed < 8.0 -> TransportMode.CYCLING
            currentSpeed < 30.0 -> TransportMode.DRIVING
            else -> TransportMode.HIGH_SPEED
        }
    }

    /**
     * 获取当前有效的交通模式
     */
    fun getEffectiveTransportMode(): TransportMode {
        return if (autoDetectTransportMode) {
            detectTransportMode()
        } else {
            transportMode
        }
    }

    /**
     * 获取当前步频 (Hz)
     */
    fun getCurrentStepFrequency(): Double {
        val mode = getEffectiveTransportMode()
        return mode.stepFrequency * stepFrequencyMultiplier
    }

    @Volatile var hasBearings = false

    @Volatile
    private var _bearing = 0.0

    // 专用锁对象，避免锁住整个 FakeLoc object
    private val bearingLock = Any()

    var bearing: Double
        get() = synchronized(bearingLock) {
            if (hasBearings) {
                _bearing
            } else {
                if (_bearing >= 360.0) {
                    _bearing -= 360.0
                }
                _bearing += 0.5
                _bearing
            }
        }
        set(value) = synchronized(bearingLock) {
            _bearing = value
        }

    // 精度随机化，避免固定25m被检测
    var accuracy = (25.0f + kotlin.random.Random.nextFloat() * 10.0f - 5.0f)
        set(value) {
            field = if (value < 0) {
                -value
            } else {
                value
            }
        }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    /**
     * 原子性设置经纬度坐标
     */
    fun setCoordinates(lat: Double, lon: Double) {
        _coordinates.set(Pair(lat, lon))
    }

    /**
     * 原子性获取经纬度坐标
     */
    fun getCoordinates(): Pair<Double, Double> = _coordinates.get()

    fun jitterLocation(lat: Double = latitude, lon: Double = longitude, n: Double = Random.nextDouble(0.0, accuracy.toDouble()), angle: Double = bearing): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = n / 15 / earthRadius * (180 / PI)

        val jitterAngle = if (Random.nextBoolean()) angle + 45 else angle - 45

        val newLat = lat + radiusInDegrees * cos(Math.toRadians(jitterAngle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(jitterAngle)) / cos(Math.toRadians(lat))

        return Pair(newLat, newLon)
    }

    fun moveLocation(lat: Double = latitude, lon: Double = longitude, n: Double, angle: Double = bearing): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = Random.nextDouble(n, n + 1.2) / earthRadius * (180 / PI)
        val newLat = lat + radiusInDegrees * cos(Math.toRadians(angle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(angle)) / cos(Math.toRadians(lat))
        return Pair(newLat, newLon)
    }



    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }

    // ==================== 基站参数生成 ====================

    /**
     * 基于位置生成稳定的基站参数
     * 使用位置hash确保同一位置返回相同基站ID
     */
    data class CellTowerInfo(
        val cid: Int,      // Cell ID: 0-65535
        val lac: Int,      // Location Area Code: 0-65535
        val psc: Int,      // Primary Scrambling Code: 0-511
        val mcc: Int,      // Mobile Country Code
        val mnc: Int,      // Mobile Network Code
        val sid: Int,      // System ID (CDMA): 0-32767
        val nid: Int,      // Network ID (CDMA): 0-65535
        val bid: Int       // Base Station ID (CDMA): 0-65535
    )

    /**
     * 根据当前位置生成虚拟基站参数
     * 同一位置总是返回相同参数，避免检测
     */
    fun generateCellTowerInfo(): CellTowerInfo {
        val (lat, lon) = getCoordinates()

        // 使用位置生成稳定的hash种子
        val seed = ((lat * 1000000).toLong() xor (lon * 1000000).toLong())
        val random = Random(seed)

        return CellTowerInfo(
            cid = random.nextInt(1, 65535),      // 有效范围内的CID
            lac = random.nextInt(1, 65535),      // 有效范围内的LAC
            psc = random.nextInt(0, 511),        // PSC范围
            mcc = 460,                           // 中国MCC
            mnc = random.nextInt(0, 11),         // 中国运营商MNC
            sid = random.nextInt(1, 32767),      // CDMA系统ID
            nid = random.nextInt(1, 65535),      // CDMA网络ID
            bid = random.nextInt(1, 65535)       // CDMA基站ID
        )
    }

    // ==================== 步频模拟开关 ====================

    /**
     * 是否启用步频传感器模拟
     */
    @Volatile
    var enableSensorSimulation = true

    // ==================== 卫星数时间依赖波动 ====================

    /**
     * 获取带时间依赖波动的卫星数量
     *
     * 模拟真实GNSS场景中卫星可见性的自然变化：
     * - 每30秒一个波动周期
     * - 基于正弦函数产生平滑变化
     * - 波动范围为 minSatellites ± 3
     *
     * @return 当前时刻的卫星数量
     */
    fun getTimeDependentSatelliteCount(): Int {
        val time = System.currentTimeMillis()

        // 30秒一个完整周期的波动
        val phase = (time % 30000) / 30000.0 * 2 * Math.PI

        // 基础波动: ±2颗卫星
        val baseVariation = (kotlin.math.sin(phase) * 2).toInt()

        // 随机微扰: ±1颗卫星（每5秒变化一次）
        val randomSeed = (time / 5000).toInt()
        val randomVariation = (randomSeed % 3) - 1

        // 计算最终卫星数
        val result = minSatellites + baseVariation + randomVariation

        // 确保在合理范围内 (4-35)
        return result.coerceIn(4, 35)
    }
}
