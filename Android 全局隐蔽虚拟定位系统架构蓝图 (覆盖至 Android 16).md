# Android 全局隐蔽虚拟定位系统架构蓝图 (覆盖至 Android 16)





## 执行摘要



**目标:** 本报告旨在提供一个全面的技术架构蓝图，用于在 Android 平台（覆盖至最新的 Android 16）上实现一个全局性、高隐蔽性的虚拟定位系统。

**核心方法论:** 本架构明确拒绝了易于检测的用户空间“模拟位置”API 1。推荐的架构基于以下技术的协同作用：1) **Root 权限 (Magisk)** 3 作为基础；2) **Zygote 注入 (Zygisk)** 4 作为代码注入机制；3) **框架层 ART Hooking (LSPosed)** 5 作为核心修改手段。

**核心目标:** 架构的核心是直接 Hook `system_server` 进程。`system_server` 是 Android 框架的核心，通过在这一单点进行干预，本系统能够拦截并伪造所有位置消耗者的数据，包括 AOSP 的 `LocationManager` 6 和 GMS 的 `FusedLocationProvider` (FLP) 7。

**模拟范围:** 为彻底规避高级别的异常检测 8，本系统必须伪造一个“全景式”的虚拟环境。这不仅包括伪造最终的 `Location` 对象，还必须同步伪造构成该位置的所有原始数据：

1. **GNSS (GPS) `Location` 对象：** 核心的经纬度海拔数据。
2. **原始 `GnssStatus`：** 卫星“3D 固定”数据，包括卫星数量、信号强度、星历和年历状态 10。
3. **网络 `List<CellInfo>`：** 蜂窝网络基站数据，包括邻近基站 11。
4. **Wi-Fi `List<ScanResult>`：** Wi-Fi 接入点数据 14。
5. **相关的 `SensorEvent`：** 协同伪造的运动传感器数据（加速度计、陀螺仪），以匹配虚拟的移动状态 15。

**关键特性:** 本架构支持通过 `Binder.getCallingUid()` 18 实现精细化的“按应用过滤”模式。它采用一个高速、隐蔽的 Binder 进程间通信（IPC）机制进行控制 20，并采用规避检测的命名方案 22。

**Android 16+ 考量:** 随着 Android 16 引入基于 802.11az 的安全 Wi-Fi 测距 23，系统必须扩展 Hook 范围，以覆盖新的 `RangingManager` API 25，从而消除这一最新的高完整性位置数据源所带来的检测向量。

------



## 1.0 核心 Hook 基础设施：架构策略



本节深入分析在 Android 系统框架内实现特权代码执行的最优方法，并直接回应关于“LSPosed 还是仅 Root”的核心问题。



### 1.1 解构：Root、注入与 Hooking



这三个概念是递进关系，共同构成了本架构的基础：

- **Systemless Root (Magisk):** 这是基础要求。Magisk 提供了 `su`（超级用户）权限，且其“systemless”（无系统分区修改）的特性使其与 OTA 升级兼容，并成为现代 Android 的 Root 黄金标准 3。Root 权限是实现后续所有操作的*前提*，而非*方法*本身。
- **注入 (Zygisk):** 这是代码的*投递机制*。Zygisk 是 Magisk 的一个核心功能，它允许模块将代码（动态库）直接注入到 Zygote 进程中 4。
  - **架构关键点 (因果关系):** Zygote 进程 27 是 Android 系统上所有 Java 应用程序进程的父进程，这其中*包括了* `system_server`（PID 1000，系统核心服务）。通过在 Zygote 启动时注入，我们的代码被加载到 `system_server` 的地址空间中，此时远早于 `LocationManagerService` 等关键服务被初始化。这提供了最稳定、最全面的注入时机，远胜于旧的 Riru 框架 28。
- **Hooking (LSPosed / LSPlant):** 这是*修改框架*。LSPosed 5 是一个 Zygisk 模块，它提供了一个稳定且强大的 API，用于实现“ART Hooking”（通过其核心库 LSPlant 30）。这使我们能够在运行时查找并替换内存中任意的 Java 方法。



### 1.2 Hooking 策略对比分析



基于上述基础，我们评估三种可能的系统级干预策略：



#### 1.2.1 方法一 (推荐)：LSPosed/Zygisk (Java/ART Hooking)



- **机制:** 利用 Zygisk 4 将 LSPosed 框架 5 加载到 `system_server` 进程中。随后，使用 LSPosed 提供的 Xposed API 31 来 Hook 目标 Java 方法（例如 `GnssManagerService.reportLocation`）。
- **优势:**
  - **正确的抽象层:** 我们的目标是操纵 Java 对象，如 `Location` 32、`GnssStatus` 10 和 `CellInfo` 33。Hook 处理这些对象的 Java 方法是最直接、最稳定且最易于维护的途径。
  - **稳定性:** LSPosed 是一个成熟的框架 34，它完美处理了 Android ART 运行时的复杂方法替换逻辑 5，开发者无需关心底层的JIT/AOT 编译差异。
