# V2rayNG 定制版 — HY2 测速失败排查

## 问题描述

同一台安卓设备、同一个订阅链接：
- **官方 V2rayNG**：VLESS 和 HY2 都能正常测速（76ms），HY2 实际连接延迟更低（101ms vs 174ms）
- **定制版**：VLESS 正常测速（149ms），**HY2 测速永远返回 -1（失败）**

## 已尝试的修复（均无效）

1. 修改 `getV2rayNormalConfig4Speedtest` 去掉 HY2 的 `getPlusOutbounds` 分支，统一走 `getOutbounds`（对齐官方）→ 仍失败
2. 在 `RealPingWorkerService.startRealPing` 中对 HY2 走 `PluginServiceManager.realPingHy2`（启动临时 HY2 进程 + SOCKS 代理测延迟）→ 仍失败
3. 已回滚方案 2，保留方案 1

## 官方 V2rayNG 的测速实现（对比参考）

官方对所有协议（包括 HY2）走完全一样的路径，没有特殊处理：
- `getV2rayConfig4Speedtest` → `getV2rayNormalConfig4Speedtest` → `getOutbounds` → `convertProfile2Outbound`
- `RealPingWorkerService.startRealPing` → `V2RayNativeManager.measureOutboundDelay(config, url)` → `Libv2ray.measureOutboundDelay()`
- 官方**没有** HY2 的 `getPlusOutbounds` 分支
- 官方 `measureOutboundDelay` (Go JNI) 原生支持 HY2

---

## 当前全部相关源码

### 1. AppConfig.kt — 测速相关常量
文件路径: `app/src/main/java/com/v2ray/ang/AppConfig.kt`

```kotlin
const val MSG_MEASURE_DELAY = 6
const val MSG_MEASURE_DELAY_SUCCESS = 61
const val MSG_MEASURE_CONFIG = 7
const val MSG_MEASURE_CONFIG_SUCCESS = 71
const val MSG_MEASURE_CONFIG_CANCEL = 72
const val MSG_MEASURE_CONFIG_NOTIFY = 73
const val MSG_MEASURE_CONFIG_FINISH = 74
```

---

### 2. V2RayNativeManager.kt — Libv2ray JNI 封装
文件路径: `app/src/main/java/com/v2ray/ang/handler/V2RayNativeManager.kt`

```kotlin
package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.concurrent.atomic.AtomicBoolean

object V2RayNativeManager {
    private val initialized = AtomicBoolean(false)

    fun initCoreEnv(context: Context?) {
        if (initialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(context?.applicationContext)
                val assetPath = Utils.userAssetPath(context)
                val deviceId = Utils.getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                Log.i(AppConfig.TAG, "V2Ray core environment initialized successfully")
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to initialize V2Ray core environment", e)
                initialized.set(false)
                throw e
            }
        }
    }

    fun getLibVersion(): String {
        return try {
            Libv2ray.checkVersionX()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to check V2Ray version", e)
            "Unknown"
        }
    }

    fun measureOutboundDelay(config: String, testUrl: String): Long {
        return try {
            Libv2ray.measureOutboundDelay(config, testUrl)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to measure outbound delay", e)
            -1L
        }
    }

    fun newCoreController(handler: CoreCallbackHandler): CoreController {
        return Libv2ray.newCoreController(handler)
    }
}
```

---

### 3. V2rayConfigManager.kt — 测速配置生成（核心问题所在）
文件路径: `app/src/main/java/com/v2ray/ang/handler/V2rayConfigManager.kt`

#### getV2rayConfig4Speedtest（入口）
```kotlin
fun getV2rayConfig4Speedtest(context: Context, guid: String): ConfigResult {
    try {
        val config = MmkvManager.decodeServerConfig(guid) ?: return ConfigResult(false)
        return if (config.configType == EConfigType.CUSTOM) {
            getV2rayCustomConfig(guid, config)
        } else {
            getV2rayNormalConfig4Speedtest(context, guid, config)
        }
    } catch (e: Exception) {
        Log.e(AppConfig.TAG, "Failed to get V2ray config for speedtest", e)
        return ConfigResult(false)
    }
}
```

