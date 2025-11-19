package john.fucklocation.xposed.hooks.telephony

import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.telephony.CellIdentity
import android.telephony.CellIdentityCdma
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.NeighboringCellInfo
import android.telephony.SignalStrength
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import john.fucklocation.xposed.utils.BinderUtils
import john.fucklocation.xposed.utils.FakeLoc
import john.fucklocation.xposed.utils.Logger
import john.fucklocation.xposed.utils.afterHook
import john.fucklocation.xposed.utils.beforeHook
import john.fucklocation.xposed.utils.hookAllMethods
import john.fucklocation.xposed.utils.hookBefore
import john.fucklocation.xposed.utils.hookMethodBefore
import john.fucklocation.xposed.utils.onceHookDoNothingMethod
import john.fucklocation.xposed.utils.onceHookMethodBefore
import java.lang.reflect.Modifier


object TelephonyHook: BaseTelephonyHook() {
    operator fun invoke(classLoader: ClassLoader) {
        if(!initDivineService("TelephonyHook")) {
            Logger.error("Failed to init mock service in TelephonyHook")
            return
        }

//        kotlin.runCatching {
//            val cCellIdentityCdma =
//                XposedHelpers.findClass("android.telephony.CellIdentityCdma", classLoader)
//            val hookCdma = object: XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam?) {
//                    if (param == null) return
//
//                    if (!FakeLocationConfig.enable) return
//
//                    param.args[3] = Int.MAX_VALUE
//                    param.args[4] = Int.MAX_VALUE
//                }
//            }
//            //                                                             nid             sid                 bid            lon            lat                alphal              alphas
//            XposedHelpers.findAndHookConstructor(
//                cCellIdentityCdma,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                Int::class.java,
//                String::class.java,
//                String::class.java,
//                hookCdma
//            )
//        }.onFailure {
//            XposedBridge.log("[Portal] Hook CellIdentityCdma failed")
//        }

//        kotlin.runCatching {
//            val cCellIdentityGsm = XposedHelpers.findClass("android.telephony.CellIdentityGsm", classLoader)
//
//        }.onFailure {
//            XposedBridge.log("[Portal] Hook CellIdentityGsm failed")
//        }

//        XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader)?.let {
//            XposedBridge.hookAllMethods(it, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//            XposedBridge.hookAllMethods(it, "getCellLocation", hookGetCellLocation)
//        }

//        kotlin.runCatching {
//            XposedHelpers.findClass("com.android.internal.telephony.ITelephony\$Stub", classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                if (it.name == "onTransact") {
//                    hookOnTransactForServiceInstance(it)
//                    return@forEach
//                }
//            }
//        }.onFailure {
//            XposedBridge.log("[Portal] ITelephony.Stub not found: ${it.stackTraceToString()}")
//        }

        if (!FakeLoc.needDowngradeToCdma) return

        val cPhoneInterfaceManager = XposedHelpers.findClassIfExists("com.android.phone.PhoneInterfaceManager", classLoader)
            ?: return

        val hookGetPhoneTyp = beforeHook {
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getActivePhoneType: injected!")
                }

                result = 2
            }
        }

        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneType", hookGetPhoneTyp).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getActivePhoneType failed")
        }
        if(cPhoneInterfaceManager.hookAllMethods("getActivePhoneTypeForSlot", hookGetPhoneTyp).isEmpty()) {
            Logger.warn("Hook PhoneInterfaceManager.getActivePhoneTypeForSlot failed")
        }

        cPhoneInterfaceManager.declaredMethods.find { it.name == "getAllCellInfo" }?.let {
            val hookGetAllCellInfo = afterHook {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getAllCellInfo: injected! caller = ${BinderUtils.getCallerUid()}")
                }

                if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                    val cResult = arrayListOf<CellInfo>()
                    val cellTowerInfo = FakeLoc.generateCellTowerInfo()

                    // 尝试创建 5G NR CellInfo (Android 10+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        kotlin.runCatching {
                            val cCellInfoNr = Class.forName("android.telephony.CellInfoNr")
                            val cCellIdentityNr = Class.forName("android.telephony.CellIdentityNr")
                            val cCellSignalStrengthNr = Class.forName("android.telephony.CellSignalStrengthNr")

                            // 创建 CellIdentityNr
                            val nci = kotlin.random.Random.nextInt(0, 16777215) // NR Cell Identity
                            val pci = kotlin.random.Random.nextInt(0, 1007) // Physical Cell Id
                            val tac = cellTowerInfo.lac // 复用 LAC 作为 TAC
                            val nrarfcn = kotlin.random.Random.nextInt(0, 3279165) // NR-ARFCN

                            val cellIdentityNr = cCellIdentityNr.declaredConstructors.firstOrNull {
                                it.parameterTypes.isNotEmpty()
                            }?.let { constructor ->
                                constructor.isAccessible = true
                                // 尝试不同的构造函数签名
                                when (constructor.parameterTypes.size) {
                                    in 5..7 -> constructor.newInstance(
                                        pci, tac, nrarfcn, *Array(constructor.parameterTypes.size - 3) { 0 }
                                    )
                                    else -> null
                                }
                            }

                            // 创建 CellSignalStrengthNr
                            val ssRsrp = -85 + kotlin.random.Random.nextInt(-10, 10)
                            val ssRsrq = -10 + kotlin.random.Random.nextInt(-3, 3)
                            val ssSinr = 15 + kotlin.random.Random.nextInt(-5, 5)

                            val signalStrengthNr = cCellSignalStrengthNr.declaredConstructors.firstOrNull()?.let { constructor ->
                                constructor.isAccessible = true
                                constructor.newInstance()
                            }

                            if (cellIdentityNr != null && signalStrengthNr != null) {
                                // 创建 CellInfoNr
                                val cellInfoNr = cCellInfoNr.declaredConstructors.firstOrNull {
                                    it.parameterTypes.size >= 2
                                }?.let { constructor ->
                                    constructor.isAccessible = true
                                    constructor.newInstance(
                                        *Array(constructor.parameterTypes.size) { idx ->
                                            when (idx) {
                                                0 -> 0 // connectionStatus
                                                1 -> true // registered
                                                2 -> System.nanoTime() // timestamp
                                                else -> null
                                            }
                                        }.filterNotNull().toTypedArray()
                                    )
                                }

                                if (cellInfoNr != null) {
                                    cResult.add(cellInfoNr as CellInfo)
                                    Logger.debug("Created CellInfoNr for 5G support")
                                }
                            }
                        }.onFailure {
                            if (FakeLoc.enableDebugLog) {
                                Logger.debug("5G CellInfoNr creation failed, falling back to CDMA: ${it.message}")
                            }
                        }
                    }

                    // 如果没有成功创建 5G 或者需要 fallback，创建 CDMA
                    if (cResult.isEmpty()) {
                        val cellInfo = kotlin.runCatching {
                            CellInfoCdma::class.java.getConstructor().newInstance().also {
                                XposedHelpers.callMethod(it, "setRegistered", true)
                                XposedHelpers.callMethod(it, "setTimeStamp", System.nanoTime())
                                XposedHelpers.callMethod(it, "setCellConnectionStatus", 0)
                            }
                        }.getOrElse {
                            CellInfoCdma::class.java.getConstructor(
                                Int::class.java,
                                Boolean::class.java,
                                Long::class.java,
                                CellIdentityCdma::class.java,
                                CellSignalStrengthCdma::class.java
                            ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
                        }
                        cResult.add(cellInfo)
                    }

                    result = cResult
                }
            }
            if (XposedBridge.hookMethod(it, hookGetAllCellInfo) == null) {
                Logger.error("Hook PhoneInterfaceManager.getAllCellInfo failed")
            }
        }

        if(XposedBridge.hookAllMethods(cPhoneInterfaceManager, "getCellLocation", afterHook {
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@afterHook
                }
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("${method.name}: injected!")
                }

                val cellInfo = FakeLoc.generateCellTowerInfo()

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || result.javaClass.name == "android.os.Bundle") {
                    result = Bundle().apply {
                        putInt("cid", cellInfo.cid)
                        putInt("lac", cellInfo.lac)
                        putInt("psc", cellInfo.psc)
                        putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                        putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                        putBoolean("empty", false)
                        putBoolean("emptyParcel", false)
                        putInt("mFlags", 1536)
                        putBoolean("parcelled", false)
                        putInt("baseStationId", cellInfo.bid)
                        putInt("systemId", cellInfo.sid)
                        putInt("networkId", cellInfo.nid)
                        putInt("size", 0)
                    }
                } else {
                    // int nid, int sid, int bid, int lon, int lat,
                    //            @Nullable String alphal, @Nullable String alphas
                    result = CellIdentityCdma::class.java.getConstructor(
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        Int::class.java,
                        String::class.java,
                        String::class.java
                    ).newInstance(
                        cellInfo.nid,
                        cellInfo.sid,
                        cellInfo.bid,
                        (FakeLoc.latitude * 14400.0).toInt(),
                        (FakeLoc.longitude * 14400.0).toInt(),
                        null, null
                    )
                }
            }).isEmpty()) {
            Logger.error("Hook PhoneInterfaceManager.getCellLocation failed")
        }

        beforeHook {
            if (FakeLoc.enable && !BinderUtils.isSystemAppsCall()) {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("getDataNetworkType: injected!")
                }
                result = 4
            }
        }.let {
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkType", it)
            cPhoneInterfaceManager.hookAllMethods("getDataNetworkTypeForSubscriber", it)
            cPhoneInterfaceManager.hookAllMethods("getNetworkTypeForSubscriber", it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (cPhoneInterfaceManager.hookAllMethods("getNeighboringCellInfo", beforeHook {
                if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                    return@beforeHook
                }

                result = kotlin.runCatching {
                    val cellInfo = FakeLoc.generateCellTowerInfo()
                    val nCellInfo = NeighboringCellInfo::class.java.getConstructor().newInstance()
                    // 信号强度随机化 (-45 到 -65 dBm)，避免固定值被检测
                    val rssi = -55 + kotlin.random.Random.nextInt(-10, 10)
                    XposedHelpers.setIntField(nCellInfo, "mRssi", rssi)
                    XposedHelpers.setIntField(nCellInfo, "mCid", cellInfo.cid)
                    XposedHelpers.setIntField(nCellInfo, "mLac", cellInfo.lac)
                    XposedHelpers.setIntField(nCellInfo, "mPsc", cellInfo.psc)
                    XposedHelpers.setIntField(nCellInfo, "mNetworkType", 3)
                    listOf(nCellInfo)
                }.getOrElse {
                    arrayListOf()
                }
            }).isEmpty()) {
                Logger.error("Hook PhoneInterfaceManager.getNeighboringCellInfo failed")
            }
        }

        val cTelephonyRegistry = XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)
        if (cTelephonyRegistry == null) {
            Logger.error("TelephonyRegistry not found")
        } else {
            hookTelephonyRegistry(cTelephonyRegistry)
        }

    }

    fun hookTelephonyRegistry(cTelephonyRegistry: Class<*>) {
        cTelephonyRegistry.declaredMethods.filter { (it.name == "listen" || it.name == "listenWithEventList") && !Modifier.isAbstract(it.modifiers) }
            .map {
                it to it.parameterTypes.indexOfFirst { typ -> typ.simpleName == "IPhoneStateListener" }
            }.forEach {
                val (m, idx) = it
                if (idx == -1) {
                    Logger.error("IPhoneStateListener not found")
                    return@forEach
                }
                m.hookBefore {
                    if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                        return@hookBefore
                    }

                    val listener = args[idx] as Any
                    val hasHookOnCellLocationChanged = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", CellIdentity::class.java) {
                            if (FakeLoc.enable) {
                                result = CellIdentityCdma::class.java.getConstructor(
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    Int::class.java,
                                    String::class.java,
                                    String::class.java
                                ).newInstance(
                                    FakeLoc.generateCellTowerInfo().nid,
                                    FakeLoc.generateCellTowerInfo().sid,
                                    FakeLoc.generateCellTowerInfo().bid,
                                    (FakeLoc.latitude * 14400.0).toInt(),
                                    (FakeLoc.longitude * 14400.0).toInt(),
                                    null, null
                                )
                            }
                        }
                    } else {
                        listener.javaClass.onceHookMethodBefore("onCellLocationChanged", Bundle::class.java) {
                            if (FakeLoc.enable) {
                                val cellInfo = FakeLoc.generateCellTowerInfo()
                                result = Bundle().apply {
                                    putInt("cid", cellInfo.cid)
                                    putInt("lac", cellInfo.lac)
                                    putInt("psc", cellInfo.psc)
                                    putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                                    putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                                    putBoolean("empty", false)
                                    putBoolean("emptyParcel", false)
                                    putInt("mFlags", 1536)
                                    putBoolean("parcelled", false)
                                    putInt("baseStationId", cellInfo.bid)
                                    putInt("systemId", cellInfo.sid)
                                    putInt("networkId", cellInfo.nid)
                                    putInt("size", 0)
                                }
                            }
                        }
                    } != null
                    if (!hasHookOnCellLocationChanged) {
                        Logger.error("Hook onCellLocationChanged failed")
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthChanged", Int::class.java) {
                        FakeLoc.enable
                    }
                    listener.javaClass.onceHookDoNothingMethod("onSignalStrengthsChanged", SignalStrength::class.java) { FakeLoc.enable }
                }
            }

        cTelephonyRegistry.hookMethodBefore("notifyCellInfo", List::class.java) {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfo: injected!")
            }

            val cellInfos = arrayListOf<CellInfo>()
            val cellInfo = kotlin.runCatching {
                CellInfoCdma::class.java.getConstructor().newInstance().also {
                    XposedHelpers.callMethod(it, "setRegistered", true)
                    XposedHelpers.callMethod(it, "setTimeStamp", System.nanoTime())
                    XposedHelpers.callMethod(it, "setCellConnectionStatus", 0)
                }
            }.getOrElse {
                CellInfoCdma::class.java.getConstructor(
                    Int::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    CellIdentityCdma::class.java,
                    CellSignalStrengthCdma::class.java
                ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
            }
            cellInfos.add(cellInfo)

            args[0] = cellInfos
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellInfoForSubscriber", Int::class.java, List::class.java) {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellInfoForSubscriber: injected!")
            }

            val cellInfos = arrayListOf<CellInfo>()
            val cellInfo = kotlin.runCatching {
                CellInfoCdma::class.java.getConstructor().newInstance().also {
                    XposedHelpers.callMethod(it, "setRegistered", true)
                    XposedHelpers.callMethod(it, "setTimeStamp", System.nanoTime())
                    XposedHelpers.callMethod(it, "setCellConnectionStatus", 0)
                }
            }.getOrElse {
                CellInfoCdma::class.java.getConstructor(
                    Int::class.java,
                    Boolean::class.java,
                    Long::class.java,
                    CellIdentityCdma::class.java,
                    CellSignalStrengthCdma::class.java
                ).newInstance(0, true, System.nanoTime(), CellIdentityCdma::class.java.newInstance(), CellSignalStrengthCdma::class.java.newInstance())
            }
            cellInfos.add(cellInfo)

            args[1] = cellInfos
        }

        cTelephonyRegistry.hookMethodBefore("notifyCellLocation", Bundle::class.java) {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocation: injected!")
            }

            val cellInfo = FakeLoc.generateCellTowerInfo()
            args[0] = Bundle().apply {
                putInt("cid", cellInfo.cid)
                putInt("lac", cellInfo.lac)
                putInt("psc", cellInfo.psc)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", cellInfo.bid)
                putInt("systemId", cellInfo.sid)
                putInt("networkId", cellInfo.nid)
                putInt("size", 0)
            }
        }
        cTelephonyRegistry.hookMethodBefore("notifyCellLocationForSubscriber", Int::class.java, Bundle::class.java) {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@hookMethodBefore
            }

            if (FakeLoc.enableDebugLog) {
                Logger.debug("notifyCellLocationForSubscriber: injected!")
            }

            val cellInfo = FakeLoc.generateCellTowerInfo()
            args[1] = Bundle().apply {
                putInt("cid", cellInfo.cid)
                putInt("lac", cellInfo.lac)
                putInt("psc", cellInfo.psc)
                putInt("baseStationLatitude", (FakeLoc.latitude * 14400.0).toInt())
                putInt("baseStationLongitude", (FakeLoc.longitude * 14400.0).toInt())
                putBoolean("empty", false)
                putBoolean("emptyParcel", false)
                putInt("mFlags", 1536)
                putBoolean("parcelled", false)
                putInt("baseStationId", cellInfo.bid)
                putInt("systemId", cellInfo.sid)
                putInt("networkId", cellInfo.nid)
                putInt("size", 0)
            }
        }
    }

    @Suppress("LocalVariableName")
    fun hookSubOnTransact(classLoader: ClassLoader) {
        val cISub = XposedHelpers.findClassIfExists("com.android.internal.telephony.ISub\$Stub", classLoader)
        if (cISub == null) {
            Logger.error("ISub.Stub not found")
            return
        }

        val subClassName = "com.android.internal.telephony.ISub"
        val TRANSACTION_getActiveSubInfoCount = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCount") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubInfoCountMax = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubInfoCountMax") }.getOrDefault(-1)
        val TRANSACTION_getActiveSubscriptionInfoList = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getActiveSubscriptionInfoList") }.getOrDefault(-1)
        val TRANSACTION_getPhoneId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getPhoneId") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIndex = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIndex") }.getOrDefault(-1)
        val TRANSACTION_isActiveSubId = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_isActiveSubId") }.getOrDefault(-1)
        val TRANSACTION_getNetworkCountryIsoForPhone = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getNetworkCountryIsoForPhone") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSubscriber = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSubscriber") }.getOrDefault(-1)
        val TRANSACTION_getSimStateForSlotIdx = kotlin.runCatching { XposedHelpers.getStaticIntField(cISub, "TRANSACTION_getSimStateForSlotIdx") }.getOrDefault(-1)

        val hookOnTransact = beforeHook {
            if (!FakeLoc.enable || BinderUtils.isSystemAppsCall()) {
                return@beforeHook
            }

            val code = args[0] as Int
            val data = args[1] as Parcel
            val reply = args[2] as Parcel
            val flags = args[3] as Int

            if (code == -1) return@beforeHook

            when (code) {
                TRANSACTION_isActiveSubId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        reply.writeBoolean(true)
                    } else {
                        reply.writeInt(1)
                    }
                    result = true
                }
                TRANSACTION_getSimStateForSlotIndex -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSubscriber -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
                TRANSACTION_getSimStateForSlotIdx -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(5)
                    result = true
                }