- **劣势:** LSPosed 框架本身具有可检测性，安全敏感的应用会扫描其特征 35。这一问题将在 8.0 章节中通过反检测策略解决。



#### 1.2.2 方法二 (备选)：内核级 eBPF Hooking



- **机制:** eBPF 是一个内核内的虚拟机 38，Root 进程可以加载 eBPF 程序 39 来 Hook 内核函数、系统调用 (syscalls) 40 或跟踪点 (tracepoints) 41。在 Android 中，eBPF 已被用于网络流量监控 42 和安全研究（如 BPFroid 恶意软件检测）43。
- **优势:** 极其隐蔽。eBPF 在内核空间运行，对用户空间的检测方法完全免疫 43。
- **劣势:**
  - **错误的抽象层:** eBPF 看到的是内核数据结构（如 syscall 参数、网络数据包），它*无法*直接感知或操作 Java 对象。
  - **架构关键点 (连锁反应):** 若要使用 eBPF 伪造位置，必须拦截对硬件驱动（例如 `/dev/gnss0`）的 `read()` 或 `ioctl()` 系统调用。这意味着我们必须逆向工程特定硬件（如高通、联发科）的私有通信协议，并伪造*原始的 NMEA 数据流*或硬件特定的二进制结构，而非一个简单的 Java `Location` 对象。这在工程上是极其复杂、易碎且设备强相关的。eBPF 在此场景下是错误的工具。



#### 1.2.3 方法三 (备选)：原生注入与 BinderJacking



- **机制:** 使用 Root 权限的 `ptrace` 或其他注入技术，将一个原生的 `.so` 库注入正在运行的 `system_server` 进程。一旦进入，通过“BinderJacking” 20 技术，劫持 `ILocationManager` 的 Binder IPC（进程间通信）句柄 45。
- **优势:** 绕过了 Zygisk/LSPosed 框架，可能具有更小的检测特征。
- **劣势:** 该技术的原始实现者明确指出，这种方法“非常不稳定”，并且需要“跨 OEM 和跨 Android 版本”进行大量测试 20。LSPosed 完美地解决了这个稳定性问题。



### 1.3 结论与架构决策



用户的“LSPosed 还是仅 Root”的问题得到了解答：**两者都是必需的**。Root 是基础，LSPosed 是方法。

**决策:** 最佳架构是 **Magisk (Root) + Zygisk (注入) + LSPosed (Hooking 框架)**。这个技术栈稳定、可维护，并且精确地瞄准了正确的抽象层（Java/ART），以操纵实现用户需求所需的核心框架对象 5。

**表 1.1：系统 Hook 策略对比分析**

| **策略**               | **目标层级**       | **处理的数据类型**                   | **稳定性** | **实现复杂度**              | **隐蔽性 (对应用)**  |
| ---------------------- | ------------------ | ------------------------------------ | ---------- | --------------------------- | -------------------- |
| **LSPosed (Zygisk)**   | Java (ART 运行时)  | Java 对象 (`Location`, `GnssStatus`) | **高** 5   | 中 (需 Java/Xposed API)     | 低 (框架可被检测) 35 |
| **eBPF (内核)**        | 内核 (VFS/Syscall) | 原始二进制 (如 NMEA 流)              | 高 43      | **极高** (需硬件逆向)       | 非常高 43            |
| **原生 BinderJacking** | 原生 (Binder IPC)  | Binder Parcels (`Location` 对象)     | 低 20      | 高 (需 C++/JNI/Binder 逆向) | 中                   |



------



## 2.0 Android 定位栈解构 (AOSP & GMS)



要伪造位置，首先必须理解真实数据的流动路径。Android 定位栈是一个由 AOSP（Android 开放源代码项目）提供商和 GMS（Google 移动服务）客户端组成的联邦系统。



### 2.1 架构枢纽：`LocationManagerService` (LMS)



- `com.android.server.location.LocationManagerService` (LMS) 位于 `system_server` 进程中 6，是所有定位数据的中央多路复用器。
- 它管理着一个 `LocationProviderManager` 实例列表 47，每个实例对应一个 Provider（例如 `GPS_PROVIDER`、`NETWORK_PROVIDER`、`PASSIVE_PROVIDER`）6。
- LMS 负责仲裁来自应用程序的定位请求，并将来自 Provider 的数据路由到正确的监听器。



### 2.2 AOSP 数据提供商



- **`GnssLocationProvider`:**
  - 这就是 `GPS_PROVIDER` 6 的实现。
  - 其核心逻辑由 `com.android.server.location.gnss.GnssManagerService` 管理 47。
  - **数据边界 (JNI):** `GnssManagerService` 不直接与硬件通信。它通过 JNI (Java 原生接口) 52 从原生 HAL（硬件抽象层）接收数据。原生层代码（如 `GnssNative.cpp`）54 会向 Java 框架注册回调（例如 `location_cb`, `sv_status_cb`）56。这些回调函数是*真正的*原始 GPS 数据进入系统框架的入口点。