#### getV2rayNormalConfig4Speedtest（当前状态 — 已改为与官方一致）
```kotlin
private fun getV2rayNormalConfig4Speedtest(context: Context, guid: String, config: ProfileItem): ConfigResult {
    val result = ConfigResult(false)

    val address = config.server ?: return result
    if (!Utils.isPureIpAddress(address)) {
        if (!Utils.isValidUrl(address)) {
            Log.w(AppConfig.TAG, "$address is an invalid ip or domain")
            return result
        }
    }

    val v2rayConfig = initV2rayConfig(context) ?: return result

    // 所有协议统一走 getOutbounds（与官方 V2rayNG 对齐）
    // 注意：测速不需要 getPlusOutbounds（SOCKS 代理），measureOutboundDelay 原生支持 HY2
    getOutbounds(v2rayConfig, config) ?: return result
    getMoreOutbounds(v2rayConfig, config.subscriptionId)

    v2rayConfig.log.loglevel = MmkvManager.decodeSettingsString(AppConfig.PREF_LOGLEVEL) ?: "warning"
    v2rayConfig.inbounds.clear()
    v2rayConfig.routing.rules.clear()
    v2rayConfig.dns = null
    v2rayConfig.fakedns = null
    v2rayConfig.stats = null
    v2rayConfig.policy = null

    v2rayConfig.outbounds.forEach { key ->
        key.mux = null
    }

    result.status = true
    result.content = JsonUtil.toJsonPretty(v2rayConfig) ?: ""
    result.guid = guid
    return result
}
```

##### 修改前（问题代码 — HY2 走了 SOCKS 代理路径）：
```kotlin
// 修改前的代码（已删除）：
if (config.configType == EConfigType.HYSTERIA2) {
    result.socksPort = getPlusOutbounds(v2rayConfig, config) ?: return result
} else {
    getOutbounds(v2rayConfig, config) ?: return result
    getMoreOutbounds(v2rayConfig, config.subscriptionId)
}
```

#### getOutbounds（将 ProfileItem 转为 Xray outbound JSON）
```kotlin
private fun getOutbounds(v2rayConfig: V2rayConfig, config: ProfileItem): Boolean? {
    val outbound = convertProfile2Outbound(config) ?: return null
    val ret = updateOutboundWithGlobalSettings(outbound)
    if (!ret) return null

    if (v2rayConfig.outbounds.isNotEmpty()) {
        v2rayConfig.outbounds[0] = outbound
    } else {
        v2rayConfig.outbounds.add(outbound)
    }

    updateOutboundFragment(v2rayConfig)
    return true
}
```

#### getPlusOutbounds（HY2 SOCKS 代理 — 仅用于正常 VPN 连接，不应用于测速）
```kotlin
private fun getPlusOutbounds(v2rayConfig: V2rayConfig, config: ProfileItem): Int? {
    try {
        val socksPort = Utils.findFreePort(listOf(100 + SettingsManager.getSocksPort(), 0))

        val outboundNew = OutboundBean(
            mux = null,
            protocol = EConfigType.SOCKS.name.lowercase(),
            settings = OutSettingsBean(
                servers = listOf(
                    OutSettingsBean.ServersBean(
                        address = AppConfig.LOOPBACK,
                        port = socksPort
                    )
                )
            )
        )
        if (v2rayConfig.outbounds.isNotEmpty()) {
            v2rayConfig.outbounds[0] = outboundNew
        } else {
            v2rayConfig.outbounds.add(outboundNew)
        }

        return socksPort
    } catch (e: Exception) {
        Log.e(AppConfig.TAG, "Failed to configure plusOutbound", e)
        return null
    }
}
```

#### getV2rayNormalConfig（正常 VPN 连接配置 — 这里 HY2 走 getPlusOutbounds 是正确的）
```kotlin
private fun getV2rayNormalConfig(context: Context, guid: String, config: ProfileItem): ConfigResult {
    // ...省略前面的校验...
    
    val v2rayConfig = initV2rayConfig(context) ?: return result

    if (config.configType == EConfigType.HYSTERIA2) {
        result.socksPort = getPlusOutbounds(v2rayConfig, config) ?: return result
    } else {
        getOutbounds(v2rayConfig, config) ?: return result
        getMoreOutbounds(v2rayConfig, config.subscriptionId)
    }
    // ...后续 routing, dns 等配置...
}
```

---

### 4. RealPingWorkerService.kt — 批量测速执行者
文件路径: `app/src/main/java/com/v2ray/ang/service/RealPingWorkerService.kt`

```kotlin
package com.v2ray.ang.service

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PluginServiceManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onFinish: (status: String) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val cpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val dispatcher = Executors.newFixedThreadPool(cpu * 4).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, result))
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "$left / $count")
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return V2RayNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }
}
```

---

### 5. V2RayTestService.kt — 测速 Android Service
文件路径: `app/src/main/java/com/v2ray/ang/service/V2RayTestService.kt`

