@file:Suppress("UNCHECKED_CAST")
package moe.fuqiuluo.xposed.hooks.sensor

import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.util.ArrayMap
import de.robv.android.xposed.XposedHelpers
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger
import moe.fuqiuluo.xposed.utils.afterHook
import moe.fuqiuluo.xposed.utils.beforeHook
import moe.fuqiuluo.xposed.utils.hookAllMethods
import moe.fuqiuluo.xposed.utils.hookMethodAfter
import moe.fuqiuluo.xposed.utils.onceHook
import moe.fuqiuluo.xposed.utils.onceHookAllMethod
import java.util.concurrent.ConcurrentHashMap

// https://github.com/Frazew/VirtualSensor/blob/master/app/src/main/java/fr/frazew/virtualgyroscope/XposedMod.java#L298
object SystemSensorManagerHook {
    private val listenerMap = ConcurrentHashMap<SensorEventListener, Int>()

    operator fun invoke(classLoader: ClassLoader) {
        unlockGeoSensor(classLoader)

        hookSystemSensorManager(classLoader)
        hookSystemSensorManagerQueue(classLoader)
    }

    private fun hookSystemSensorManagerQueue(classLoader: ClassLoader) {
        val cSystemSensorManagerQueue = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager\$SensorEventQueue", classLoader)
            ?: return


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

            listener.javaClass.onceHookAllMethod("onSensorChanged", beforeHook sensorHook@{
                if (!FakeLoc.enable) return@sensorHook

                val event = args[0] ?: return@sensorHook
                val sensorType = listenerMap[listener] ?: return@sensorHook

                // 获取 SensorEvent 的 values 数组
                val valuesField = event.javaClass.getDeclaredField("values")
                valuesField.isAccessible = true
                val values = valuesField.get(event) as? FloatArray ?: return@sensorHook

                when (sensorType) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        // 模拟加速度计数据
                        // 静止时: [0, 0, 9.8], 行走时加入轻微波动
                        val baseAccel = 9.8f
                        val amplitude = (FakeLoc.speed / 10.0).toFloat().coerceIn(0f, 2f)
                        val time = System.currentTimeMillis()

                        // 使用正弦波模拟行走时的加速度变化
                        val walkFreq = FakeLoc.speed / 2.0 // 步频与速度相关
                        val phase = (time * walkFreq / 1000.0 * 2 * Math.PI).toFloat()

                        values[0] = amplitude * kotlin.math.sin(phase) * 0.3f // X轴轻微摆动
                        values[1] = amplitude * kotlin.math.cos(phase * 2) * 0.2f // Y轴
                        values[2] = baseAccel + amplitude * kotlin.math.sin(phase * 2) * 0.5f // Z轴主要变化
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        // 模拟陀螺仪数据
                        // 行走时有轻微的角速度变化
                        val amplitude = (FakeLoc.speed / 20.0).toFloat().coerceIn(0f, 0.5f)
                        val time = System.currentTimeMillis()
                        val phase = (time * 0.003).toFloat()

                        values[0] = amplitude * kotlin.math.sin(phase) * 0.1f
                        values[1] = amplitude * kotlin.math.cos(phase * 1.5f) * 0.1f
                        values[2] = amplitude * kotlin.math.sin(phase * 0.5f) * 0.05f
                    }
                    Sensor.TYPE_LINEAR_ACCELERATION -> {
                        // 线性加速度 (不包含重力)
                        val amplitude = (FakeLoc.speed / 10.0).toFloat().coerceIn(0f, 1.5f)
                        val time = System.currentTimeMillis()
                        val phase = (time * FakeLoc.speed / 1000.0 * Math.PI).toFloat()

                        values[0] = amplitude * kotlin.math.sin(phase) * 0.2f
                        values[1] = amplitude * kotlin.math.cos(phase * 2) * 0.15f
                        values[2] = amplitude * kotlin.math.sin(phase * 2) * 0.3f
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        // 步数计数器 - 根据速度和时间计算累计步数
                        // 不修改，保持原值，因为需要持续累加
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        // 步态检测器 - 如果在移动则触发步态事件
                        if (FakeLoc.speed > 0.5) {
                            values[0] = 1.0f
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