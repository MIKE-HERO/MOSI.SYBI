package com.sybi.mosi.helpers

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

object LocaleHelper {

    private const val PREF_KEY_LANGUAGE = "AppLanguage"
    const val LANG_ES = "es"
    const val LANG_EN = "en"

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_LANGUAGE, LANG_ES) ?: LANG_ES
    }

    fun setLanguage(context: Context, language: String): Context {
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit {
            putString(PREF_KEY_LANGUAGE, language)
        }
        return updateResources(context, language)
    }

    fun applyLanguage(context: Context): Context {
        val lang = getLanguage(context)
        return updateResources(context, lang)
    }

    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)

        val resources = context.resources
        val config = Configuration(resources.configuration)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            config.setLocales(localeList)
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
            context
        }
    }
}
