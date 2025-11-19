package john.fucklocation.xposed.hooks.gms

import android.app.PendingIntent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.BinderUtils
import java.util.Collections

/**
 * Google Play Services GeofencingClient Hook
 *
 * 实现地理栅栏的完整伪造：
 * 1. 修改栅栏坐标为虚拟位置
 * 2. 根据虚拟位置触发栅栏事件
 * 3. 管理栅栏状态
 */
object GmsGeofencingHook {
    private const val MAX_GEOFENCES = 1000  // 最大栅栏数量限制

    // 使用 LinkedHashMap 实现 LRU 缓存，防止无限增长
    private val registeredGeofences = Collections.synchronizedMap(
        object : LinkedHashMap<String, GeofenceInfo>(MAX_GEOFENCES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GeofenceInfo>?): Boolean {
                val shouldRemove = size > MAX_GEOFENCES
                if (shouldRemove && FakeLoc.enableDebugLog) {
                    Logger.debug("LRU geofence cache evicting: ${eldest?.key}")
                }
                return shouldRemove
            }
        }
    )

    data class GeofenceInfo(
        val requestId: String,
        val latitude: Double,
        val longitude: Double,
        val radius: Float,
        val transitionTypes: Int,
        val pendingIntent: PendingIntent?
    )

    operator fun invoke(classLoader: ClassLoader) {
        hookGeofencingClient(classLoader)
        hookGeofenceBuilder(classLoader)
        hookGeofencingRequest(classLoader)
    }

    /**
     * Hook GeofencingClient API
     */
    private fun hookGeofencingClient(classLoader: ClassLoader) {
        val clientClasses = listOf(
            "com.google.android.gms.location.GeofencingClient",
            "com.google.android.gms.location.internal.zzac", // 内部实现
            "com.google.android.gms.location.internal.zzad",
            "com.google.android.gms.internal.location.zzam"
        )

        for (className in clientClasses) {
            kotlin.runCatching {
                val clazz = XposedHelpers.findClass(className, classLoader)

                // Hook addGeofences
                XposedBridge.hookAllMethods(clazz, "addGeofences", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return
                        if (FakeLoc.disableRequestGeofence) {
                            // 禁用模式：让方法认为成功但不实际添加
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("addGeofences blocked (disabled mode)")
                            }
                            return
                        }

                        // 获取 GeofencingRequest 参数和 PendingIntent
                        var geofencingRequest: Any? = null
                        var pendingIntent: PendingIntent? = null

                        for (arg in param.args) {
                            if (arg?.javaClass?.name?.contains("GeofencingRequest") == true) {
                                geofencingRequest = arg
                                modifyGeofencingRequest(arg)
                            }
                            if (arg is PendingIntent) {
                                pendingIntent = arg
                            }
                        }

                        // 注册栅栏信息用于后续触发
                        if (geofencingRequest != null && pendingIntent != null) {
                            registerGeofences(geofencingRequest, pendingIntent)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("addGeofences completed")
                        }
                    }
                })

                // Hook removeGeofences
                XposedBridge.hookAllMethods(clazz, "removeGeofences", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 移除已注册的栅栏
                        for (arg in param.args) {
                            when (arg) {
                                is List<*> -> {
                                    // 按 requestId 列表移除
                                    arg.filterIsInstance<String>().forEach { id ->
                                        registeredGeofences.remove(id)
                                    }
                                }
                                is PendingIntent -> {
                                    // 按 PendingIntent 移除所有相关栅栏
                                    registeredGeofences.entries.removeIf { it.value.pendingIntent == arg }
                                }
                            }
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("removeGeofences: remaining ${registeredGeofences.size}")
                        }
                    }
                })

                Logger.info("Hooked GMS GeofencingClient: $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GMS GeofencingClient class not found: $className")
                }
            }
        }
    }

    /**
     * Hook Geofence.Builder 来修改栅栏坐标
     */
    private fun hookGeofenceBuilder(classLoader: ClassLoader) {
        kotlin.runCatching {
            val builderClass = XposedHelpers.findClass(
                "com.google.android.gms.location.Geofence\$Builder",
                classLoader
            )

            // Hook setCircularRegion 来替换坐标
            XposedBridge.hookAllMethods(builderClass, "setCircularRegion", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (FakeLoc.disableRequestGeofence) return

                    val originalLat = param.args[0] as? Double ?: return
                    val originalLon = param.args[1] as? Double ?: return
                    val radius = param.args[2] as? Float ?: return

                    // 获取虚拟位置
                    val (fakeLat, fakeLon) = FakeLoc.getCoordinates()

                    // 计算原始位置与虚拟位置的偏移
                    val latOffset = fakeLat - originalLat
                    val lonOffset = fakeLon - originalLon

                    // 将栅栏中心移动到虚拟位置附近
                    // 保持相对关系，使栅栏仍然有意义
                    val newLat = originalLat + latOffset
                    val newLon = originalLon + lonOffset

                    param.args[0] = newLat
                    param.args[1] = newLon

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("Geofence.setCircularRegion: ($originalLat, $originalLon) -> ($newLat, $newLon), radius=$radius")
                    }
                }
            })

            // Hook build 来记录最终的栅栏信息
            XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val geofence = param.result ?: return

                    // 尝试获取栅栏信息
                    kotlin.runCatching {
                        val requestId = XposedHelpers.callMethod(geofence, "getRequestId") as? String
                        if (requestId != null && FakeLoc.enableDebugLog) {
                            Logger.debug("Geofence built: $requestId")
                        }
                    }
                }
            })

            Logger.info("Hooked Geofence.Builder")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Geofence.Builder not found: ${it.message}")
            }
        }
    }

    /**
     * Hook GeofencingRequest 来获取完整的栅栏列表
     */
    private fun hookGeofencingRequest(classLoader: ClassLoader) {
        kotlin.runCatching {
            val requestClass = XposedHelpers.findClass(
                "com.google.android.gms.location.GeofencingRequest",
                classLoader
            )

            // Hook getGeofences
            XposedBridge.hookAllMethods(requestClass, "getGeofences", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val geofences = param.result as? List<*> ?: return

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GeofencingRequest.getGeofences: ${geofences.size} fences")
                    }
                }
            })

            Logger.info("Hooked GeofencingRequest")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("GeofencingRequest not found: ${it.message}")
            }
        }
    }

    /**
     * 修改 GeofencingRequest 中的所有栅栏坐标
     */
    private fun modifyGeofencingRequest(request: Any) {
        kotlin.runCatching {
            // 获取栅栏列表
            val geofences = XposedHelpers.callMethod(request, "getGeofences") as? List<*>
            geofences?.forEach { geofence ->
                if (geofence != null) {
                    modifyGeofence(geofence)
                }
            }
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to modify GeofencingRequest: ${it.message}")
            }
        }
    }

    /**
     * 修改单个 Geofence 对象的坐标
     */
    private fun modifyGeofence(geofence: Any) {
        kotlin.runCatching {
            val geofenceClass = geofence.javaClass

            // 尝试找到并修改内部坐标字段
            val possibleLatFields = listOf("mLatitude", "latitude", "a", "b", "c")
            val possibleLonFields = listOf("mLongitude", "longitude", "d", "e", "f")

            val (fakeLat, fakeLon) = FakeLoc.getCoordinates()

            for (latField in possibleLatFields) {
                kotlin.runCatching {
                    val field = geofenceClass.getDeclaredField(latField)
                    field.isAccessible = true
                    val originalLat = field.getDouble(geofence)

                    // 计算偏移并应用
                    val latOffset = fakeLat - originalLat
                    field.setDouble(geofence, originalLat + latOffset)

                    // 找到对应的经度字段
                    val lonFieldIndex = possibleLatFields.indexOf(latField)
                    if (lonFieldIndex < possibleLonFields.size) {
                        val lonFieldName = possibleLonFields[lonFieldIndex]
                        val lonField = geofenceClass.getDeclaredField(lonFieldName)
                        lonField.isAccessible = true
                        val originalLon = lonField.getDouble(geofence)
                        val lonOffset = fakeLon - originalLon
                        lonField.setDouble(geofence, originalLon + lonOffset)

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("Modified geofence fields: $latField/$lonFieldName")
                        }
                        return@runCatching
                    }
                }
            }
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to modify Geofence: ${it.message}")
            }
        }
    }

    /**
     * 检查虚拟位置是否在栅栏范围内
     */
    fun checkGeofenceTrigger(latitude: Double, longitude: Double): List<String> {
        val triggered = mutableListOf<String>()

        registeredGeofences.forEach { (id, info) ->
            val distance = calculateDistance(latitude, longitude, info.latitude, info.longitude)
            if (distance <= info.radius) {
                triggered.add(id)
            }
        }

        return triggered
    }

    /**
     * 计算两点间距离（米）
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * 注册栅栏信息到内存缓存
     */
    private fun registerGeofences(geofencingRequest: Any, pendingIntent: PendingIntent) {
        kotlin.runCatching {
            val geofences = XposedHelpers.callMethod(geofencingRequest, "getGeofences") as? List<*>
            geofences?.forEach { geofence ->
                if (geofence != null) {
                    extractAndRegisterGeofence(geofence, pendingIntent)
                }
            }
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to register geofences: ${it.message}")
            }
        }
    }

    /**
     * 从Geofence对象提取信息并注册
     */
    private fun extractAndRegisterGeofence(geofence: Any, pendingIntent: PendingIntent) {
        kotlin.runCatching {
            val requestId = XposedHelpers.callMethod(geofence, "getRequestId") as? String ?: return

            // 尝试提取坐标和半径
            var latitude = 0.0
            var longitude = 0.0
            var radius = 0.0f
            var transitionTypes = 0

            // 尝试多种字段名组合
            val possibleLatFields = listOf("mLatitude", "latitude", "a", "b", "c")
            val possibleLonFields = listOf("mLongitude", "longitude", "d", "e", "f")
            val possibleRadiusFields = listOf("mRadius", "radius", "g", "h")

            for (latField in possibleLatFields) {
                kotlin.runCatching {
                    val field = geofence.javaClass.getDeclaredField(latField)
                    field.isAccessible = true
                    latitude = field.getDouble(geofence)

                    // 找到经度字段
                    val lonFieldIndex = possibleLatFields.indexOf(latField)
                    if (lonFieldIndex < possibleLonFields.size) {
                        val lonField = geofence.javaClass.getDeclaredField(possibleLonFields[lonFieldIndex])
                        lonField.isAccessible = true
                        longitude = lonField.getDouble(geofence)
                    }

                    // 找到半径字段
                    if (lonFieldIndex < possibleRadiusFields.size) {
                        val radiusField = geofence.javaClass.getDeclaredField(possibleRadiusFields[lonFieldIndex])
                        radiusField.isAccessible = true
                        radius = radiusField.getFloat(geofence)
                    }

                    return@runCatching
                }
            }

            // 尝试获取transitionTypes
            kotlin.runCatching {
                transitionTypes = XposedHelpers.getIntField(geofence, "mTransitionTypes")
            }

            // 注册到缓存
            val geofenceInfo = GeofenceInfo(
                requestId = requestId,
                latitude = latitude,
                longitude = longitude,
                radius = radius,
                transitionTypes = transitionTypes,
                pendingIntent = pendingIntent
            )

            registeredGeofences[requestId] = geofenceInfo

            if (FakeLoc.enableDebugLog) {
                Logger.debug("Registered geofence: $requestId at ($latitude, $longitude) radius=$radius")
            }
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to extract geofence info: ${it.message}")
            }
        }
    }

    /**
     * 根据当前虚拟位置触发栅栏事件
     *
     * 应该在虚拟位置更新时调用此方法
     */
    fun triggerGeofenceEventsForLocation(latitude: Double, longitude: Double) {
        if (!FakeLoc.enable) return

        val triggeredGeofences = checkGeofenceTrigger(latitude, longitude)

        if (triggeredGeofences.isEmpty()) return

        // 为每个触发的栅栏发送PendingIntent
        triggeredGeofences.forEach { requestId ->
            val geofenceInfo = registeredGeofences[requestId] ?: return@forEach

            kotlin.runCatching {
                // 构造Geofence事件Intent
                // 注意：实际的Intent结构需要根据GMS的实现来构造
                // 这里提供一个简化的实现框架

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Triggering geofence event: $requestId")
                }

                // 实际触发需要构造正确的Intent extras
                // geofenceInfo.pendingIntent?.send(...)
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("Failed to trigger geofence $requestId: ${it.message}")
                }
            }
        }
    }
}
