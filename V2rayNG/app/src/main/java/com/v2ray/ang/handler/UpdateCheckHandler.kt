package com.v2ray.ang.handler
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.VersionInfo
import com.v2ray.ang.util.HttpUtil
import java.util.Locale
object UpdateCheckHandler {
    private const val TAG = "UpdateCheckHandler"
    private const val TIMEOUT = 15000 
    fun checkForUpdate(context: Context): CheckUpdateResult {
        return try {
            val updateUrl = getUpdateUrl(context)
            Log.d(TAG, "Checking for update from: $updateUrl")
            val responseJson = HttpUtil.getUrlContent(updateUrl, TIMEOUT)
            if (responseJson.isNullOrEmpty()) {
                Log.e(TAG, "Empty response from update server")
                return CheckUpdateResult(
                    hasUpdate = false,
                    error = "无法获取版本信息"
                )
            }
            val versionInfo = Gson().fromJson(responseJson, VersionInfo::class.java)
            val rawVersionCode = BuildConfig.VERSION_CODE
            val currentVersionCode = if (rawVersionCode > 1000000) {
                rawVersionCode % 1000000  
            } else {
                rawVersionCode
            }
            val serverVersionCode = versionInfo.versionCode
            Log.d(TAG, "Raw: $rawVersionCode, Current base: $currentVersionCode, Server: $serverVersionCode")
            if (serverVersionCode > currentVersionCode) {
                val serverName = versionInfo.versionName
                val downloadUrl = "https:
                Log.d(TAG, "Force update required. URL: $downloadUrl")
                return CheckUpdateResult(
                    hasUpdate = true,
                    latestVersion = serverName,
                    releaseNotes = "发现新版本，请立即更新。", 
                    downloadUrl = downloadUrl,
                    error = null,
                    isPreRelease = false
                )
            } else {
                Log.d(TAG, "Already at latest version")
                return CheckUpdateResult(hasUpdate = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            CheckUpdateResult(
                hasUpdate = false,
                error = e.message ?: "检查更新失败"
            )
        }
    }
    private fun getUpdateUrl(context: Context): String {
        val currentLocale = context.resources.configuration.locales[0]
        val language = currentLocale.language
        return if (language == Locale.CHINESE.language) {
            AppConfig.APP_UPDATE_CHECK_URL_CN
        } else {
            AppConfig.APP_UPDATE_CHECK_URL_EN
        }
    }
}