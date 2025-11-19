package john.fucklocation.xposed.utils

import kotlin.math.*
import kotlin.random.Random

/**
 * 性能优化缓存
 *
 * 缓存高频计算结果，避免重复计算：
 * 1. jitterLocation计算结果（100ms复用）
 * 2. 三角函数预计算表
 * 3. 卫星数量时间序列
 */
object PerformanceCache {

    /**
     * Jitter位置计算缓存
     */
    private var cachedJitterResult: Pair<Double, Double>? = null
    private var cachedJitterParams: JitterParams? = null
    private var lastJitterTime = 0L
    private const val JITTER_CACHE_VALIDITY_MS = 100L // 100ms内复用

    data class JitterParams(
        val lat: Double,
        val lon: Double,
        val accuracy: Float,
        val bearing: Double
    )

    /**
     * 获取带缓存的jitter位置
     *
     * 如果100ms内参数相同，直接返回缓存结果
     */
    fun getJitteredLocation(
        lat: Double = FakeLoc.latitude,
        lon: Double = FakeLoc.longitude,
        accuracy: Float = FakeLoc.accuracy,
        bearing: Double = FakeLoc.bearing
    ): Pair<Double, Double> {
        val now = System.currentTimeMillis()
        val params = JitterParams(lat, lon, accuracy, bearing)

        // 检查缓存
        if (now - lastJitterTime < JITTER_CACHE_VALIDITY_MS &&
            cachedJitterParams == params &&
            cachedJitterResult != null
        ) {
            return cachedJitterResult!!
        }

        // 重新计算
        val result = calculateJitter(lat, lon, accuracy, bearing)

        // 更新缓存
        cachedJitterResult = result
        cachedJitterParams = params
        lastJitterTime = now

        return result
    }

    /**
     * 实际的jitter计算（从FakeLoc.jitterLocation复制）
     */
    private fun calculateJitter(
        lat: Double,
        lon: Double,
        accuracy: Float,
        bearing: Double
    ): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val n = Random.nextDouble(0.0, accuracy.toDouble())
        val radiusInDegrees = n / 15 / earthRadius * (180 / PI)

        val jitterAngle = if (Random.nextBoolean()) bearing + 45 else bearing - 45

        val newLat = lat + radiusInDegrees * cos(Math.toRadians(jitterAngle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(jitterAngle)) / cos(Math.toRadians(lat))

        return Pair(newLat, newLon)
    }

    /**
     * 清除jitter缓存（位置/精度/方向变化时调用）
     */
    fun invalidateJitterCache() {
        cachedJitterResult = null
        cachedJitterParams = null
    }

    // ========== 三角函数预计算表 ==========

    /**
     * sin/cos预计算表（0-360度，1度精度）
     */
    private val sinTable = DoubleArray(361) { angle ->
        sin(Math.toRadians(angle.toDouble()))
    }

    private val cosTable = DoubleArray(361) { angle ->
        cos(Math.toRadians(angle.toDouble()))
    }

    /**
     * 快速sin查表（角度）
     */
    fun fastSin(angleDegrees: Double): Double {
        val normalized = ((angleDegrees % 360.0 + 360.0) % 360.0).toInt()
        return sinTable[normalized]
    }

    /**
     * 快速cos查表（角度）
     */
    fun fastCos(angleDegrees: Double): Double {
        val normalized = ((angleDegrees % 360.0 + 360.0) % 360.0).toInt()
        return cosTable[normalized]
    }

    // ========== 卫星数量缓存 ==========

    private var cachedSatelliteCount: Int? = null
    private var lastSatelliteCountTime = 0L
    private const val SATELLITE_CACHE_VALIDITY_MS = 5000L // 5秒

