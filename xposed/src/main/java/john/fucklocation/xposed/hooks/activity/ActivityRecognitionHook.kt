package john.fucklocation.xposed.hooks.activity

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.BinderUtils

/**
 * 活动识别API Hook - 与TransportMode联动
 *
 * 将虚拟的交通模式映射到Google Activity Recognition API
 * 确保位置移动与识别出的活动类型一致
 */
object ActivityRecognitionHook {
    // Google Activity Recognition 活动类型常量
    private const val ACTIVITY_IN_VEHICLE = 0
    private const val ACTIVITY_ON_BICYCLE = 1
    private const val ACTIVITY_ON_FOOT = 2
    private const val ACTIVITY_RUNNING = 8
    private const val ACTIVITY_STILL = 3
    private const val ACTIVITY_WALKING = 7
    private const val ACTIVITY_UNKNOWN = 4

    operator fun invoke(classLoader: ClassLoader) {
        // Hook Google Play Services ActivityRecognitionClient
        hookGmsActivityRecognition(classLoader)

        // Hook HMS ActivityIdentification (华为)
        hookHmsActivityIdentification(classLoader)

        Logger.info("ActivityRecognitionHook initialized")
    }

    /**
     * 将TransportMode映射到Activity类型
     */
    private fun transportModeToActivity(): Int {
        val mode = FakeLoc.getEffectiveTransportMode()
        return when (mode) {
            FakeLoc.TransportMode.STATIONARY -> ACTIVITY_STILL
            FakeLoc.TransportMode.WALKING -> ACTIVITY_WALKING
            FakeLoc.TransportMode.RUNNING -> ACTIVITY_RUNNING
            FakeLoc.TransportMode.CYCLING -> ACTIVITY_ON_BICYCLE
            FakeLoc.TransportMode.DRIVING,
            FakeLoc.TransportMode.HIGH_SPEED -> ACTIVITY_IN_VEHICLE
        }
    }

    /**
     * Hook Google Play Services ActivityRecognitionClient
     */
    private fun hookGmsActivityRecognition(classLoader: ClassLoader) {
        // ActivityRecognitionResult
        kotlin.runCatching {
            val activityResultClass = XposedHelpers.findClass(
                "com.google.android.gms.location.ActivityRecognitionResult",
                classLoader
            )

            // Hook getMostProbableActivity
            XposedBridge.hookAllMethods(activityResultClass, "getMostProbableActivity", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    val detectedActivity = param.result
                    if (detectedActivity != null) {
                        // 替换为虚拟活动
                        param.result = createFakeDetectedActivity(detectedActivity)
                    }
                }
            })

            // Hook getProbableActivities
            XposedBridge.hookAllMethods(activityResultClass, "getProbableActivities", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    @Suppress("UNCHECKED_CAST")
                    val activities = param.result as? MutableList<Any>
                    if (activities != null && activities.isNotEmpty()) {
                        // 将第一个活动替换为虚拟活动，其他置低可信度
                        val fakeActivity = createFakeDetectedActivity(activities[0])
                        activities.clear()
                        activities.add(fakeActivity)
                    }
                }
            })

            Logger.info("Hooked GMS ActivityRecognitionResult")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("GMS ActivityRecognitionResult not found")
            }
        }

        // DetectedActivity
        kotlin.runCatching {
            val detectedActivityClass = XposedHelpers.findClass(
                "com.google.android.gms.location.DetectedActivity",
                classLoader
            )

            // Hook getType
            XposedBridge.hookAllMethods(detectedActivityClass, "getType", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    // 返回虚拟活动类型
                    param.result = transportModeToActivity()
                }
            })

            // Hook getConfidence
            XposedBridge.hookAllMethods(detectedActivityClass, "getConfidence", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    // 设置高置信度 (75-95%)
                    param.result = 75 + kotlin.random.Random.nextInt(21)
                }
            })

            Logger.info("Hooked GMS DetectedActivity")
        }
    }

    /**
     * 创建虚拟的DetectedActivity对象
     */
    private fun createFakeDetectedActivity(template: Any): Any {
        return try {
            val activityType = transportModeToActivity()
            val confidence = 75 + kotlin.random.Random.nextInt(21) // 75-95%

            // 尝试创建新的DetectedActivity
            val constructor = template.javaClass.getConstructor(Int::class.java, Int::class.java)
            constructor.newInstance(activityType, confidence)
        } catch (e: Exception) {
            // Fallback：修改现有对象的字段
            try {
                val typeField = template.javaClass.getDeclaredField("mActivityType").apply {
                    isAccessible = true
                }
                val confidenceField = template.javaClass.getDeclaredField("mConfidence").apply {
                    isAccessible = true
                }

                typeField.set(template, transportModeToActivity())
                confidenceField.set(template, 75 + kotlin.random.Random.nextInt(21))

                template
            } catch (e2: Exception) {
                // 最终Fallback：尝试混淆后的字段名
                try {
                    template.javaClass.declaredFields.forEach { field ->
                        field.isAccessible = true
                        val value = field.get(template)
                        if (value is Int) {
                            when (value) {
                                in 0..10 -> {
                                    // 可能是activity type
                                    field.set(template, transportModeToActivity())
                                }
                                in 0..100 -> {
                                    // 可能是confidence
                                    field.set(template, 75 + kotlin.random.Random.nextInt(21))
                                }
                            }
                        }
                    }
                    template
                } catch (e3: Exception) {
                    Logger.error("Failed to create fake DetectedActivity", e3)
                    template
                }
            }
        }
    }

    /**
     * Hook 华为HMS ActivityIdentification
     */
    private fun hookHmsActivityIdentification(classLoader: ClassLoader) {
        kotlin.runCatching {
            // HMS的活动识别结果类
            val activityResultClass = XposedHelpers.findClass(
                "com.huawei.hms.location.ActivityIdentificationResponse",
                classLoader
            )

            // Hook getMostActivityIdentification
            XposedBridge.hookAllMethods(activityResultClass, "getMostActivityIdentification", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val activity = param.result
                    if (activity != null) {
                        injectHmsActivityIdentification(activity)
                    }
                }
            })

            // Hook getActivityIdentificationDatas
            XposedBridge.hookAllMethods(activityResultClass, "getActivityIdentificationDatas", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    @Suppress("UNCHECKED_CAST")
                    val activities = param.result as? List<Any>
                    activities?.forEach { injectHmsActivityIdentification(it) }
                }
            })

            Logger.info("Hooked HMS ActivityIdentification")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("HMS ActivityIdentification not found (normal on non-Huawei devices)")
            }
        }
    }

    /**
     * 注入HMS活动识别数据
     */
    private fun injectHmsActivityIdentification(activity: Any) {
        kotlin.runCatching {
            // HMS的活动类型常量与GMS类似
            val identificationTypeField = activity.javaClass.getDeclaredField("mIdentificationType").apply {
                isAccessible = true
            }
            val possibilityField = activity.javaClass.getDeclaredField("mPossibility").apply {
                isAccessible = true
            }

            identificationTypeField.set(activity, transportModeToActivity())
            possibilityField.set(activity, 75 + kotlin.random.Random.nextInt(21))
        }.onFailure {
            // Fallback：字段名扫描
            kotlin.runCatching {
                activity.javaClass.declaredFields.forEach { field ->
                    field.isAccessible = true
                    val value = field.get(activity)
                    if (value is Int) {
                        when (value) {
                            in 0..10 -> field.set(activity, transportModeToActivity())
                            in 0..100 -> field.set(activity, 75 + kotlin.random.Random.nextInt(21))
                        }
                    }
                }
            }
        }
    }
}
