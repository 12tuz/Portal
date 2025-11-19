package john.fucklocation.xposed.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 位置精度管理器
 *
 * 根据应用的权限（精确/粗略）动态调整返回的位置精度
 * 避免权限不匹配导致的检测
 */
object LocationAccuracyManager {

    /**
     * 位置精度等级
     */
    enum class AccuracyLevel {
        FINE,    // 精确位置 (ACCESS_FINE_LOCATION) - GPS级别，5-50米
        COARSE   // 粗略位置 (ACCESS_COARSE_LOCATION) - 城市级别，约500-2000米
    }

    /**
     * 全局精度设置（可被应用覆盖）
     */
    @Volatile
    var globalAccuracyLevel: AccuracyLevel = AccuracyLevel.FINE

    /**
     * 是否根据应用权限自动调整精度
     */
    @Volatile
    var autoAdjustByPermission: Boolean = true

    /**
     * 根据调用者UID检测权限并调整位置精度
     *
     * @param location 原始位置对象
     * @param callerUid 调用者UID
     * @param context 系统上下文（可选，用于检查权限）
     * @return 调整精度后的位置对象
     */
    fun adjustAccuracyByPermission(
        location: Location,
        callerUid: Int,
        context: Context? = null
    ): Location {
        if (!autoAdjustByPermission) {
            return if (globalAccuracyLevel == AccuracyLevel.COARSE) {
                applyCoarseAccuracy(location)
            } else {
                location
            }
        }

        // 检查调用者的位置权限
        val hasFINEPermission = checkPermission(context, callerUid, Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCOARSEPermission = checkPermission(context, callerUid, Manifest.permission.ACCESS_COARSE_LOCATION)

        return when {
            // 有精确权限 -> 返回精确位置
            hasFINEPermission -> location

            // 只有粗略权限 -> 返回粗略位置
            hasCOARSEPermission -> applyCoarseAccuracy(location)

            // 无权限 -> 返回精确位置（因为已经被Hook，权限检查可能被绕过）
            else -> location
        }
    }

    /**
     * 将位置降级为粗略精度
     *
     * 粗略位置特征：
     * - 坐标精度约0.01度 (约1km)
     * - accuracy字段: 500-2000米
     * - 移除高精度字段（speed, bearing, altitude）
     */
    private fun applyCoarseAccuracy(location: Location): Location {
        val coarseLocation = Location(location)

        // 坐标模糊化：保留2位小数（约1km精度）
        coarseLocation.latitude = (location.latitude * 100).roundToInt() / 100.0
        coarseLocation.longitude = (location.longitude * 100).roundToInt() / 100.0

        // 设置粗略精度范围：500-2000米
        coarseLocation.accuracy = 500f + Random.nextFloat() * 1500f

        // 移除精确定位的特征字段
        coarseLocation.removeSpeed()
        coarseLocation.removeBearing()
        coarseLocation.removeAltitude()

        // 移除Android 8.0+的精度字段
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            coarseLocation.removeSpeedAccuracy()
            coarseLocation.removeBearingAccuracy()
            coarseLocation.removeVerticalAccuracy()
        }

        // 移除extras中的高精度标记
        coarseLocation.extras?.remove("satellites")
        coarseLocation.extras?.remove("maxCn0")
        coarseLocation.extras?.remove("meanCn0")

        // 确保provider标记为NETWORK（粗略位置通常来自网络）
        coarseLocation.provider = LocationManager.NETWORK_PROVIDER

        if (FakeLoc.enableDebugLog) {
            Logger.debug("Applied COARSE accuracy: ${coarseLocation.accuracy}m, coords: (${coarseLocation.latitude}, ${coarseLocation.longitude})")
        }

        return coarseLocation
    }

    /**
     * 检查指定UID的应用是否有某个权限
     */
    private fun checkPermission(context: Context?, uid: Int, permission: String): Boolean {
        if (context == null || uid < 10000) {
            // 系统UID或无context时默认有权限
            return true
        }

        return try {
            val packageNames = BinderUtils.getUidPackageNames(context, uid)
            packageNames?.any { packageName ->
                context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED
            } ?: true
        } catch (e: Exception) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to check permission $permission for uid $uid: ${e.message}")
            }
            true // 失败时默认有权限，避免误伤
        }
    }

    /**
     * 根据LocationRequest的精度要求调整位置
     *
     * @param location 原始位置
     * @param quality LocationRequest的quality参数
     */
    fun adjustAccuracyByQuality(location: Location, quality: Int): Location {
        return when (quality) {
            // QUALITY_LOW_POWER (104) -> 粗略位置
            104 -> applyCoarseAccuracy(location)

            // QUALITY_BALANCED_POWER_ACCURACY (102) -> 中等精度
            102 -> {
                val mediumLocation = Location(location)
                mediumLocation.accuracy = 20f + Random.nextFloat() * 30f // 20-50米
                mediumLocation
            }

            // QUALITY_HIGH_ACCURACY (100) -> 精确位置
            100 -> location

            // 默认返回精确位置
            else -> location
        }
    }
}