- **`NetworkLocationProvider` (NLP):**
  - 这就是 `NETWORK_PROVIDER` 6 的实现。
  - 在绝大多数设备上，这*不是* AOSP 的一部分，而是由 Google Play 服务提供的闭源实现 58。
  - **数据源分析:** NLP 的工作原理是扫描当前环境（通过 `WifiManager.getScanResults()` 获取 Wi-Fi 列表，通过 `TelephonyManager.getAllCellInfo()` 获取基站列表），然后将这些扫描数据发送到 Google 服务器，服务器再返回一个计算后的位置 49。



### 2.3 GMS `FusedLocationProvider` (FLP)



- 这是现代应用程序*主要*使用的 API：`com.google.android.gms.location.FusedLocationProviderClient` 7。
- 它通过“智能融合”GPS、Wi-Fi 和蜂窝网络信号 7，来提供最佳的功耗/精度平衡。



### 2.4 核心架构洞察：FLP 是 LMS 的下游客户端



- **关键发现:** 运行在 GMS 进程中的 `FusedLocationProviderClient` (FLP) 7 *不是*一个全新的、独立的定位技术栈。它是一个高级*客户端*，其数据最终来源于 AOSP 的 `LocationManagerService` (LMS)。
- **证据:** 在开发者社区的讨论 64 和相关分析 64 中明确证实了这一点：“**`LocationManager` 不会消失，因为它本身正在被 `FusedLocationProvider` 使用。**”
- **因果链分析:**
  1. 一个目标应用（例如地图或游戏）向 `FusedLocationProviderClient` 62 请求位置。
  2. GMS 进程 7 随后向 AOSP 的 `LocationManagerService` 64 请求原始数据。
  3. `LocationManagerService` 6 激活其内部的 Provider（`GnssLocationProvider`, `NetworkLocationProvider`）来收集数据。
  4. 我们在 3.0 章节中部署的 Hook，在 `system_server` 内部拦截了这些数据。
  5. `LocationManagerService` 将我们*伪造的* GPS、*伪造的*基站和*伪造的* Wi-Fi 数据传递给 GMS 进程。
  6. `FusedLocationProviderClient` 接收到这个*100% 伪造、但内部数据一致*的虚拟环境数据包。
  7. FLP "融合" 这些伪造数据，并最终将我们想要的虚拟位置交付给目标应用。
- **战略意义:** 这个架构异常坚固。我们无需 Hook 每一个单独的应用，甚至无需 Hook GMS 进程 7。通过 Hook `system_server` 6，我们为*所有*的下游消费者（无论是 AOSP 还是 GMS）“投毒”。这也直接回答了用户的疑问：我们*提供了一个完整的、模拟的融合定位*，而非屏蔽它，这是通过伪造其所有组成部分来实现的。

------



## 3.0 框架层 Hook 实现：拦截 `system_server`



本节详细说明在 `system_server` 进程内的精确 Hook 点，目标是 2.0 章节中确定的数据入口点。所有 Hook 都将使用 LSPosed 5 提供的 `XposedHelpers.findAndHookMethod()` API 实现。



### 3.1 Hook 点 1 & 2: GNSS 位置与卫星状态



- **目标类:** `com.android.server.location.gnss.GnssManagerService` 47。
- 目标方法 (通过 JNI 回调分析 54 确定):
  1. `reportLocation(Location location)`
  2. `reportGnssStatus(GnssStatus status)`
- **机制:**
  - 我们将对这两个方法使用 `beforeHookedMethod` (前置 Hook)。
  - 在 Hook 内部，我们首先检查 `callingUid`（详见 7.0 章节）。
  - 如果目标应用处于激活状态，我们调用 `param.setResult(null)` 来阻止原始（真实）方法的执行。
  - 然后，我们使用 4.0 章节中生成的伪造 `Location` 和 `GnssStatus` 对象，*独立地*调用这些方法，从而有效地替换来自硬件的数据流。
- **重要性:** 这拦截了 Java 框架中可用的*最原始*的 GNSS 数据。`reportGnssStatus` 对于伪造“3D 固定”状态 10 和信号强度至关重要，这是简单的 `reportLocation` Hook 所无法实现的。



### 3.2 Hook 点 3: 蜂窝基站环境



- **目标类:** `com.android.server.TelephonyRegistry` (或其 `TelephonyManager` 66 的服务侧实现)。
- **目标方法:** `getAllCellInfo()` 68。
- **机制:**
  - 使用 `afterHookedMethod` (后置 Hook)。
  - 检查 `callingUid`。
  - 如果目标应用激活，调用 `param.setResult(forgedCellInfoList)` 来替换返回结果。
- **重要性:** 这为 `NetworkLocationProvider` 49 和任何直接查询 `TelephonyManager` 的应用提供了伪造的基站环境 11。这可以防止位置/基站数据不匹配而导致的异常。



### 3.3 Hook 点 4: Wi-Fi 环境



