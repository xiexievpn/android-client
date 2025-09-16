package com.v2ray.ang

import android.content.Context
import androidx.multidex.MultiDexApplication
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LanguageManager

class AngApplication : MultiDexApplication() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        // 应用多语言设置
        val contextWithLanguage = if (base != null) {
            LanguageManager.autoApplyLanguage(base)
        } else {
            base
        }
        super.attachBaseContext(contextWithLanguage)
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        SettingsManager.setNightMode()
        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        SettingsManager.initRoutingRulesets(this)

        es.dmoral.toasty.Toasty.Config.getInstance()
            .setGravity(android.view.Gravity.BOTTOM, 0, 200)
            .apply()
    }
}