    /**
     * 获取带缓存的卫星数量
     *
     * 5秒内返回相同值，避免频繁变化被检测
     */
    fun getCachedSatelliteCount(): Int {
        val now = System.currentTimeMillis()

        if (now - lastSatelliteCountTime < SATELLITE_CACHE_VALIDITY_MS && cachedSatelliteCount != null) {
            return cachedSatelliteCount!!
        }

        // 重新计算
        val count = FakeLoc.getTimeDependentSatelliteCount()

        cachedSatelliteCount = count
        lastSatelliteCountTime = now

        return count
    }

    // ========== CellID生成缓存 ==========

    /**
     * 基站信息缓存
     */
    data class CellTowerCache(
        val cid: Int,
        val lac: Int,
        val psc: Int,
        val timestamp: Long
    )

    private val cellTowerCache = HashMap<Pair<Double, Double>, CellTowerCache>()
    private const val CELL_CACHE_MAX_SIZE = 100

    /**
     * 获取缓存的基站信息
     *
     * 同一位置总是返回相同的CID/LAC/PSC
     */
    fun getCachedCellTower(lat: Double, lon: Double): Triple<Int, Int, Int> {
        // 坐标精度降低到3位小数（约100米）作为cache key
        val key = Pair(
            (lat * 1000).toInt() / 1000.0,
            (lon * 1000).toInt() / 1000.0
        )

        val cached = cellTowerCache[key]
        if (cached != null) {
            return Triple(cached.cid, cached.lac, cached.psc)
        }

        // 计算新的基站信息
        val seed = ((lat * 1000000).toLong() xor (lon * 1000000).toLong())
        val random = Random(seed)

        val cid = 10000 + random.nextInt(50000)
        val lac = 1000 + random.nextInt(9000)
        val psc = random.nextInt(504)

        // 存入缓存
        if (cellTowerCache.size >= CELL_CACHE_MAX_SIZE) {
            // LRU淘汰：移除最旧的条目
            val oldest = cellTowerCache.minByOrNull { it.value.timestamp }
            oldest?.let { cellTowerCache.remove(it.key) }
        }

        cellTowerCache[key] = CellTowerCache(cid, lac, psc, System.currentTimeMillis())

        return Triple(cid, lac, psc)
    }

    // ========== Haversine距离计算缓存 ==========

    data class DistanceKey(
        val lat1: Int, // 精度降低到整数（约100km）
        val lon1: Int,
        val lat2: Int,
        val lon2: Int
    )

    private val distanceCache = HashMap<DistanceKey, Double>()
    private const val DISTANCE_CACHE_MAX_SIZE = 200

    /**
     * 带缓存的Haversine距离计算
     */
    fun cachedHaversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val key = DistanceKey(
            (lat1 * 100).toInt(),
            (lon1 * 100).toInt(),
            (lat2 * 100).toInt(),
            (lon2 * 100).toInt()
        )

        val cached = distanceCache[key]
        if (cached != null) {
            return cached
        }

        // 计算距离
        val distance = FakeLoc.haversine(lat1, lon1, lat2, lon2)

        // 存入缓存
        if (distanceCache.size >= DISTANCE_CACHE_MAX_SIZE) {
            // 随机淘汰一个条目
            val toRemove = distanceCache.keys.randomOrNull()
            toRemove?.let { distanceCache.remove(it) }
        }

        distanceCache[key] = distance

        return distance
    }

    // ========== 统计信息 ==========

    /**
     * 获取缓存统计信息
     */
    fun getCacheStats(): String {
        return """
            PerformanceCache Stats:
            - Jitter cache: ${if (cachedJitterResult != null) "HIT" else "MISS"}
            - Satellite count cache: ${if (cachedSatelliteCount != null) "VALID" else "INVALID"}
            - Cell tower cache: ${cellTowerCache.size} entries
            - Distance cache: ${distanceCache.size} entries
        """.trimIndent()
    }

    /**
     * 清空所有缓存
     */
    fun clearAllCaches() {
        cachedJitterResult = null
        cachedJitterParams = null
        cachedSatelliteCount = null
        cellTowerCache.clear()
        distanceCache.clear()

        if (FakeLoc.enableDebugLog) {
            Logger.debug("All performance caches cleared")
        }
    }
}