```kotlin
package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.util.MessageUtil
import java.util.Collections

class V2RayTestService : Service() {

    private val activeWorkers = Collections.synchronizedList(mutableListOf<RealPingWorkerService>())

    override fun onCreate() {
        super.onCreate()
        V2RayNativeManager.initCoreEnv(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        val snapshot = ArrayList(activeWorkers)
        snapshot.forEach { it.cancel() }
        activeWorkers.clear()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getIntExtra("key", 0)) {
            MSG_MEASURE_CONFIG -> {
                val guidsList = intent.serializable<ArrayList<String>>("content")
                if (guidsList != null && guidsList.isNotEmpty()) {
                    lateinit var worker: RealPingWorkerService
                    worker = RealPingWorkerService(this, guidsList) { status ->
                        MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                        activeWorkers.remove(worker)
                    }
                    activeWorkers.add(worker)
                    worker.start()
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                val snapshot = ArrayList(activeWorkers)
                snapshot.forEach { it.cancel() }
                activeWorkers.clear()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }
}
```

---

### 6. MessageUtil.kt — 消息工具
文件路径: `app/src/main/java/com/v2ray/ang/util/MessageUtil.kt`

```kotlin
package com.v2ray.ang.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.service.V2RayTestService
import java.io.Serializable

object MessageUtil {

    fun sendMsg2Service(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_SERVICE, what, content)
    }

    fun sendMsg2UI(ctx: Context, what: Int, content: Serializable) {
        sendMsg(ctx, AppConfig.BROADCAST_ACTION_ACTIVITY, what, content)
    }

    fun sendMsg2TestService(ctx: Context, what: Int, content: Serializable) {
        try {
            val intent = Intent()
            intent.component = ComponentName(ctx, V2RayTestService::class.java)
            intent.putExtra("key", what)
            intent.putExtra("content", content)
            ctx.startService(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to send message to test service", e)
        }
    }

    private fun sendMsg(ctx: Context, action: String, what: Int, content: Serializable) {
        try {
            val intent = Intent()
            intent.action = action
            intent.`package` = AppConfig.ANG_PACKAGE
            intent.putExtra("key", what)
            intent.putExtra("content", content)
            ctx.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to send message with action: $action", e)
        }
    }
}
```

---

### 7. SpeedtestManager.kt — TCP Ping 工具
文件路径: `app/src/main/java/com/v2ray/ang/handler/SpeedtestManager.kt`

```kotlin
package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

object SpeedtestManager {

    private val tcpTestingSockets = ArrayList<Socket?>()

    suspend fun tcping(url: String, port: Int): Long {
        var time = -1L
        for (k in 0 until 2) {
            val one = socketConnectTime(url, port)
            if (!currentCoroutineContext().isActive) {
                break
            }
            if (one != -1L && (time == -1L || one < time)) {
                time = one
            }
        }
        return time
    }

    fun socketConnectTime(url: String, port: Int): Long {
        try {
            val socket = Socket()
            synchronized(this) {
                tcpTestingSockets.add(socket)
            }
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(url, port), 3000)
            val time = System.currentTimeMillis() - start
            synchronized(this) {
                tcpTestingSockets.remove(socket)
            }
            socket.close()
            return time
        } catch (e: UnknownHostException) {
            Log.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            Log.e(AppConfig.TAG, "socketConnectTime IOException: $e")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish socket connection to $url:$port", e)
        }
        return -1
    }

    fun closeAllTcpSockets() {
        synchronized(this) {
            tcpTestingSockets.forEach {
                it?.close()
            }
            tcpTestingSockets.clear()
        }
    }
}
```

---

### 8. PluginServiceManager.kt — HY2 插件管理
文件路径: `app/src/main/java/com/v2ray/ang/handler/PluginServiceManager.kt`