- **目标类:** `com.android.server.wifi.WifiServiceImpl` (即 `WifiManager` 的服务侧实现)。
- **目标方法:** `getScanResults(String callingPackage, String callingFeatureId)` 14。
- **机制:**
  - 使用 `afterHookedMethod` (后置 Hook)。
  - 通过 `Binder.getCallingUid()` 检查调用者，而不是 `callingPackage` 参数（该参数可被伪造）。
  - 如果目标应用激活，调用 `param.setResult(forgedScanResultList)` 替换返回结果。
- **重要性:** 这为 `NetworkLocationProvider` 49 提供了伪造的 Wi-Fi 接入点列表，防止了位置/Wi-Fi 环境不匹配的异常 9。

**表 3.1：用于全面位置伪造的核心 Hook 点**

| **ID** | **服务**  | **目标类**              | **目标方法**                   | **Hook 类型** | **目的**                         |
| ------ | --------- | ----------------------- | ------------------------------ | ------------- | -------------------------------- |
| H1     | GNSS      | `...GnssManagerService` | `reportLocation(Location)`     | `before`      | 注入伪造的 `Location` 对象 51    |
| H2     | GNSS      | `...GnssManagerService` | `reportGnssStatus(GnssStatus)` | `before`      | 注入伪造的卫星状态 (3D 固定) 10  |
| H3     | Telephony | `...TelephonyRegistry`  | `getAllCellInfo()`             | `after`       | 注入伪造的 `List<CellInfo>` 68   |
| H4     | Wi-Fi     | `...WifiServiceImpl`    | `getScanResults(...)`          | `after`       | 注入伪造的 `List<ScanResult>` 14 |



------



## 4.0 数据伪造引擎：构建全面的位置对象



第 3.0 章节中的 Hook 需要一个“有效载荷”。本引擎负责基于期望的虚拟坐标（经度、纬度、海拔）生成现实且内部一致的数据对象。



### 4.1 伪造 `Location` 对象



- 这是最简单的对象：`new Location("gps")`。
- 必须调用的关键方法：
  - `setLatitude(double)`
  - `setLongitude(double)`
  - `setAltitude(double)`
  - `setAccuracy(float)` (例如 `3.0f`)
  - `setTime(long)` (设置为系统当前时间)
  - `setElapsedRealtimeNanos(long)` (设置为系统启动时间)
- **隐蔽性关键点:** 绝对*不能*调用 `setIsFromMockProvider(true)` 或添加 "mockLocation" bundle 60。由于我们是在 `GnssManagerService` 51 级别注入数据，系统会将其视为真实的硬件事件，`isMock()` 将默认为 `false`。这直接绕过了最常见的模拟位置检测 2。



### 4.2 伪造 `GnssStatus` (实现“3D 固定”)



- 这是击败高级检测的关键，也是用户请求（模拟信号强度 3D fix）的核心。
- 关键发现 10: `GnssStatus.java` 类 10 有一个**包私有 (package-private)** 的构造函数：`GnssStatus(int svCount, int svidWithFlags, float cn0s, float elevations, float azimuths)`。
- **架构优势:** 由于我们的 LSPosed Hook 5 运行在 `system_server` 进程*内部*，与 `GnssStatus` 类位于同一包（或至少有权访问），因此我们可以通过反射或直接调用来使用这个非公开的构造函数。
- **生成过程:**
  1. **生成星座:** 为虚拟坐标（经纬度和时间）创建 8-12 颗卫星。这需要一个简单的算法模型（或静态列表）来生成真实的 SVID（卫星 ID）、方位角 (azimuths) 和仰角 (elevations)。
  2. 填充数组 10:
     - `svCount`: 12
     - `mCn0DbHz`: (信号强度) `float` 数组。为所有 12 颗卫星填充高信号值（例如 30.0 到 50.0 dBHz）。
     - `mElevations`, `mAzimuths`: `float` 数组，填充算法生成的位置。
     - `mSvidWithFlags`: **关键数组**。对于全部 12 颗卫星，必须设置以下标志位：`GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA` (第 0 位，有星历)、`GNSS_SV_FLAGS_HAS_ALMANAC_DATA` (第 1 位，有年历)、`GNSS_SV_FLAGS_USED_IN_FIX` (第 2 位，用于定位解算)。
  3. **实例化:** 使用这些数组调用构造函数。
- **结果:** 任何注册了 `GnssStatus.Callback` 72 的应用，都将收到一个包含 12 颗高信号卫星的列表，所有卫星都用于 3D 定位，这与我们伪造的 `Location` 对象完美匹配。



### 4.3 伪造 `List<CellInfo>` (蜂窝基站)



- **要求:** 基站数据无法通过算法凭空捏造。这需要一个反向查找数据库（例如，一个本地化的 OpenCelliD 或 Wigle 9 的数据快照），该数据库能够将（经度, 纬度）映射到真实的基站数据。
- **生成过程:**
  1. 使用虚拟（经, 纬）查询本地数据库。
  2. 获取一个真实世界的基站列表（例如 1 个服务基站，3 个邻近基站）。
  3. 为每个基站构建相应的对象：`CellInfoGsm` 75 或 `CellInfoLte` 77。
  4. 填充 `CellIdentityGsm` 12：`setMcc()` (国家码), `setMnc()` (网络码), `setLac()` (位置区码), `setCid()` (基站 ID)。
  5. 填充 `CellIdentityLte` 13：`setMcc()`, `setMnc()`, `setTac()` (跟踪区码), `setCi()` (基站 ID)。
  6. 填充 `CellSignalStrength` 对象 80，设置真实的 Dbm（信号强度）值。
