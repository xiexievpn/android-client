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