```kotlin
package com.v2ray.ang.handler

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.EConfigType
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.fmt.Hysteria2Fmt
import com.v2ray.ang.service.ProcessService
import com.v2ray.ang.util.JsonUtil
import com.v2ray.ang.util.Utils
import java.io.File

object PluginServiceManager {
    private const val HYSTERIA2 = "libhysteria2.so"

    private val procService: ProcessService by lazy {
        ProcessService()
    }

    fun runPlugin(context: Context, config: ProfileItem?, socksPort: Int?) {
        Log.i(AppConfig.TAG, "Starting plugin execution")
        if (config == null) {
            Log.w(AppConfig.TAG, "Cannot run plugin: config is null")
            return
        }
        try {
            if (config.configType == EConfigType.HYSTERIA2) {
                if (socksPort == null) {
                    Log.w(AppConfig.TAG, "Cannot run plugin: socksPort is null")
                    return
                }
                Log.i(AppConfig.TAG, "Running Hysteria2 plugin")
                val configFile = genConfigHy2(context, config, socksPort) ?: return
                val cmd = genCmdHy2(context, configFile)
                procService.runProcess(context, cmd)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Error running plugin", e)
        }
    }

    fun stopPlugin() {
        stopHy2()
    }

    fun realPingHy2(context: Context, config: ProfileItem?): Long {
        Log.i(AppConfig.TAG, "realPingHy2 start")
        val retFailure = -1L
        if (config?.configType != EConfigType.HYSTERIA2) return retFailure

        val socksPort = Utils.findFreePort(listOf(0))
        val configFile = genConfigHy2(context, config, socksPort) ?: return retFailure
        val cmd = genCmdHy2(context, configFile)

        val proc = ProcessService()
        try {
            proc.runProcess(context, cmd)
            Thread.sleep(2000L)

            val proxy = java.net.Proxy(
                java.net.Proxy.Type.SOCKS,
                java.net.InetSocketAddress("127.0.0.1", socksPort)
            )
            val url = java.net.URL(SettingsManager.getDelayTestUrl())
            val conn = url.openConnection(proxy) as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Connection", "close")

            val start = System.currentTimeMillis()
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - start
            conn.disconnect()

            Log.i(AppConfig.TAG, "realPingHy2 result: ${elapsed}ms (HTTP $code)")
            return if (code == 204 || code == 200) elapsed else retFailure
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "realPingHy2 failed", e)
            return retFailure
        } finally {
            proc.stopProcess()
            try { configFile.delete() } catch (_: Exception) {}
        }
    }

    private fun genConfigHy2(context: Context, config: ProfileItem, socksPort: Int): File? {
        Log.i(AppConfig.TAG, "runPlugin $HYSTERIA2")
        val hy2Config = Hysteria2Fmt.toNativeConfig(config, socksPort) ?: return null
        val configFile = File(context.noBackupFilesDir, "hy2_${SystemClock.elapsedRealtime()}.json")
        Log.i(AppConfig.TAG, "runPlugin ${configFile.absolutePath}")
        configFile.parentFile?.mkdirs()
        configFile.writeText(JsonUtil.toJson(hy2Config))
        Log.i(AppConfig.TAG, JsonUtil.toJson(hy2Config))
        return configFile
    }

    private fun genCmdHy2(context: Context, configFile: File): MutableList<String> {
        return mutableListOf(
            File(context.applicationInfo.nativeLibraryDir, HYSTERIA2).absolutePath,
            "--disable-update-check",
            "--config",
            configFile.absolutePath,
            "--log-level",
            "warn",
            "client"
        )
    }

    private fun stopHy2() {
        try {
            Log.i(AppConfig.TAG, "$HYSTERIA2 destroy")
            procService?.stopProcess()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop Hysteria2 process", e)
        }
    }
}
```

---

### 9. MainViewModel.kt — testAllTcping / testAllRealPing
文件路径: `app/src/main/java/com/v2ray/ang/viewmodel/MainViewModel.kt`

```kotlin
// testAllTcping（TCP 连接测速）
fun testAllTcping() {
    tcpingTestScope.coroutineContext[Job]?.cancelChildren()
    SpeedtestManager.closeAllTcpSockets()
    MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())

    val serversCopy = serversCache.toList()
    for (item in serversCopy) {
        item.profile.let { outbound ->
            val serverAddress = outbound.server
            val serverPort = outbound.serverPort
            if (serverAddress != null && serverPort != null) {
                tcpingTestScope.launch {
                    val testResult = SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                    launch(Dispatchers.Main) {
                        MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                        updateListAction.value = getPosition(item.guid)
                    }
                }
            }
        }
    }
}

// testAllRealPing（通过 Xray/HY2 核心测真实代理延迟）
fun testAllRealPing() {
    MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG_CANCEL, "")
    MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())
    updateListAction.value = -1

    val serversCopy = serversCache.toList()
    viewModelScope.launch(Dispatchers.Default) {
        val guids = ArrayList<String>(serversCopy.map { it.guid })
        if (guids.isEmpty()) {
            return@launch
        }
        MessageUtil.sendMsg2TestService(getApplication(), AppConfig.MSG_MEASURE_CONFIG, guids)
    }
}
```

---

### 10. MainActivity.kt — autoSelectBestServer（自动选优）
文件路径: `app/src/main/java/com/v2ray/ang/ui/MainActivity.kt`