- **结果:** `NetworkLocationProvider` 49 和 `TelephonyManager` 66 将报告一个与虚拟位置完全匹配的基站环境。



### 4.4 伪造 `List<ScanResult>` (Wi-Fi 接入点)



- **要求:** 与基站信息相同。需要一个 Wi-Fi BSSID 数据库（例如 Wigle 9）。
- **生成过程:**
  1. 使用虚拟（经, 纬）查询本地数据库。
  2. 获取一个真实世界的 BSSID（MAC 地址）及其位置列表。
  3. 为每个 BSSID 构建一个 `ScanResult` 对象。
  4. 填充 BSSID、SSID（可伪造，如 "xfinitywifi"）以及一个基于虚拟坐标与 BSSID 真实位置之间距离计算得出的 RSSI（信号强度）。
- **结果:** `NetworkLocationProvider` 49 将报告一个与虚拟位置完全匹配的 Wi-Fi 环境。

**表 4.1：用于伪造位置的数据结构**

| **数据类型**   | **目标类**                      | **关键构造函数 / 需填充的字段**                              | **数据来源**           |
| -------------- | ------------------------------- | ------------------------------------------------------------ | ---------------------- |
| **位置**       | `android.location.Location`     | `setLatitude`, `setLongitude`, `setAltitude`, `setAccuracy`  | 控制应用               |
| **GNSS 状态**  | `android.location.GnssStatus`   | `GnssStatus(svCount, svidWithFlags, cn0s, elevations, azimuths)` 10 | 算法生成               |
| **GNSS 标志**  | `(int svidWithFlags)`           | `HAS_EPHEMERIS_DATA`, `HAS_ALMANAC_DATA`, `USED_IN_FIX` 10   | 算法生成               |
| **基站 (GSM)** | `android.telephony.CellInfoGsm` | `CellIdentityGsm` (MCC, MNC, LAC, CID) 12                    | 数据库 (如 OpenCelliD) |
| **基站 (LTE)** | `android.telephony.CellInfoLte` | `CellIdentityLte` (MCC, MNC, TAC, CI) 13                     | 数据库 (如 OpenCelliD) |
| **Wi-Fi**      | `android.net.wifi.ScanResult`   | `BSSID`, `SSID`, `level` (RSSI)                              | 数据库 (如 Wigle)      |



------



## 5.0 实现全沉浸：模拟相关的传感器数据



这是最高级的反检测层。伪造一个*移动中*的位置，却不伪造相应的运动传感器数据，是导致异常检测（Anomaly Detection）8 的主要原因。



### 5.1 威胁：传感器不匹配异常



- 关键发现 9: 一种主要的检测方法是“基于传感器的验证”。应用可以注册一个 `SensorEventListener` 15 来监听 `TYPE_ACCELEROMETER`（加速度计）17。
- **检测逻辑:** 如果 GPS 位置显示用户正在以 5 km/h 的速度“行走”，但加速度计数据却是一个完美的平直线（例如 `[0, 0, 9.8]`，表示手机正平放在桌面上），应用可以高置信度地判断位置正在被伪造 9。



### 5.2 Hook 点 5: 运动传感器



- **目标类:** `com.android.server.SensorManagerService` (或其等效实现，如 `SystemSensorManager`)。
- **目标方法:** 负责向已注册的监听器分发 `SensorEvent` 数据的核心方法。这通常是一个类似 `dispatchSensorEvent(SensorEvent event)` 的方法。
- **机制:**
  - 使用 `beforeHookedMethod` (前置 Hook)。
  - 检查 `callingUid` (详见 7.0 章节)。
  - 检查 `event.sensor.getType()` 15。
  - **如果** `uid` 是目标应用 **并且** 传感器类型是 `TYPE_ACCELEROMETER` 17 或 `TYPE_GYROSCOPE` 17：
    - 检查由控制应用（见 6.0 章节）提供的*虚拟运动状态*。
    - 如果状态是“静止”：允许真实事件通过。
    - 如果状态是“行走/驾驶”：阻止真实事件 (`param.setResult(null)`)，并注入一个伪造的 `SensorEvent` 16，其 `values` 数组模拟了该运动（例如，在 Z 轴上施加一个低幅度的正弦波来模拟“行走”时的颠簸）。
- **重要性:** 这将虚拟运动（例如“遥感”或“路线模拟”）与运动传感器数据完全同步，击败了基于传感器的异常检测。用户请求中的“步频”设置将直接控制这个生成的正弦波的频率。

------



## 6.0 控制平面架构：高速隐蔽的 IPC



位于 `system_server` (UID 1000) 中的 Hook 需要从一个用户界面的控制应用（App）接收指令（经纬度、速度、目标应用列表）。这需要一个安全、高速的进程间通信（IPC）通道。



