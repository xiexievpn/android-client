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
