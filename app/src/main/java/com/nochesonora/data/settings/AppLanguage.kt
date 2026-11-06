package com.nochesonora.data.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's language, chosen in Settings (Spanish by default).
 *
 * Stored in SharedPreferences rather than DataStore because it must be read synchronously in
 * `attachBaseContext`, before the activity or service exists. Changing it recreates the activity.
 */
enum class Language(val tag: String) { ES("es"), EN("en") }

object AppLanguage {
    private const val PREFS = "app_language"
    private const val KEY = "language"

    fun get(context: Context): Language {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Language.entries.firstOrNull { it.tag == stored } ?: Language.ES
    }

    fun set(context: Context, language: Language) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
    }

    /** Context with the chosen language; also sets the default `Locale` (dates and numbers). */
    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(get(context).tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