```kotlin
private fun autoSelectBestServer() {
    val serverList = MmkvManager.decodeServerList()
    if (serverList.isEmpty()) return
    if (serverList.size == 1) {
        MmkvManager.setSelectServer(serverList.first())
        val cfg = MmkvManager.decodeServerConfig(serverList.first())
        toast("仅1个节点: ${cfg?.remarks} | ${cfg?.configType?.name}")
        return
    }

    toast("正在测速选优（${serverList.size}个节点）...")

    lifecycleScope.launch(Dispatchers.IO) {
        // 1. 触发 V2rayNG 原生 Real Ping 测速
        withContext(Dispatchers.Main) {
            mainViewModel.testAllRealPing()
        }

        // 2. 轮询等待所有结果，最多 15 秒
        for (i in 0..30) {
            delay(500)
            val allDone = serverList.all { guid ->
                val d = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                d != 0L  // 0 = 未测，>0 = 成功，-1 = 失败
            }
            if (allDone) break
        }

        // 3. 收集所有结果
        val penalizedType = V2RayServiceManager.getPenalizedConfigType()
        data class NodeResult(val guid: String, val name: String, val protocol: EConfigType?, val rawDelay: Long, var effectiveDelay: Long)

        val results = serverList.map { guid ->
            val cfg = MmkvManager.decodeServerConfig(guid)
            val raw = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
            var effective = raw
            if (penalizedType != null && cfg?.configType == penalizedType && effective > 0) {
                effective += 5000
            }
            NodeResult(guid, cfg?.remarks ?: guid.take(8), cfg?.configType, raw, effective)
        }

        // 诊断 toast
        val diagLines = results.joinToString("\n") { r ->
            val dStr = if (r.rawDelay > 0) "${r.rawDelay}ms" else "失败"
            "${r.name}|${r.protocol?.name}: $dStr"
        }
        withContext(Dispatchers.Main) {
            toast(diagLines)
        }

        // 4. 选最优节点
        val valid = results.filter { it.effectiveDelay > 0 }
        val best = if (valid.isNotEmpty()) {
            val sorted = valid.sortedBy { it.effectiveDelay }
            val fastest = sorted.first()
            // HY2 优选策略：延迟差 ≤50ms 优先 HY2
            val hy2Candidate = sorted.firstOrNull {
                it.protocol == EConfigType.HYSTERIA2 && it.effectiveDelay - fastest.effectiveDelay <= 50
            }
            hy2Candidate ?: fastest
        } else {
            results.first()
        }

        // 5. 应用选择
        withContext(Dispatchers.Main) {
            val current = MmkvManager.getSelectServer()
            val delayStr = if (best.rawDelay > 0) "${best.rawDelay}ms" else "失败"
            val penaltyNote = if (penalizedType != null) " [${penalizedType.name}已降级]" else ""
            toast("已选: ${best.name} | ${best.protocol?.name} ($delayStr)$penaltyNote")

            if (current != best.guid) {
                MmkvManager.setSelectServer(best.guid)
                mainViewModel.reloadServerList()
                if (mainViewModel.isRunning.value == true) {
                    V2RayServiceManager.stopVService(this@MainActivity)
                    delay(500)
                    V2RayServiceManager.startVServiceFromToggle(this@MainActivity)
                }
            }
        }
    }
}
```

---

## 测速调用链路

```
用户点击 autoSelectBestServer
  → mainViewModel.testAllRealPing()
    → MessageUtil.sendMsg2TestService(MSG_MEASURE_CONFIG, guids)
      → V2RayTestService.onStartCommand()
        → RealPingWorkerService.start()
          → startRealPing(guid)
            → V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
              → getV2rayNormalConfig4Speedtest()
                → getOutbounds(v2rayConfig, config)  ← 关键：HY2 走这里还是 getPlusOutbounds？
            → V2RayNativeManager.measureOutboundDelay(config, url)
              → Libv2ray.measureOutboundDelay()  ← JNI 调 Go 代码
          → 结果通过 broadcast 发回 MainViewModel
  → 轮询 MmkvManager 读取结果
  → 选最优节点
```

## 待解决

HY2 测速仍然返回 -1（失败）。已确认 `getV2rayNormalConfig4Speedtest` 改成了跟官方一致的 `getOutbounds` 路径。

可能的其他原因：
1. `convertProfile2Outbound()` 对 HY2 配置生成的 outbound JSON 是否跟官方一致？
2. `Libv2ray.measureOutboundDelay()` 的 Go 实现（.so 文件）版本是否支持 HY2？
3. `getOutbounds` → `convertProfile2Outbound` 返回 null 导致直接 `return result`（status=false）？
4. HY2 的 ProfileItem 字段（server, serverPort, security 等）是否完整？
