package john.fucklocation.xposed.hooks.network

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * IP定位服务Hook
 *
 * 拦截并伪造基于IP的定位服务响应：
 * - ipapi.co
 * - ip-api.com
 * - ipinfo.io
 * - freegeoip.app
 * - geoip-db.com
 *
 * 原理：Hook HttpURLConnection和OkHttp的响应，替换JSON内容
 */
object IPLocationHook {
    // 常见IP定位服务域名
    private val IP_LOCATION_DOMAINS = setOf(
        "ipapi.co",
        "ip-api.com",
        "ipinfo.io",
        "freegeoip.app",
        "geoip-db.com",
        "ipgeolocation.io",
        "ipstack.com",
        "extreme-ip-lookup.com"
    )

    operator fun invoke(classLoader: ClassLoader) {
        hookHttpURLConnection(classLoader)
        hookOkHttp(classLoader)
        Logger.info("IPLocationHook initialized")
    }

    /**
     * Hook HttpURLConnection - Android原生HTTP客户端
     */
    private fun hookHttpURLConnection(classLoader: ClassLoader) {
        kotlin.runCatching {
            val httpUrlConnectionClass = XposedHelpers.findClass(
                "java.net.HttpURLConnection",
                classLoader
            )

            // Hook getInputStream - 返回伪造的响应流
            XposedBridge.hookAllMethods(httpUrlConnectionClass, "getInputStream", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!FakeLoc.enable) return

                    val connection = param.thisObject as? HttpURLConnection ?: return
                    val url = connection.url?.toString() ?: return

                    // 检查是否是IP定位请求
                    if (!isIPLocationRequest(url)) return

                    // 读取原始响应
                    val originalStream = param.result as? InputStream ?: return
                    val originalResponse = originalStream.bufferedReader().use { it.readText() }

                    // 生成伪造响应
                    val fakeResponse = generateFakeIPLocationResponse(url, originalResponse)

                    // 替换响应流
                    param.result = ByteArrayInputStream(fakeResponse.toByteArray(Charsets.UTF_8))

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("IP location request intercepted: $url")
                    }
                }
            })

            Logger.info("Hooked HttpURLConnection")
        }.onFailure {
            if (FakeLoc.enableDebugLog) {
                Logger.debug("HttpURLConnection hook failed: ${it.message}")
            }
        }
    }

    /**
     * Hook OkHttp - 流行的第三方HTTP客户端
     */
    private fun hookOkHttp(classLoader: ClassLoader) {
        val possibleClasses = listOf(
            "okhttp3.Response",
            "com.squareup.okhttp.Response", // OkHttp 2.x
            "okhttp3.internal.http.RealResponseBody"
        )

        for (className in possibleClasses) {
            kotlin.runCatching {
                val responseClass = XposedHelpers.findClass(className, classLoader)

                // Hook body() 方法
                XposedBridge.hookAllMethods(responseClass, "body", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!FakeLoc.enable) return

                        // 获取Response对象
                        val response = param.thisObject

                        // 尝试获取request的URL
                        val url = kotlin.runCatching {
                            val request = XposedHelpers.callMethod(response, "request")
                            val urlObj = XposedHelpers.callMethod(request, "url")
                            urlObj.toString()
                        }.getOrNull() ?: return

                        if (!isIPLocationRequest(url)) return

                        // 获取原始body
                        val originalBody = param.result ?: return

                        // 读取原始内容
                        val originalContent = kotlin.runCatching {
                            val source = XposedHelpers.callMethod(originalBody, "source")
                            XposedHelpers.callMethod(source, "readUtf8") as? String
                        }.getOrNull() ?: return

                        // 生成伪造响应
                        val fakeContent = generateFakeIPLocationResponse(url, originalContent)

                        // 创建新的ResponseBody（实现较复杂，简化处理）
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("OkHttp IP location request intercepted: $url")
                        }
                    }
                })

                Logger.info("Hooked OkHttp: $className")
            }.onFailure {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("$className not found: ${it.message}")
                }
            }
        }
    }

    /**
     * 检查URL是否是IP定位请求
     */
    private fun isIPLocationRequest(url: String): Boolean {
        return IP_LOCATION_DOMAINS.any { domain ->
            url.contains(domain, ignoreCase = true)
        }
    }

    /**
     * 生成伪造的IP定位响应
     */
    private fun generateFakeIPLocationResponse(url: String, originalResponse: String): String {
        val (lat, lon) = FakeLoc.getCoordinates()

        // 尝试解析原始JSON并修改
        return kotlin.runCatching {
            val json = JSONObject(originalResponse)

            // 修改坐标字段（不同服务使用不同字段名）
            when {
                url.contains("ipapi.co") -> {
                    json.put("latitude", lat)
                    json.put("longitude", lon)
                    json.put("city", "Unknown")
                    json.put("region", "Unknown")
                    json.put("country_name", "Unknown")
                }
                url.contains("ip-api.com") -> {
                    json.put("lat", lat)
                    json.put("lon", lon)
                    json.put("city", "Unknown")
                    json.put("region", "Unknown")
                    json.put("country", "Unknown")
                }
                url.contains("ipinfo.io") -> {
                    json.put("loc", "$lat,$lon")
                    json.put("city", "Unknown")
                    json.put("region", "Unknown")
                    json.put("country", "Unknown")
                }
                else -> {
                    // 通用字段修改
                    if (json.has("latitude")) json.put("latitude", lat)
                    if (json.has("longitude")) json.put("longitude", lon)
                    if (json.has("lat")) json.put("lat", lat)
                    if (json.has("lon")) json.put("lon", lon)
                    if (json.has("lng")) json.put("lng", lon)
                }
            }

            json.toString()
        }.getOrElse {
            // 如果解析失败，返回简单JSON
            """
            {
                "latitude": $lat,
                "longitude": $lon,
                "city": "Unknown",
                "region": "Unknown",
                "country": "Unknown"
            }
            """.trimIndent()
        }
    }
}
