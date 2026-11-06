package com.nochesonora.data.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Idioma de la app, elegido en Ajustes (español por defecto).
 *
 * Se guarda en SharedPreferences y no en DataStore porque hay que leerlo de forma síncrona en
 * `attachBaseContext`, antes de que exista la actividad o el servicio. Cambiarlo recrea la actividad.
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

    /** Contexto con el idioma elegido; también fija el `Locale` por defecto (fechas y números). */
    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(get(context).tag)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
