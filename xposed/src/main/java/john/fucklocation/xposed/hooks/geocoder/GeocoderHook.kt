package john.fucklocation.xposed.hooks.geocoder

import android.location.Address
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.BinderUtils
import java.util.Locale

/**
 * Geocoder地理编码Hook - 防止通过地址反查真实位置
 *
 * 伪造反向地理编码结果，确保地址信息与虚拟位置一致
 */
object GeocoderHook {
    operator fun invoke(classLoader: ClassLoader) {
        hookGeocoder(classLoader)
        Logger.info("GeocoderHook initialized")
    }

    /**
     * Hook Geocoder API
     */
    private fun hookGeocoder(classLoader: ClassLoader) {
        kotlin.runCatching {
            val geocoderClass = XposedHelpers.findClass("android.location.Geocoder", classLoader)

            // Hook getFromLocation (反向地理编码)
            XposedBridge.hookAllMethods(geocoderClass, "getFromLocation", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    // 如果禁用，返回空列表
                    if (FakeLoc.disableGetFromLocation) {
                        param.result = emptyList<Address>()
                        return
                    }

                    // 检查输入坐标是否为虚拟坐标
                    if (param.args.size >= 2) {
                        val latitude = param.args[0] as? Double
                        val longitude = param.args[1] as? Double

                        if (latitude != null && longitude != null) {
                            val (fakeLat, fakeLon) = FakeLoc.getCoordinates()

                            // 如果查询的是虚拟坐标，生成虚拟地址
                            if (isNearby(latitude, longitude, fakeLat, fakeLon)) {
                                param.result = generateFakeAddresses(latitude, longitude, param.args)
                                return
                            }
                        }
                    }
                }
            })

            // Hook getFromLocationName (正向地理编码)
            XposedBridge.hookAllMethods(geocoderClass, "getFromLocationName", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return
                    if (!BinderUtils.isTargetApp()) return

                    // 正向编码：根据输入的地址名称，修改返回的坐标为虚拟坐标
                    @Suppress("UNCHECKED_CAST")
                    val addresses = param.result as? List<Address>
                    if (addresses != null && addresses.isNotEmpty()) {
                        param.result = modifyAddressCoordinates(addresses)
                    }
                }
            })

            Logger.info("Hooked Geocoder")
        }.onFailure {
            Logger.error("Failed to hook Geocoder", it)
        }
    }

    /**
     * 判断两个坐标是否接近（误差< 0.00001度 约1.1m）
     *
     * 注：原阈值0.001度约110m太大，容易导致非虚拟位置被错误匹配
     */
    private fun isNearby(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean {
        return kotlin.math.abs(lat1 - lat2) < 0.00001 && kotlin.math.abs(lon1 - lon2) < 0.00001
    }

    /**
     * 生成虚拟地址列表
     */
    private fun generateFakeAddresses(latitude: Double, longitude: Double, args: Array<Any?>): List<Address> {
        val maxResults = if (args.size >= 3) args[2] as? Int ?: 1 else 1

        val addresses = mutableListOf<Address>()

        for (i in 0 until maxResults) {
            val address = Address(Locale.getDefault())

            // 设置坐标
            address.latitude = latitude
            address.longitude = longitude

            // 生成虚拟地址信息
            when (i) {
                0 -> {
                    // 主要地址
                    address.countryCode = "CN"
                    address.countryName = "中国"
                    address.adminArea = getRandomProvince()
                    address.locality = getRandomCity()
                    address.subLocality = getRandomDistrict()
                    address.thoroughfare = getRandomStreet()
                    address.featureName = "${kotlin.random.Random.nextInt(1, 999)}号"

                    // 拼接完整地址
                    address.setAddressLine(
                        0,
                        "${address.countryName}${address.adminArea}${address.locality}${address.subLocality}${address.thoroughfare}${address.featureName}"
                    )
                }
                else -> {
                    // 备选地址（POI）
                    address.countryCode = "CN"
                    address.countryName = "中国"
                    address.featureName = getRandomPOI()
                    address.setAddressLine(0, address.featureName ?: "")
                }
            }

            addresses.add(address)
        }

        return addresses
    }

    /**
     * 修改地址列表中的坐标为虚拟坐标
     */
    private fun modifyAddressCoordinates(addresses: List<Address>): List<Address> {
        val (fakeLat, fakeLon) = FakeLoc.getCoordinates()

        return addresses.map { originalAddress ->
            val modifiedAddress = Address(originalAddress.locale)

            // 复制所有原始信息
            for (i in 0 until originalAddress.maxAddressLineIndex + 1) {
                modifiedAddress.setAddressLine(i, originalAddress.getAddressLine(i))
            }

            modifiedAddress.featureName = originalAddress.featureName
            modifiedAddress.adminArea = originalAddress.adminArea
            modifiedAddress.subAdminArea = originalAddress.subAdminArea
            modifiedAddress.locality = originalAddress.locality
            modifiedAddress.subLocality = originalAddress.subLocality
            modifiedAddress.thoroughfare = originalAddress.thoroughfare
            modifiedAddress.subThoroughfare = originalAddress.subThoroughfare
            modifiedAddress.premises = originalAddress.premises
            modifiedAddress.postalCode = originalAddress.postalCode
            modifiedAddress.countryCode = originalAddress.countryCode
            modifiedAddress.countryName = originalAddress.countryName
            modifiedAddress.phone = originalAddress.phone
            modifiedAddress.url = originalAddress.url

            // 替换坐标为虚拟坐标
            modifiedAddress.latitude = fakeLat
            modifiedAddress.longitude = fakeLon

            modifiedAddress
        }
    }

    // 虚拟地址数据库

    private val provinces = arrayOf(
        "北京市", "上海市", "广东省", "浙江省", "江苏省",
        "四川省", "湖北省", "湖南省", "河南省", "山东省"
    )

    private val cities = arrayOf(
        "市区", "中心城区", "新区", "开发区", "高新区"
    )

    private val districts = arrayOf(
        "朝阳区", "海淀区", "西城区", "浦东新区", "黄浦区",
        "天河区", "越秀区", "江汉区", "武昌区", "锦江区"
    )

    private val streets = arrayOf(
        "中山路", "人民路", "建设路", "解放路", "和平路",
        "胜利路", "文化路", "新华路", "光明路", "复兴路",
        "振兴路", "幸福路", "团结路", "友谊路", "民主路"
    )

    private val pois = arrayOf(
        "购物中心", "商业广场", "科技园", "写字楼",
        "公园", "体育馆", "图书馆", "博物馆",
        "地铁站", "公交站", "火车站", "机场"
    )

    private fun getRandomProvince(): String = provinces.random()
    private fun getRandomCity(): String = cities.random()
    private fun getRandomDistrict(): String = districts.random()
    private fun getRandomStreet(): String = streets.random()
    private fun getRandomPOI(): String = pois.random()
}
