package com.v2ray.ang.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.*

object LanguageManager {

    /**
     * 检测系统语言并返回适当的语言代码
     * @return "zh" 对于中文语系，"en" 对于其他语系
     */
    fun getSystemLanguage(): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0及以上版本
            Locale.getDefault()
        } else {
            // Android 7.0以下版本
            @Suppress("DEPRECATION")
            Locale.getDefault()
        }

        val language = locale.language
        val country = locale.country

        // 检查是否为中文语系
        return when {
            language == "zh" -> "zh"  // 中文简体、繁体等
            language == "zh" && country in listOf("CN", "TW", "HK", "SG") -> "zh"
            else -> "en"  // 其他语系使用英文
        }
    }

    /**
     * 应用语言设置到Context
     * @param context Context对象
     * @param languageCode 语言代码 ("zh" 或 "en")
     * @return 应用了语言设置的新Context
     */
    fun applyLanguage(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }

        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            context
        }
    }

    /**
     * 自动检测并应用系统语言
     * @param context Context对象
     * @return 应用了语言设置的新Context
     */
    fun autoApplyLanguage(context: Context): Context {
        val systemLanguage = getSystemLanguage()
        return applyLanguage(context, systemLanguage)
    }
}