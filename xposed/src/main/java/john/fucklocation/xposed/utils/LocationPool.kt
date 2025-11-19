package john.fucklocation.xposed.utils

import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Location对象池 - 减少GC压力
 *
 * 通过复用Location对象，避免频繁创建导致的内存抖动
 *
 * 性能优化：
 * - 减少GC次数：复用对象减少内存分配
 * - 线程安全：使用ConcurrentLinkedQueue无锁队列
 * - 容量限制：防止内存泄漏
 * - 统计信息：监控池的使用效率
 */
object LocationPool {

    /**
     * 对象池配置
     */
    private const val MAX_POOL_SIZE = 20          // 池最大容量
    private const val INITIAL_POOL_SIZE = 5       // 初始化时预创建的对象数
    private const val MAX_REUSE_COUNT = 100       // 单个对象最大复用次数

    /**
     * 对象池（线程安全）
     */
    private val pool = ConcurrentLinkedQueue<PooledLocation>()

    /**
     * 统计信息
     */
    private val obtainCount = AtomicInteger(0)    // 获取次数
    private val recycleCount = AtomicInteger(0)   // 回收次数
    private val createCount = AtomicInteger(0)    // 创建次数
    private val hitCount = AtomicInteger(0)       // 命中次数（从池中获取）

    /**
     * 是否启用对象池（可在运行时切换）
     */
    @Volatile
    var enabled = true

    /**
     * 带复用计数的Location包装类
     */
    private data class PooledLocation(
        val location: Location,
        var reuseCount: Int = 0
    )

    init {
        // 预热对象池，创建初始对象
        repeat(INITIAL_POOL_SIZE) {
            pool.offer(PooledLocation(createNewLocation()))
        }
        if (FakeLoc.enableDebugLog) {
            Logger.debug("LocationPool initialized with $INITIAL_POOL_SIZE objects")
        }
    }

    /**
     * 从池中获取Location对象
     *
     * @param provider 位置提供者名称（默认GPS）
     * @return 可复用的Location对象
     */
    fun obtain(provider: String = LocationManager.GPS_PROVIDER): Location {
        obtainCount.incrementAndGet()

        if (!enabled) {
            createCount.incrementAndGet()
            return createNewLocation(provider)
        }

        // 从池中获取对象
        var pooled = pool.poll()

        // 如果池为空，创建新对象
        if (pooled == null) {
            createCount.incrementAndGet()
            return createNewLocation(provider)
        }

        // 检查复用次数是否超限
        if (pooled.reuseCount >= MAX_REUSE_COUNT) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationPool: object exceeded max reuse count, creating new")
            }
            createCount.incrementAndGet()
            return createNewLocation(provider)
        }

        // 复用对象
        hitCount.incrementAndGet()
        pooled.reuseCount++

        // 重置Location对象
        resetLocation(pooled.location, provider)

        return pooled.location
    }

    /**
     * 回收Location对象到池中
     *
     * @param location 要回收的Location对象
     */
    fun recycle(location: Location?) {
        if (location == null || !enabled) {
            return
        }

        recycleCount.incrementAndGet()

        // 检查池是否已满
        if (pool.size >= MAX_POOL_SIZE) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationPool: pool full, discarding object")
            }
            return
        }

        // 清理敏感信息
        clearSensitiveData(location)

        // 放回池中
        pool.offer(PooledLocation(location))
    }

    /**
     * 创建新的Location对象
     */
    private fun createNewLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
        return Location(provider)
    }

    /**
     * 重置Location对象到初始状态
     */
    private fun resetLocation(location: Location, provider: String) {
        // 重置provider
        location.provider = provider

        // 重置基本属性
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()

        // 移除所有附加数据
        location.removeSpeed()
        location.removeBearing()
        location.removeAltitude()
        location.removeAccuracy()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.removeSpeedAccuracy()
            location.removeBearingAccuracy()
            location.removeVerticalAccuracy()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.removeElapsedRealtimeUncertaintyNanos()
        }

        // 清理extras
        location.extras?.clear()
    }

    /**
     * 清理Location对象中的敏感信息
     */
    private fun clearSensitiveData(location: Location) {
        // 清除坐标
        location.latitude = 0.0
        location.longitude = 0.0

        // 清除extras中可能包含的敏感信息
        location.extras?.clear()
    }

    /**
     * 清空对象池
     */
    fun clear() {
        pool.clear()
        if (FakeLoc.enableDebugLog) {
            Logger.debug("LocationPool cleared")
        }
    }

    /**
     * 获取对象池统计信息
     *
     * @return 统计信息字符串
     */
    fun getStats(): String {
        val obtains = obtainCount.get()
        val hits = hitCount.get()
        val hitRate = if (obtains > 0) {
            (hits * 100.0 / obtains)
        } else {
            0.0
        }

        return """
            LocationPool Statistics:
            - Enabled: $enabled
            - Pool size: ${pool.size}/$MAX_POOL_SIZE
            - Obtain count: $obtains
            - Recycle count: ${recycleCount.get()}
            - Create count: ${createCount.get()}
            - Hit count: $hits
            - Hit rate: ${"%.2f".format(hitRate)}%
        """.trimIndent()
    }

    /**
     * 重置统计信息
     */
    fun resetStats() {
        obtainCount.set(0)
        recycleCount.set(0)
        createCount.set(0)
        hitCount.set(0)
        if (FakeLoc.enableDebugLog) {
            Logger.debug("LocationPool stats reset")
        }
    }

    /**
     * 预热对象池（在模块启动时调用）
     */
    fun warmUp(count: Int = INITIAL_POOL_SIZE) {
        repeat(count.coerceAtMost(MAX_POOL_SIZE)) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.offer(PooledLocation(createNewLocation()))
            }
        }
        if (FakeLoc.enableDebugLog) {
            Logger.debug("LocationPool warmed up with $count objects")
        }
    }

    /**
     * 获取池的当前大小
     */
    fun size(): Int = pool.size

    /**
     * 检查池是否为空
     */
    fun isEmpty(): Boolean = pool.isEmpty()

    /**
     * 获取命中率
     */
    fun getHitRate(): Double {
        val obtains = obtainCount.get()
        val hits = hitCount.get()
        return if (obtains > 0) {
            (hits * 100.0 / obtains)
        } else {
            0.0
        }
    }
}