//                TRANSACTION_getActiveSubInfoCount -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
//                TRANSACTION_getActiveSubscriptionInfoList -> {
//                    data.enforceInterface(subClassName)
//                    data.readString()
//                    reply.writeNoException()
//                    reply.writeTypedList(arrayListOf())
//                    result = true
//                }
//                TRANSACTION_getActiveSubInfoCountMax -> {
//                    data.enforceInterface(subClassName)
//                    reply.writeNoException()
//                    reply.writeInt(1)
//                    result = true
//                }
                TRANSACTION_getNetworkCountryIsoForPhone -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeString("CHN")
                    result = true
                }
                TRANSACTION_getPhoneId -> {
                    data.enforceInterface(subClassName)
                    data.readInt()
                    reply.writeNoException()
                    reply.writeInt(0)
                    result = true
                }
            }
        }
        if(cISub.hookAllMethods("onTransact", hookOnTransact).isEmpty()) {
            Logger.error("Hook ISub.Stub.onTransact failed")
        }
    }

//    private fun hookOnTransactForServiceInstance(m: Method) {
//        var hook: XC_MethodHook.Unhook? = null
//        hook = XposedBridge.hookMethod(m, object : XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//                val thisObject = param.thisObject
//
//                if (hook == null || thisObject == null) return
//                onFetchServiceInstance(thisObject)
//                hook?.unhook()
//                hook = null
//            }
//        })
//    }

//    private fun onFetchServiceInstance(thisObject: Any) {
//        val cITelephony = thisObject.javaClass
//
//        println("[Portal] found " + cITelephony.declaredMethods.mapNotNull {
//            if (it.returnType.javaClass.name.contains("CellLocation")) {
//                if (FakeLocationConfig.DEBUG) {
//                    XposedBridge.log("[Portal] hook method: $it")
//                }
//                XposedBridge.hookMethod(it, hookGetCellLocation)
//            } else null
//        }.size + " methods(CellLocation) to hook in ITelephony\$Stub")
//
//        XposedBridge.hookAllMethods(cITelephony, "getNeighboringCellInfo", hookGetNeighboringCellInfoList)
//    }
}