### 6.1 IPC 挑战



- 一个用户空间应用 (UID 10xxx) 需要与 `system_server` (UID 1000) 通信。
- 通信必须是高速的（用于遥感/路线模拟）和隐蔽的（没有明显的“mock”服务名称）。



### 6.2 IPC 机制对比分析



- **方案一：Magisk Root 守护进程 + Socket**
  - **机制:** 一个 `su` 守护进程 5 以 Root 身份运行。它打开一个 UNIX 域套接字 (Socket) 83。控制应用和 `system_server` 中的 Hook 都作为客户端连接到此 Socket。
  - **优势:** 可行。Root 守护进程很常见。
  - **劣势:** 增加了另一个需要隐藏的进程。与 Binder 相比，Socket 通信不是 Android 的“原生”方式 21。
- **方案二：eBPF Maps 作为共享内存**
  - **机制:** eBPF Map 是一个驻留在内核的键值存储 39。控制应用（拥有 Root 权限）将期望的（经, 纬）写入一个固定的 (pinned) map 文件（例如 `/sys/fs/bpf/map_virt_loc_data`）39。`system_server` 中的 Hook 从这个 map 中读取数据。
  - **优势:** 内核级，速度极快。
  - **劣势:** API 笨拙。eBPF 被设计用于用户空间与内核空间通信 42，而不是用户空间与用户空间。这是对 eBPF 特性的滥用。
- **方案三 (推荐)：自定义 Binder 服务 (广义“BinderJacking”)**
  - **机制:** 这是最优雅的解决方案。术语“BinderJacking” 20 可以指*劫持*现有服务，但一个更稳定的用途是*注入一个新服务*。
  - **逻辑推导:**
    1. 我们的 LSPosed Hook 5 已经在 `system_server` 启动时运行。
    2. 此代码可以实例化一个全新的、自定义的 `IBinder` 服务（例如 `ICoreDisplayAnalytics.Stub`）。
    3. 然后，它调用 `ServiceManager.addService()`，使用一个*隐蔽的名称*（例如 "CoreDisplayAnalytics"）来注册这个新服务。
    4. 用户界面的控制应用（需要 Root/system 权限）可以调用 `ServiceManager.getService("CoreDisplayAnalytics")` 20 来获取该服务的句柄。
    5. 这建立了一个标准的、高速的、由内核支持的 Android 原生 IPC 通道 21，用于发送所有指令（设置位置、设置路线、设置速度、设置目标 UID）。
  - **优势:** 最“Android 原生”的方案 21。高速。安全（使用 Binder 内置的 UID/PID 验证）。命名隐蔽。

**表 6.1：用于 Hook 控制的 IPC 机制分析**

| **机制**                   | **主要用例**      | **速度** | **稳定性** | **命名隐蔽性**            | **推荐度**    |
| -------------------------- | ----------------- | -------- | ---------- | ------------------------- | ------------- |
| **Root 守护进程 + Socket** | 通用 Root IPC     | 高       | 高         | 中 (Socket 文件)          | 可行，但笨重  |
| **eBPF Map**               | 用户空间 <-> 内核 | 非常高   | 高         | 高 (Map 文件)             | 不推荐 (滥用) |
| **自定义 Binder 服务**     | 用户空间 <-> 服务 | 非常高   | 高         | **非常高** (隐蔽的服务名) | **推荐** 20   |



------



## 7.0 全局 Hook 与按应用过滤：实现 UID 筛选



本节直接回应用户的核心疑问：`(但这样是不是不能仅对个别应用返回模拟数据)` "但这样做是不是就不能只对个别应用返回模拟数据了？"

**答案是：可以，而且必须这样做。**



### 7.1 问题：全局 Hook，选择性目标



- 我们的 Hook (3.0, 5.0 章节) 位于*全局*的 `system_server` 中。这是一个特性，而不是缺陷。
- 我们不希望为*整个系统*“开启”虚拟定位，因为这极易被检测（例如，Google 地图会立即显示伪造位置）并且破坏了可用性。
- 我们需要让全局 Hook *仅在目标应用请求数据时*才选择性地激活。



### 7.2 解决方案：`Binder.getCallingUid()`



- **关键发现:** 所有对 `LocationManagerService`、`TelephonyRegistry` 和 `SensorManagerService` 的调用都是 Binder 事务 45。
- 机制 18: 当一个应用调用 `locationManager.getLastKnownLocation()` 时，它会触发一个 Binder 事务。当我们的 Hook 代码在 `system_server` 中执行时，我们可以调用 `Binder.getCallingUid()` 来获取*发起调用的*那个应用的 Linux UID（例如 `10182`）。
- **证据:** 19 证实了这是一个有效的 Xposed 技术，它展示了一个在 `PermissionManagerService` 中的 Hook 如何通过检查 UID 来拒绝特定应用的权限请求。



### 7.3 实现蓝图



1. **控制应用:** 用户选择目标应用（例如 "应用 A", "应用 B"）。控制应用将这些包名转换为一个 UID 的 `Set<Integer>` 集合。

