package john.fucklocation.portal.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import john.fucklocation.portal.android.coro.CoroutineController
import john.fucklocation.portal.android.coro.CoroutineRouteMock
import john.fucklocation.portal.ext.Loc4j
import john.fucklocation.portal.ext.accuracy
import john.fucklocation.portal.ext.altitude
import john.fucklocation.portal.ext.reportDuration
import john.fucklocation.portal.ext.speed
import john.fucklocation.portal.service.MockServiceHelper
import john.fucklocation.portal.ui.mock.HistoricalLocation
import john.fucklocation.portal.ui.mock.HistoricalRoute
import john.fucklocation.portal.ui.mock.Rocker
import john.fucklocation.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null


    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    if(!MockServiceHelper.move(locationManager!!, FakeLoc.speed / (1000 / delayTime) / 0.85, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }

//                    if (MockServiceHelper.broadcastLocation(locationManager!!)) {
//                        Log.d("MockServiceViewModel", "Broadcast location")
//                    } else {
//                        Log.e("MockServiceViewModel", "Failed to broadcast location")
//                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()
            routeMockJob = GlobalScope.launch {
                // 在路线模拟开始时启用路线模式
                var routeModeEnabled = false

                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)

                    // 确保路线模式已启用（只在第一次或恢复时执行）
                    if (!routeMockCoroutine.isPaused && !routeModeEnabled) {
                        MockServiceHelper.enableRouteMode(locationManager!!)
                        routeModeEnabled = true
                        if (FakeLoc.enableDebugLog) {
                            Log.d("MockServiceViewModel", "路线模拟模式已启用")
                        }
                    }

                    // 如果暂停，禁用路线模式
                    if (routeMockCoroutine.isPaused && routeModeEnabled) {
                        MockServiceHelper.disableRouteMode(locationManager!!)
                        routeModeEnabled = false
                        if (FakeLoc.enableDebugLog) {
                            Log.d("MockServiceViewModel", "路线模拟模式已暂停")
                        }
                    }

                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            locationManager!!,
                            selectedRoute!!.route[0].first,
                            selectedRoute!!.route[0].second
                        )
                        routeStage++
                    }
                    val route = selectedRoute!!.route

                    // 处理所有已到达的阶段
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = location!!.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        // 判断距离是否小于1米（可根据需要调整阈值）
                        if (inverse.s12 < 1.0) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++
                        } else if (inverse.s12 < FakeLoc.speed / (1000 / delayTime) / 0.85) {
                            // 如果距离小于速度，直接移动到目标点
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++

                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 禁用路线模式
                        if (routeModeEnabled) {
                            MockServiceHelper.disableRouteMode(locationManager!!)
                            routeModeEnabled = false
                        }
                        // 重设阶段
                        routeStage = 0
                        break // 退出循环
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(locationManager!!)
                    val currentLat = location!!.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth")
                    if (!MockServiceHelper.move(
                            locationManager!!,
                            FakeLoc.speed / (1000 / delayTime) / 0.85,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }
}