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
        }
    }
    private suspend fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            PluginServiceManager.lastHy2Error = "decodeServerConfig返回null(guid=${guid.take(8)})"
        } else {
            PluginServiceManager.lastHy2Error = "configType=${config.configType?.name},remarks=${config.remarks}"
        }
        if (config?.configType == EConfigType.HYSTERIA2) {
            PluginServiceManager.lastHy2Error = "进入HY2分支,调用realPingHy2..."
            return PluginServiceManager.realPingHy2(context, config)
        }
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return V2RayNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }
}