2. **IPC:** 控制应用通过 6.0 章节中的自定义 Binder 服务，将这个 `targetUids` 集合发送到 `system_server` 内部缓存。

3. **Hook 逻辑:** 我们*所有*的 Hook (H1-H6) 都必须被包裹在以下逻辑中：

   Java

   ```
   // 位于 LSPosed Hook 内部 (例如在 WifiService.getScanResults 中)
   protected void afterHookedMethod(MethodHookParam param) throws Throwable {
       int callingUid = Binder.getCallingUid(); // 
   
       // 检查这个 UID 是否在我们的控制应用提供的目标集合中
       if (GlobalConfig.targetUids.contains(callingUid)) {
           // 这是一个目标应用。返回伪造数据。
           param.setResult(GlobalConfig.getForgedScanResultList()); // 
       } else {
           // 这是一个普通应用。什么都不做，让原始的真实结果通过。
       }
   }
   ```

- **结果:** 这是最终极的隐蔽形态。系统中 99% 的应用（包括 Google 地图、天气挂件）都正常运行，接收真实数据。只有被选中的 1-2 个目标应用会接收到我们提供的、全方位的虚拟环境数据。

------



## 8.0 综合隐蔽与反检测策略



本架构在设计上已具备高隐蔽性，但它依赖于一个可被检测的框架 (LSPosed)。本节详述所需的多层防御策略。



### 8.1 第 1 层：绕过 `Location.isMock()` (应用级)



- **威胁:** 应用检查 `Location.isMock()` 60 或 `Settings.Secure.ALLOW_MOCK_LOCATION`（模拟位置开发者选项）2。
- **防御:** **已通过架构设计绕过**。我们的 Hook (3.0 章节) 在*Provider*（提供商）级别 (`GnssManagerService` 51) 注入数据，这位于系统应用 `isMock` 标志的*上游*。系统认为它正在接收真实的硬件事件 86，因此 `isMock` 标志位为 `false`。



### 8.2 第 2 层：规避异常检测 (系统级)



- **威胁:** 多源数据不匹配 8。
  - GPS 位置 vs. 网络位置（Wi-Fi/基站）9。
  - 位置 vs. 运动传感器 9。
- **防御:** **已通过全景式伪造解决**。
  - 4.3 和 4.4 章节（伪造 CellInfo 和 Wi-Fi）确保了 GPS 和网络位置的完美对齐。
  - 5.0 章节（伪造 SensorEvent）确保了虚拟运动与运动传感器数据的同步。



### 8.3 第 3 层：隐藏 Root 与 Hooking 框架 (元隐蔽)



- **威胁:** 这是我们架构的主要弱点。应用可以检测 Magisk、Zygisk 88 以及 LSPosed 框架本身 35。

- **防御:** 我们必须使用*其他*模块来隐藏我们自己的基础设施。

  1. **隐藏 Root:** 使用启用了 Zygisk 的 Magisk 4。使用 **Shamiko** 模块 88，该模块专为隐藏 Magisk 和 Zygisk 而设计。
  2. **隐藏 LSPosed:** 使用 **`Hide My Applist`** (HMA) 36 之类的 LSPosed 模块。HMA 的工作原理是 Hook `PackageManager` 31，以过滤*返回给目标应用*的已安装应用列表。

  - **配置:** 我们需要配置 HMA 36，使其向我们的目标应用隐藏“控制应用”的包名、“LSPosed 管理器”的包名以及其他所有与 Root 相关的应用。



### 8.4 第 4 层：隐蔽命名与组件



- **威胁:** 用户的明确要求。服务名称不应包含 "mock" 或 "spoof" 22。
- **防御:**
  - **控制应用:** 包名应为 `com.android.providers.health` 或类似名称。应用名称应为 "设备健康服务"。
  - **Binder 服务:** 名称应为 "CoreDisplayAnalytics" 或 "DeviceMonitor" (如 6.0 章节所述)。
  - **LSPosed 模块:** 模块本身应有不起眼的名称。

------



## 9.0 面向未来的架构：应对 Android 16 的 `RangingManager`



用户的请求*明确*包括了 Android 16。此版本引入了一个全新的高完整性定位 API，如果不加以处理，它将*完全绕过*我们前面设置的所有 Hook。



### 9.1 新威胁：802.11az 和 `RangingManager`



- **Android 16** 引入了使用 802.11az 的 Wi-Fi 安全定位功能 23，该标准在设计上旨在抵抗欺骗和中间人攻击 92。
- **新 API:** 系统引入了一个全新的 `RangingManager` API 25，统一了 Wi-Fi RTT (802.11az)、UWB（超宽带）和蓝牙测距。
- “Opt-In” 问题 25: 应用程序可以请求新的 `android.permission.RANGING` 权限，并“选择使用”(Opt-In) 这个新 API 25。



### 9.2 关键洞察：新的绕过向量



