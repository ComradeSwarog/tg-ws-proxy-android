package com.github.tgwsproxy

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.preference.PreferenceManager
import java.util.Locale

object LocaleUtils {
    private const val PREF_APP_LANGUAGE = "app_language"

    fun getAppLanguage(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_APP_LANGUAGE, "") ?: ""
    }

    fun setAppLanguage(context: Context, language: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString(PREF_APP_LANGUAGE, language).apply()
    }

    /**
     * Call from Application or Activity onCreate before setContentView.
     * If language is empty string → auto-detect from system.
     * If "ru" or "en" → force that locale.
     */
    fun applyLocale(context: Context): Context {
        val lang = getAppLanguage(context)
        val target = when (lang) {
            "ru" -> Locale("ru")
            "en" -> Locale("en")
            "" -> {
                // Auto-detect: if system locale is Russian → Russian, else English
                val system = getSystemLocale(context)
                if (system.language == "ru") Locale("ru") else Locale("en")
            }
            else -> Locale("en")
        }
        return updateConfiguration(context, target)
    }

    /**
     * Wraps an Activity context so the locale is applied dynamically.
     */
    fun wrapContext(baseContext: Context): Context {
        return applyLocale(baseContext)
    }

    private fun getSystemLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }

    private fun updateConfiguration(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        return context
    }
}
