@file:Suppress("UNCHECKED_CAST")
package john.fucklocation.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.util.ArrayMap
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.afterHook
import john.fucklocation.xposed.utils.beforeHook
import john.fucklocation.xposed.utils.hookAllMethods
import john.fucklocation.xposed.utils.hookMethodAfter
import john.fucklocation.xposed.utils.onceHook
import john.fucklocation.xposed.utils.onceHookAllMethod
import java.util.Collections
import java.util.WeakHashMap

// https://github.com/Frazew/VirtualSensor/blob/master/app/src/main/java/fr/frazew/virtualgyroscope/XposedMod.java#L298
object SystemSensorManagerHook {
    // 使用 WeakHashMap 防止内存泄漏，当 SensorEventListener 被 GC 回收时自动移除
    private val listenerMap = Collections.synchronizedMap(WeakHashMap<SensorEventListener, Int>())

    // 监听器数量上限警告阈值
    private const val LISTENER_WARNING_THRESHOLD = 100

    // 步数计数器状态
    private var baseStepCount = 0L
    private var lastStepTime = 0L

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val cSystemSensorManagerQueue = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: return

        // Hook dispatchSensorEvent 来拦截传感器事件队列
        kotlin.runCatching {
            XposedBridge.hookAllMethods(cSystemSensorManagerQueue, "dispatchSensorEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!FakeLoc.enableSensorSimulation) return

                    // 获取传感器句柄和数据
                    val handle = param.args[0] as? Int ?: return
                    val values = param.args[1] as? FloatArray ?: return

                    // 根据传感器类型修改数据
                    modifySensorData(handle, values)
                }
            })
            Logger.info("Hooked SystemSensorManager.SensorEventQueue.dispatchSensorEvent")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to hook SensorEventQueue: ${it.message}")
            }
        }
    }

    /**
     * 根据传感器句柄修改数据
     * 注意：句柄到传感器类型的映射需要在运行时确定
     */
    private fun modifySensorData(handle: Int, values: FloatArray) {
        // 加速度计通常是前几个句柄
        if (values.size >= 3) {
            val mode = FakeLoc.getEffectiveTransportMode()
            val time = System.currentTimeMillis()

            // 简单的步频模拟
            when (mode) {
                FakeLoc.TransportMode.WALKING, FakeLoc.TransportMode.RUNNING -> {
                    val stepFreq = FakeLoc.getCurrentStepFrequency()
                    val amplitude = if (mode == FakeLoc.TransportMode.WALKING) 1.5f else 3.0f
                    val phase = (time * stepFreq * 2 * Math.PI / 1000).toFloat()

                    // 添加步态周期性运动
                    values[0] += kotlin.math.sin(phase) * amplitude * 0.3f
                    values[1] += kotlin.math.cos(phase) * amplitude * 0.2f
                    values[2] += kotlin.math.sin(phase * 2) * amplitude * 0.5f
                }
                else -> {
                    // 其他模式添加小噪声
                    values[0] += kotlin.random.Random.nextFloat() * 0.1f - 0.05f
                    values[1] += kotlin.random.Random.nextFloat() * 0.1f - 0.05f
                    values[2] += kotlin.random.Random.nextFloat() * 0.1f - 0.05f
                }
            }
        }
    }

    private fun hookSystemSensorManager(classLoader: ClassLoader) {
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader)
        if (cSystemSensorManager == null) {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("Failed to find SystemSensorManager")
            }
            return
        }

        val hookRegisterListenerImpl = beforeHook {
            val listener = args[0] as SensorEventListener
            if (FakeLoc.enableDebugLog) {
                Logger.debug("RegisterListenerImpl: $listener, sensor: ${args[1]}")
            }

            val sensor = args[1] as? Sensor ?: return@beforeHook
            listenerMap[listener] = sensor.type

            // 监控监听器数量，超过阈值时警告
            val currentSize = listenerMap.size
            if (currentSize > LISTENER_WARNING_THRESHOLD && currentSize % 50 == 0) {
                Logger.warn("SensorEventListener count is high: $currentSize")
            }

            listener.javaClass.onceHookAllMethod("onSensorChanged", beforeHook sensorHook@{
                if (!FakeLoc.enable) return@sensorHook
                if (!FakeLoc.enableSensorSimulation) return@sensorHook

                val event = args[0] ?: return@sensorHook
                val sensorType = listenerMap[listener] ?: return@sensorHook

                // 获取 SensorEvent 的 values 数组
                val valuesField = event.javaClass.getDeclaredField("values")
                valuesField.isAccessible = true
                val values = valuesField.get(event) as? FloatArray ?: return@sensorHook

                when (sensorType) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        // 根据交通模式模拟加速度计数据
                        val mode = FakeLoc.getEffectiveTransportMode()
                        val baseAccel = 9.8f
                        val time = System.currentTimeMillis()

                        when (mode) {
                            FakeLoc.TransportMode.STATIONARY -> {
                                // 静止 - 只有重力加速度 + 微小噪声
                                values[0] = kotlin.random.Random.nextFloat() * 0.05f
                                values[1] = kotlin.random.Random.nextFloat() * 0.05f
                                values[2] = baseAccel + kotlin.random.Random.nextFloat() * 0.05f
                            }
                            FakeLoc.TransportMode.WALKING, FakeLoc.TransportMode.RUNNING -> {
                                // 行走/跑步 - 使用步频生成周期性加速度
                                val stepFreq = FakeLoc.getCurrentStepFrequency()
                                val amplitude = mode.accelerationRange.start +
                                    (mode.accelerationRange.endInclusive - mode.accelerationRange.start) *
                                    (FakeLoc.speed / mode.speedRange.endInclusive).toFloat().coerceIn(0f, 1f)

                                // 步行周期相位 (一个完整步行周期)
                                val phase = (time * stepFreq / 1000.0 * 2 * Math.PI).toFloat()

                                // X轴: 左右摆动 (步行时身体重心左右移动)
                                values[0] = amplitude * kotlin.math.sin(phase) * 0.4f

                                // Y轴: 前后加速度 (迈步时向前推进)
                                values[1] = amplitude * kotlin.math.sin(phase * 2 + Math.PI.toFloat() / 4) * 0.3f

                                // Z轴: 垂直加速度 (步行时上下起伏) + 重力
                                values[2] = baseAccel + amplitude * kotlin.math.sin(phase * 2) * 0.6f
                            }
                            FakeLoc.TransportMode.CYCLING -> {
                                // 骑行 - 轻微周期性振动 (路面颠簸)
                                val amplitude = mode.accelerationRange.start +
                                    (mode.accelerationRange.endInclusive - mode.accelerationRange.start) * 0.5f
                                val vibrationFreq = 5.0 // 5 Hz 振动频率
                                val phase = (time * vibrationFreq / 1000.0 * 2 * Math.PI).toFloat()

                                values[0] = amplitude * kotlin.math.sin(phase * 1.3f) * 0.5f
                                values[1] = amplitude * kotlin.math.cos(phase * 1.7f) * 0.3f
                                values[2] = baseAccel + amplitude * kotlin.math.sin(phase * 2.1f) * 0.4f
                            }
                            FakeLoc.TransportMode.DRIVING, FakeLoc.TransportMode.HIGH_SPEED -> {
                                // 驾驶 - 随机低频振动 (路面起伏、刹车、转弯)
                                val amplitude = mode.accelerationRange.start +
                                    (mode.accelerationRange.endInclusive - mode.accelerationRange.start) *
                                    kotlin.random.Random.nextFloat()

                                // 模拟转弯和加减速
                                val turnPhase = (time / 5000.0 * 2 * Math.PI).toFloat()
                                values[0] = amplitude * kotlin.math.sin(turnPhase * 0.3f)
                                values[1] = amplitude * kotlin.math.sin(turnPhase * 0.5f) * 0.6f
                                values[2] = baseAccel + amplitude * 0.3f
                            }
                        }
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        // 根据交通模式模拟陀螺仪数据
                        val mode = FakeLoc.getEffectiveTransportMode()
                        val time = System.currentTimeMillis()

                        when (mode) {
                            FakeLoc.TransportMode.STATIONARY -> {
                                // 静止 - 微小噪声
                                values[0] = kotlin.random.Random.nextFloat() * 0.01f
                                values[1] = kotlin.random.Random.nextFloat() * 0.01f
                                values[2] = kotlin.random.Random.nextFloat() * 0.01f
                            }
                            FakeLoc.TransportMode.WALKING, FakeLoc.TransportMode.RUNNING -> {
                                // 行走/跑步 - 步态引起的旋转
                                val stepFreq = FakeLoc.getCurrentStepFrequency()
                                val amplitude = (FakeLoc.speed / 10.0).toFloat().coerceIn(0.1f, 0.5f)
                                val phase = (time * stepFreq / 1000.0 * 2 * Math.PI).toFloat()

                                // 模拟步行时的身体旋转
                                values[0] = amplitude * kotlin.math.sin(phase * 0.7f) * 0.2f // 俯仰
                                values[1] = amplitude * kotlin.math.cos(phase * 1.3f) * 0.15f // 滚转
                                values[2] = amplitude * kotlin.math.sin(phase * 0.5f) * 0.1f // 偏航
                            }
                            else -> {
                                // 其他模式 - 轻微随机旋转
                                val amplitude = (FakeLoc.speed / 30.0).toFloat().coerceIn(0f, 0.2f)
                                val phase = (time * 0.003).toFloat()

                                values[0] = amplitude * kotlin.math.sin(phase) * 0.1f
                                values[1] = amplitude * kotlin.math.cos(phase * 1.5f) * 0.1f
                                values[2] = amplitude * kotlin.math.sin(phase * 0.5f) * 0.05f
                            }
                        }
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        // 线性加速度 (不包含重力) - 根据交通模式
                        val mode = FakeLoc.getEffectiveTransportMode()
                        val amplitude = mode.accelerationRange.start +
                            (mode.accelerationRange.endInclusive - mode.accelerationRange.start) * 0.6f
                        val time = System.currentTimeMillis()

                        if (mode == FakeLoc.TransportMode.WALKING || mode == FakeLoc.TransportMode.RUNNING) {
                            val stepFreq = FakeLoc.getCurrentStepFrequency()
                            val phase = (time * stepFreq / 1000.0 * 2 * Math.PI).toFloat()

                            values[0] = amplitude * kotlin.math.sin(phase) * 0.2f
                            values[1] = amplitude * kotlin.math.cos(phase * 2) * 0.15f
                            values[2] = amplitude * kotlin.math.sin(phase * 2) * 0.3f
                        } else {
                            val phase = (time * FakeLoc.speed / 1000.0 * Math.PI).toFloat()
                            values[0] = amplitude * kotlin.math.sin(phase) * 0.15f
                            values[1] = amplitude * kotlin.math.cos(phase * 1.5f) * 0.1f
                            values[2] = amplitude * kotlin.math.sin(phase * 2) * 0.2f
                        }
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        // 步数计数器 - 根据步频和时间计算
                        val mode = FakeLoc.getEffectiveTransportMode()
                        val currentTime = System.currentTimeMillis()

                        // 只有行走和跑步才产生步数
                        if (mode == FakeLoc.TransportMode.WALKING || mode == FakeLoc.TransportMode.RUNNING) {
                            if (lastStepTime == 0L) {
                                lastStepTime = currentTime
                                baseStepCount = values[0].toLong()
                            }

                            val stepFreq = FakeLoc.getCurrentStepFrequency()
                            val elapsedSeconds = (currentTime - lastStepTime) / 1000.0
                            val stepsAdded = (stepFreq * elapsedSeconds).toLong()

                            values[0] = (baseStepCount + stepsAdded).toFloat()
                        }
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        // 步态检测器 - 根据步频触发
                        val mode = FakeLoc.getEffectiveTransportMode()
                        val currentTime = System.currentTimeMillis()

                        if (mode == FakeLoc.TransportMode.WALKING || mode == FakeLoc.TransportMode.RUNNING) {
                            val stepFreq = FakeLoc.getCurrentStepFrequency()
                            val stepPeriod = (1000.0 / stepFreq).toLong() // 毫秒

                            // 在步行周期的特定时刻触发
                            if ((currentTime % stepPeriod) < 100) {
                                values[0] = 1.0f
                            } else {
                                values[0] = 0.0f
                            }
                        } else {
                            values[0] = 0.0f
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        // 磁力计数据 - 模拟地球磁场
                        // 地磁场强度约为 25-65 微特斯拉
                        val time = System.currentTimeMillis()
                        val bearing = FakeLoc.bearing.toFloat()

                        // 根据方向角计算磁场分量
                        val magStrength = 45.0f // 微特斯拉
                        val inclination = 60.0f // 磁倾角（度）

                        val bearingRad = Math.toRadians(bearing.toDouble()).toFloat()
                        val inclinationRad = Math.toRadians(inclination.toDouble()).toFloat()

                        // 水平分量
                        val horizontal = magStrength * kotlin.math.cos(inclinationRad)
                        values[0] = horizontal * kotlin.math.sin(bearingRad) +
                                   kotlin.random.Random.nextFloat() * 0.5f // X (东向)
                        values[1] = horizontal * kotlin.math.cos(bearingRad) +
                                   kotlin.random.Random.nextFloat() * 0.5f // Y (北向)
                        values[2] = -magStrength * kotlin.math.sin(inclinationRad) +
                                   kotlin.random.Random.nextFloat() * 0.5f // Z (垂直向下)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        // 旋转向量 - 基于方向角
                        val bearing = FakeLoc.bearing.toFloat()
                        val bearingRad = Math.toRadians(bearing.toDouble()).toFloat() / 2

                        // 四元数表示 (x, y, z, w)
                        values[0] = 0f // x
                        values[1] = 0f // y
                        values[2] = kotlin.math.sin(bearingRad) // z
                        if (values.size > 3) {
                            values[3] = kotlin.math.cos(bearingRad) // w
                        }
                    }
                    Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                        // 游戏旋转向量 (不使用磁力计)
                        val bearing = FakeLoc.bearing.toFloat()
                        val bearingRad = Math.toRadians(bearing.toDouble()).toFloat() / 2

                        values[0] = 0f
                        values[1] = 0f
                        values[2] = kotlin.math.sin(bearingRad)
                        if (values.size > 3) {
                            values[3] = kotlin.math.cos(bearingRad)
                        }
                    }
                    Sensor.TYPE_PRESSURE -> {
                        // 气压计 - 根据海拔高度计算大气压强
                        // 标准海平面气压 1013.25 hPa
                        // 海拔每升高100米，气压大约下降12 hPa
                        val altitude = FakeLoc.altitude
                        val seaLevelPressure = 1013.25
                        // 使用气压高度公式: P = P0 * (1 - L*h/T0)^(g*M/(R*L))
                        // 简化公式: P = P0 * e^(-h/8500)
                        val pressure = (seaLevelPressure * kotlin.math.exp(-altitude / 8500.0)).toFloat()
                        // 添加小幅波动模拟真实气压变化
                        values[0] = pressure + kotlin.random.Random.nextFloat() * 0.5f - 0.25f
                    }
                    Sensor.TYPE_LIGHT -> {
                        // 光线传感器 - 根据时间模拟昼夜变化
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val baseLux = when (hour) {
                            in 6..8 -> 1000f + (hour - 6) * 5000f // 日出
                            in 9..17 -> 15000f + kotlin.random.Random.nextFloat() * 5000f // 白天
                            in 18..20 -> 15000f - (hour - 18) * 7000f // 日落
                            else -> 10f + kotlin.random.Random.nextFloat() * 50f // 夜间
                        }
                        // 添加随机波动
                        values[0] = baseLux * (0.9f + kotlin.random.Random.nextFloat() * 0.2f)
                    }
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        // 环境温度传感器 - 根据时间模拟温度变化
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        // 基础温度 20°C，日间较高夜间较低
                        val baseTemp = when (hour) {
                            in 0..6 -> 15f + (hour * 0.5f)
                            in 7..14 -> 18f + (hour - 7) * 1.5f
                            in 15..20 -> 28f - (hour - 15) * 1.0f
                            else -> 23f - (hour - 20) * 2.0f
                        }
                        values[0] = baseTemp + kotlin.random.Random.nextFloat() * 2f - 1f
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        // 相对湿度传感器 - 根据时间模拟湿度变化
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        // 清晨湿度高，午后湿度低
                        val baseHumidity = when (hour) {
                            in 0..6 -> 75f + (6 - hour) * 3f
                            in 7..14 -> 75f - (hour - 7) * 5f
                            in 15..20 -> 40f + (hour - 15) * 6f
                            else -> 70f
                        }
                        values[0] = (baseHumidity + kotlin.random.Random.nextFloat() * 10f - 5f).coerceIn(20f, 100f)
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        // 距离传感器 - 通常为二值（近/远）
                        // 保持原值或设为远距离
                        if (values[0] == 0f) {
                            values[0] = 5.0f // 远距离
                        }
                    }
                    Sensor.TYPE_GRAVITY -> {
                        // 重力传感器 - 始终指向地心
                        // 标准重力加速度 9.8 m/s²
                        values[0] = kotlin.random.Random.nextFloat() * 0.02f
                        values[1] = kotlin.random.Random.nextFloat() * 0.02f
                        values[2] = 9.8f + kotlin.random.Random.nextFloat() * 0.05f
                    }
                }

                valuesField.set(event, values)
            })
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "registerListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
                    && it.parameterTypes[1] == Sensor::class.java
        }.forEach {
            it.onceHook(hookRegisterListenerImpl)
        }

        val hookUnregisterListenerImpl = beforeHook {
            val listener = args[0] as SensorEventListener
            if (FakeLoc.enableDebugLog) {
                Logger.debug("UnregisterListenerImpl: $listener")
            }
            listenerMap.remove(listener)
        }
        cSystemSensorManager.declaredMethods.filter {
            it.name == "unregisterListenerImpl" && it.parameterTypes.isNotEmpty()
                    && it.parameterTypes[0] == SensorEventListener::class.java
        }.forEach {
            it.onceHook(hookUnregisterListenerImpl)
        }

        cSystemSensorManager.hookAllMethods("getSensorList", afterHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getSensorList: type: ${args[0]} -> $result")
            }
        })
        cSystemSensorManager.hookAllMethods("getFullSensorsList", afterHook {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getFullSensorsList-> $result")
            }
        })
    }

    private fun unlockGeoSensor(classLoader: ClassLoader) {
        val cSystemConfig = XposedHelpers.findClassIfExists("com.android.server.SystemConfig", classLoader)
            ?: return

        val openGLVersion = run {
            val cSystemProperties = XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
                ?: return@run 0
            XposedHelpers.callStaticMethod(cSystemProperties, "getInt", "ro.opengles.version", FeatureInfo.GL_ES_VERSION_UNDEFINED) as Int
        }

        cSystemConfig.hookMethodAfter("getAvailableFeatures") {
            val features = result as ArrayMap<String, FeatureInfo>
            if (FakeLoc.enableDebugLog) {
                Logger.debug("getAvailableFeatures: ${features.keys}")
            }

//            if (!features.contains(PackageManager.FEATURE_SENSOR_GYROSCOPE)) {
//                val gyroFeature = FeatureInfo()
//                gyroFeature.name = PackageManager.FEATURE_SENSOR_GYROSCOPE
//                gyroFeature.reqGlEsVersion = openGLVersion
//                features[PackageManager.FEATURE_SENSOR_GYROSCOPE] = gyroFeature
//
//                if (FakeLoc.enableDebugLog) {
//                    Logger.debug("Added FEATURE_SENSOR_GYROSCOPE")
//                }
//            }
//            if (!features.contains(PackageManager.FEATURE_SENSOR_COMPASS)) {
//                val compassFeature = FeatureInfo()
//                compassFeature.name = PackageManager.FEATURE_SENSOR_COMPASS
//                compassFeature.reqGlEsVersion = openGLVersion
//                features[PackageManager.FEATURE_SENSOR_COMPASS] = compassFeature
//
//                if (FakeLoc.enableDebugLog) {
//                    Logger.debug("Added FEATURE_SENSOR_COMPASS")
//                }
//            }
//
//            if (!features.contains(PackageManager.FEATURE_SENSOR_ACCELEROMETER)) {
//                val accelerometerFeature = FeatureInfo()
//                accelerometerFeature.name = PackageManager.FEATURE_SENSOR_ACCELEROMETER
//                accelerometerFeature.reqGlEsVersion = openGLVersion
//                features[PackageManager.FEATURE_SENSOR_ACCELEROMETER] = accelerometerFeature
//
//                if (FakeLoc.enableDebugLog) {
//                    Logger.debug("Added FEATURE_SENSOR_ACCELEROMETER")
//                }
//            }
//
//            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_COUNTER)) {
//                val lightFeature = FeatureInfo()
//                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_COUNTER
//                lightFeature.reqGlEsVersion = openGLVersion
//                features[PackageManager.FEATURE_SENSOR_STEP_COUNTER] = lightFeature
//
//                if (FakeLoc.enableDebugLog) {
//                    Logger.debug("Added FEATURE_SENSOR_STEP_COUNTER")
//                }
//            }
//
//            if (!features.contains(PackageManager.FEATURE_SENSOR_STEP_DETECTOR)) {
//                val lightFeature = FeatureInfo()
//                lightFeature.name = PackageManager.FEATURE_SENSOR_STEP_DETECTOR
//                lightFeature.reqGlEsVersion = openGLVersion
//                features[PackageManager.FEATURE_SENSOR_STEP_DETECTOR] = lightFeature
//
//                if (FakeLoc.enableDebugLog) {
//                    Logger.debug("Added FEATURE_SENSOR_STEP_DETECTOR")
//                }
//            }
//
//            kotlin.runCatching {
//                XposedHelpers.setObjectField(thisObject, "mAvailableFeatures", features)
//            }.onFailure {
//                Logger.warn("Failed to set mAvailableFeatures", it)
//            }
            result = features
        }
    }
}