- **失败的因果链:**
  1. 我们的 Hook (3.0 章节) 部署在 `GnssManagerService`, `TelephonyRegistry` 和 `WifiServiceImpl.getScanResults()` 上。
  2. 一个运行在 Android 16 上的目标应用（API 36+），请求了 `RANGING` 权限。
  3. 该应用调用 `RangingManager` API 25。
  4. 此 API 通过一个*不同的*服务（`RangingManagerService`），*而不是* `WifiServiceImpl`，与硬件通信。
  5. 它从硬件接收到一个*真实的*、经过加密验证的 802.11az Wi-Fi RTT 位置。
  6. 这个*真实的* Wi-Fi RTT 位置将与我们*伪造的* GPS/Cell 位置产生灾难性的冲突。
  7. **100% 被检测。**



### 9.3 解决方案：扩展 Hook 集合



这个新的 API *必须*被 Hook。

- **Hook 点 6 (Android 16+):**
  - **目标类:** `com.android.server.RangingManagerService` (或其等效实现)。
  - **目标方法:** 负责返回测距结果的方法 (例如 `getRangingResults(RangingRequest request,...)`).
- **机制:**
  - 使用 `afterHookedMethod` (后置 Hook)。
  - 与所有其他 Hook 一样，检查 `Binder.getCallingUid()` 18。
  - 如果是目标应用，则 `param.setResult(forgedRangingResultList)`。
- **架构优势 (绕过 802.11az 安全性):** 802.11az 的*协议层*安全（如 AES-256 23）与我们的策略*无关*。我们不是在对 Wi-Fi 协议进行中间人攻击 93。我们是在 Hook *Java 框架* 5，拦截的是 HAL（硬件抽象层）完成所有解密和验证工作之后的*最终结果*，以及在它被交付给应用程序之*前*。

------



## 10.0 最终架构蓝图与建议



此蓝图整合了所有分析结果，形成了一个单一、内聚的系统架构。



### 10.1 系统示意图 (数据流)



1. **控制层 (用户 App):**
   - 提供地图选点、路线规划、速度/步频设置、目标应用选择的 UI。
   - 持有 Root 权限。
   - 通过自定义 Binder IPC (6.0 章节) 与 `system_server` 通信。
2. **IPC 层 (自定义 Binder 服务):**
   - 运行于 `system_server` 内部 (由 LSPosed 5 加载)。
   - 接收指令（经纬度、速度、`targetUids` 列表）。
   - 作为所有 Hook 的全局状态管理器。
3. **Hooking 层 (LSPosed @ `system_server`):**
   - **按应用过滤:** 所有的 Hook (H1-H6) 都会检查 `Binder.getCallingUid()` 18 是否在 `targetUids` 集合中。
   - **Hook 集合:**
     - **H1/H2:** `GnssManagerService.reportLocation/reportGnssStatus` 51。
     - **H3:** `TelephonyRegistry.getAllCellInfo` 68。
     - **H4:** `WifiServiceImpl.getScanResults` 14。
     - **H5:** `SensorManagerService.dispatchSensorEvent` 15。
     - **H6 (A16+):** `RangingManagerService.getRangingResults` 25。
4. **数据伪造引擎 (位于 `system_server`):**
   - 生成 `Location` 对象。
   - 生成 `GnssStatus` (3D 固定) 10 对象 (算法生成)。
   - 生成 `CellInfo` 11 列表 (来自本地 OpenCelliD 9 数据库)。
   - 生成 `ScanResult` 列表 (来自本地 Wigle 9 数据库)。
   - 生成 `SensorEvent` 16 数据流 (基于速度算法生成)。
   - 生成 `RangingResult` 对象 (伪造的测距结果)。
5. **元隐蔽层 (框架):**
   - Magisk + Zygisk 4 + Shamiko 88 (隐藏 Root)。
   - LSPosed 5 + `Hide My Applist` 36 (隐藏 Hook 框架和控制应用)。



### 10.2 最终建议



- **采用 LSPosed/Zygisk 栈:** 这是实现所需 Java 框架 Hook 的唯一稳定、可维护的途径。不要尝试 eBPF 或原生注入 20。
- **Hook `system_server`:** 不要 Hook 单个应用。Hook `system_server` 6 是捕获所有位置消费者（包括 GMS FLP 64）的中央枢纽。
- **实现全景式伪造:** *绝不能*只 Hook `reportLocation`。必须协同伪造 `GnssStatus` (3D 固定) 10、`CellInfo` (基站) 11、`ScanResult` (Wi-Fi) 14 和 `SensorEvent` (传感器) 16 数据，以彻底规避异常检测 9。
- **实现 UID 过滤:** `Binder.getCallingUid()` 18 检查*不是*可选项。它是实现“按应用”模拟和防止系统级检测的核心机制。
- **优先处理 Android 16:** `RangingManager` 25 是最重大的新威胁。今天构建的任何架构都*必须*包含对此服务的 Hook，否则它将在新设备上立即失效并被检测。
- **隐蔽是分层的:** 解决方案必须是自感知的。它必须包含 Shamiko 88 和 HMA 36 等模块来隐藏其自身组件。服务和包名*必须*使用平庸的名称 22 以规避关键词扫描。