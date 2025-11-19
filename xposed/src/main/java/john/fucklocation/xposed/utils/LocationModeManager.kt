package john.fucklocation.xposed.utils

import android.location.Location

/**
 * 位置模式管理器
 *
 * 统一管理单点模拟和路线模拟，防止冲突
 *
 * 优先级规则：
 * 1. 路线模拟 > 单点模拟（路线是动态的，优先级更高）
 * 2. 同一时刻只能有一种模式生效
 * 3. 模式切换时清理旧状态
 */
object LocationModeManager {

    /**
     * 位置模式
     */
    enum class LocationMode {
        DISABLED,      // 禁用模拟
        SINGLE_POINT,  // 单点位置模拟
        ROUTE         // 路线模拟
    }

    /**
     * 当前生效的模式
     */
    @Volatile
    private var currentMode: LocationMode = LocationMode.DISABLED

    /**
     * 路线模拟的位置提供者（由UI层注入）
     */
    @Volatile
    var routeLocationProvider: (() -> Pair<Double, Double>)? = null

    /**
     * 单点模拟的固定位置
     */
    private var singlePointLat: Double = 0.0
    private var singlePointLon: Double = 0.0

    /**
     * 最后更新时间戳
     */
    private var lastUpdateTime: Long = 0

    /**
     * 启用单点位置模拟
     *
     * @param latitude 纬度
     * @param longitude 经度
     */
    fun enableSinglePoint(latitude: Double, longitude: Double) {
        synchronized(this) {
            // 如果路线模拟正在运行，先停止路线模拟
            if (currentMode == LocationMode.ROUTE) {
                Logger.warn("LocationModeManager: 路线模拟正在运行，切换到单点模拟")
                disableRoute()
            }

            singlePointLat = latitude
            singlePointLon = longitude
            currentMode = LocationMode.SINGLE_POINT
            lastUpdateTime = System.currentTimeMillis()

            // 更新FakeLoc
            FakeLoc.latitude = latitude
            FakeLoc.longitude = longitude

            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationModeManager: 单点模拟已启用 ($latitude, $longitude)")
            }
        }
    }

    /**
     * 启用路线模拟
     *
     * @param provider 路线位置提供者（返回当前路线上的位置）
     */
    fun enableRoute(provider: () -> Pair<Double, Double>) {
        synchronized(this) {
            // 如果单点模拟正在运行，先停止单点模拟
            if (currentMode == LocationMode.SINGLE_POINT) {
                Logger.warn("LocationModeManager: 单点模拟正在运行，切换到路线模拟")
                disableSinglePoint()
            }

            routeLocationProvider = provider
            currentMode = LocationMode.ROUTE
            lastUpdateTime = System.currentTimeMillis()

            if (FakeLoc.enableDebugLog) {
                Logger.debug("LocationModeManager: 路线模拟已启用")
            }
        }
    }

    /**
     * 禁用单点模拟
     */
    fun disableSinglePoint() {
        synchronized(this) {
            if (currentMode == LocationMode.SINGLE_POINT) {
                currentMode = LocationMode.DISABLED
                singlePointLat = 0.0
                singlePointLon = 0.0

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("LocationModeManager: 单点模拟已禁用")
                }
            }
        }
    }

    /**
     * 禁用路线模拟
     */
    fun disableRoute() {
        synchronized(this) {
            if (currentMode == LocationMode.ROUTE) {
                currentMode = LocationMode.DISABLED
                routeLocationProvider = null

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("LocationModeManager: 路线模拟已禁用")
                }
            }
        }
    }

    /**
     * 获取当前应该使用的位置
     *
     * @return Pair<latitude, longitude>，如果没有启用任何模式则返回null
     */
    fun getCurrentLocation(): Pair<Double, Double>? {
        return synchronized(this) {
            when (currentMode) {
                LocationMode.SINGLE_POINT -> {
                    // 单点模拟：返回固定位置
                    Pair(singlePointLat, singlePointLon)
                }
                LocationMode.ROUTE -> {
                    // 路线模拟：从提供者获取当前位置
                    try {
                        val location = routeLocationProvider?.invoke()
                        if (location != null) {
                            // 更新FakeLoc（路线模拟会实时变化）
                            FakeLoc.latitude = location.first
                            FakeLoc.longitude = location.second
                            lastUpdateTime = System.currentTimeMillis()
                        }
                        location
                    } catch (e: Exception) {
                        Logger.error("LocationModeManager: 路线位置提供者异常", e)
                        null
                    }
                }
                LocationMode.DISABLED -> {
                    // 未启用任何模式
                    null
                }
            }
        }
    }

    /**
     * 获取当前模式
     */
    fun getCurrentMode(): LocationMode {
        return currentMode
    }

    /**
     * 检查是否启用了任何模拟模式
     */
    fun isEnabled(): Boolean {
        return currentMode != LocationMode.DISABLED
    }

    /**
     * 强制更新位置（由UI层调用）
     *
     * @param latitude 纬度
     * @param longitude 经度
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        synchronized(this) {
            when (currentMode) {
                LocationMode.SINGLE_POINT -> {
                    // 更新单点模拟的位置
                    singlePointLat = latitude
                    singlePointLon = longitude
                    FakeLoc.latitude = latitude
                    FakeLoc.longitude = longitude
                    lastUpdateTime = System.currentTimeMillis()

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationModeManager: 单点位置已更新 ($latitude, $longitude)")
                    }
                }
                LocationMode.ROUTE -> {
                    // 路线模式下允许更新位置（通过move命令）
                    FakeLoc.latitude = latitude
                    FakeLoc.longitude = longitude
                    lastUpdateTime = System.currentTimeMillis()

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationModeManager: 路线位置已更新 ($latitude, $longitude)")
                    }
                }
                LocationMode.DISABLED -> {
                    Logger.warn("LocationModeManager: 未启用任何模式，无法更新位置")
                }
            }
        }
    }

    /**
     * 获取状态信息（用于调试）
     */
    fun getStatus(): String {
        return synchronized(this) {
            """
            LocationModeManager状态:
            - 当前模式: $currentMode
            - 单点位置: ($singlePointLat, $singlePointLon)
            - 路线提供者: ${if (routeLocationProvider != null) "已设置" else "未设置"}
            - FakeLoc位置: (${FakeLoc.latitude}, ${FakeLoc.longitude})
            - 最后更新: ${System.currentTimeMillis() - lastUpdateTime}ms前
            """.trimIndent()
        }
    }

    /**
     * 清除所有模式
     */
    fun clearAll() {
        synchronized(this) {
            currentMode = LocationMode.DISABLED
            singlePointLat = 0.0
            singlePointLon = 0.0
            routeLocationProvider = null
            lastUpdateTime = 0

            Logger.info("LocationModeManager: 所有模式已清除")
        }
    }
}
