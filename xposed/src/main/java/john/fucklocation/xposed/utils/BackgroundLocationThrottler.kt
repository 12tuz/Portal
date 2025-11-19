package john.fucklocation.xposed.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * 后台定位限制模拟器
 *
 * Android 12+ (API 31+) 对后台定位有严格限制:
 * - 后台应用定位更新频率降至每小时几次
 * - 精度降低
 * - 需要单独申请ACCESS_BACKGROUND_LOCATION权限
 *
 * 本类模拟系统的限制行为，避免应用检测到异常的后台高频定位
 */
object BackgroundLocationThrottler {

    /**
     * 是否启用后台定位限制模拟
     * Android 12+ 默认启用
     */
    @Volatile
    var enabled: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * 后台定位最小间隔（毫秒）
     * Android 12: 约30分钟
     * Android 14: 约1小时
     */
    private val MIN_BACKGROUND_INTERVAL_MS = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> 3600_000L // 1小时
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> 1800_000L // 30分钟
        else -> 0L
    }

    /**
     * 每个UID的最后定位更新时间戳
     */
    private val lastUpdateTimestamps = ConcurrentHashMap<Int, Long>()

    /**
     * 每个UID的前台/后台状态缓存
     * 缓存时间：5秒
     */
    private val foregroundStateCache = ConcurrentHashMap<Int, Pair<Boolean, Long>>()
    private const val CACHE_VALIDITY_MS = 5000L

    /**
     * 检查是否应该允许后台定位更新
     *
     * @param uid 调用者UID
     * @param context 系统Context（用于检查前台状态）
     * @return true如果允许更新，false如果应该被限制
     */
    fun shouldAllowBackgroundUpdate(uid: Int, context: Context?): Boolean {
        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true // Android 11及以下无限制
        }

        // 系统UID始终允许
        if (uid < 10000) {
            return true
        }

        // 检查应用是否在前台
        val isInForeground = isAppInForeground(uid, context)
        if (isInForeground) {
            // 前台应用不限制
            lastUpdateTimestamps[uid] = System.currentTimeMillis()
            return true
        }

        // 后台应用检查限流
        val lastUpdate = lastUpdateTimestamps[uid] ?: 0L
        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - lastUpdate

        val shouldAllow = timeSinceLastUpdate >= MIN_BACKGROUND_INTERVAL_MS

        if (shouldAllow) {
            lastUpdateTimestamps[uid] = now
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Background location allowed for uid $uid (${timeSinceLastUpdate / 1000}s since last)")
            }
        } else {
            if (FakeLoc.enableDebugLog) {
                val remainingSeconds = (MIN_BACKGROUND_INTERVAL_MS - timeSinceLastUpdate) / 1000
                Logger.debug("Background location throttled for uid $uid (wait ${remainingSeconds}s)")
            }
        }

        return shouldAllow
    }

    /**
     * 检查应用是否在前台
     *
     * 使用缓存避免频繁查询ActivityManager
     */
    private fun isAppInForeground(uid: Int, context: Context?): Boolean {
        if (context == null) {
            return true // 无context时默认前台，避免误伤
        }

        val now = System.currentTimeMillis()

        // 检查缓存
        val cached = foregroundStateCache[uid]
        if (cached != null && (now - cached.second) < CACHE_VALIDITY_MS) {
            return cached.first
        }

        // 查询实际状态
        val isForeground = checkForegroundState(uid, context)

        // 更新缓存
        foregroundStateCache[uid] = Pair(isForeground, now)

        return isForeground
    }

    /**
     * 实际检查前台状态
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun checkForegroundState(uid: Int, context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return true

            // 方法1: 检查RunningAppProcessInfo
            val runningProcesses = activityManager.runningAppProcesses
            val isForegroundProcess = runningProcesses?.any {
                it.uid == uid && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            } ?: false

            if (isForegroundProcess) {
                return true
            }

            // 方法2: 检查RunningServices (服务不算前台，但前台服务算)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val runningServices = activityManager.getRunningServices(100)
                val hasForegroundService = runningServices?.any { service ->
                    service.uid == uid && service.foreground
                } ?: false

                if (hasForegroundService) {
                    return true
                }
            }

            // 默认认为是后台
            false
        } catch (e: Exception) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to check foreground state for uid $uid: ${e.message}")
            }
            true // 失败时默认前台，避免误伤
        }
    }

    /**
     * 清理过期缓存（定期调用）
     */
    fun cleanupExpiredCache() {
        val now = System.currentTimeMillis()

        // 清理超过1小时未更新的UID记录
        lastUpdateTimestamps.entries.removeIf { (_, timestamp) ->
            now - timestamp > 3600_000L
        }

        // 清理前台状态缓存
        foregroundStateCache.entries.removeIf { (_, cached) ->
            now - cached.second > 60_000L // 超过1分钟
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("Cleaned up throttler cache: ${lastUpdateTimestamps.size} UIDs tracked")
        }
    }

    /**
     * 手动标记某个UID进入前台（可从ActivityRecognitionHook调用）
     */
    fun markAppForeground(uid: Int) {
        foregroundStateCache[uid] = Pair(true, System.currentTimeMillis())
    }

    /**
     * 手动标记某个UID进入后台
     */
    fun markAppBackground(uid: Int) {
        foregroundStateCache[uid] = Pair(false, System.currentTimeMillis())
    }

    /**
     * 重置某个UID的限流状态（可用于测试）
     */
    fun resetThrottle(uid: Int) {
        lastUpdateTimestamps.remove(uid)
        foregroundStateCache.remove(uid)
    }
}
