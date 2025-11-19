package john.fucklocation.xposed.utils

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import john.fucklocation.xposed.hooks.LocationServiceHook
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内存泄漏防护管理器
 *
 * 定期清理：
 * 1. LocationListener中的死亡Binder
 * 2. Hook回调缓存
 * 3. 过期的位置更新时间戳
 * 4. 前台/后台状态缓存
 */
object MemoryLeakPrevention {

    private val cleanupThread = HandlerThread("PortalCleanup").apply { start() }
    private val cleanupHandler = Handler(cleanupThread.looper)

    private val isInitialized = AtomicBoolean(false)

    /**
     * 清理间隔（毫秒）
     */
    private const val CLEANUP_INTERVAL_MS = 300_000L // 5分钟

    /**
     * LocationListener清理：移除死亡的Binder
     */
    private val listenerCleanupTask = object : Runnable {
        override fun run() {
            try {
                cleanupDeadListeners()
            } catch (e: Exception) {
                Logger.error("Listener cleanup failed", e)
            } finally {
                // 重新调度
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
    }

    /**
     * 后台定位限流器清理
     */
    private val throttlerCleanupTask = object : Runnable {
        override fun run() {
            try {
                BackgroundLocationThrottler.cleanupExpiredCache()
            } catch (e: Exception) {
                Logger.error("Throttler cleanup failed", e)
            } finally {
                cleanupHandler.postDelayed(this, CLEANUP_INTERVAL_MS)
            }
        }
    }

    /**
     * 初始化并启动清理任务
     */
    fun initialize() {
        if (isInitialized.getAndSet(true)) {
            Logger.warn("MemoryLeakPrevention already initialized")
            return
        }

        // 启动定期清理任务
        cleanupHandler.postDelayed(listenerCleanupTask, CLEANUP_INTERVAL_MS)
        cleanupHandler.postDelayed(throttlerCleanupTask, CLEANUP_INTERVAL_MS)

        Logger.info("MemoryLeakPrevention initialized (cleanup interval: ${CLEANUP_INTERVAL_MS / 1000}s)")
    }

    /**
     * 清理死亡的LocationListener
     */
    private fun cleanupDeadListeners() {
        val listeners = LocationServiceHook.locationListeners
        val sizeBefore = listeners.size

        // 移除死亡的Binder
        listeners.removeIf { (_, listener) ->
            !listener.asBinder().isBinderAlive
        }

        val sizeAfter = listeners.size
        val removed = sizeBefore - sizeAfter

        if (removed > 0) {
            Logger.info("Cleaned up $removed dead listeners (${sizeAfter} remaining)")
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("Listener cleanup: $sizeBefore -> $sizeAfter")
        }
    }

    /**
     * 手动触发清理（测试用）
     */
    fun triggerCleanup() {
        cleanupDeadListeners()
        BackgroundLocationThrottler.cleanupExpiredCache()
        Logger.info("Manual cleanup triggered")
    }

    /**
     * 停止清理任务（谨慎使用）
     */
    fun shutdown() {
        cleanupHandler.removeCallbacksAndMessages(null)
        cleanupThread.quitSafely()
        isInitialized.set(false)
        Logger.info("MemoryLeakPrevention shutdown")
    }
}

/**
 * Hook回调弱引用缓存
 *
 * 用于存储Hook回调对象，避免强引用导致内存泄漏
 */
object HookCallbackCache {

    private val cache = ConcurrentHashMap<String, WeakReference<Any>>()

    /**
     * 存储回调对象
     */
    fun put(key: String, callback: Any) {
        cache[key] = WeakReference(callback)
    }

    /**
     * 获取回调对象
     */
    fun get(key: String): Any? {
        val ref = cache[key]
        val obj = ref?.get()

        // 清理已被GC的引用
        if (ref != null && obj == null) {
            cache.remove(key)
        }

        return obj
    }

    /**
     * 移除回调对象
     */
    fun remove(key: String) {
        cache.remove(key)
    }

    /**
     * 清理所有已被GC的引用
     */
    fun cleanup() {
        val iterator = cache.iterator()
        var count = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.get() == null) {
                iterator.remove()
                count++
            }
        }
        if (count > 0 && FakeLoc.enableDebugLog) {
            Logger.debug("Cleaned up $count GC'd hook callbacks")
        }
    }

    /**
     * 获取缓存大小
     */
    fun size(): Int = cache.size
}
