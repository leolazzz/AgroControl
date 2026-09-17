package com.example.agrocontrol

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import com.example.agrocontrol.model.ControlData

object AppPrefs {
    private const val PREFS = "agrocontrol_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_MODE_CLOUDY = "control_mode_cloudy"
    private const val KEY_RELAY_1 = "control_relay_1"
    private const val KEY_RELAY_2 = "control_relay_2"
    private const val KEY_RELAY_3 = "control_relay_3"
    private const val KEY_EC_ENABLED = "control_ec_enabled"
    private const val KEY_PH_ENABLED = "control_ph_enabled"
    private const val KEY_LAST_PLANT = "last_plant"
    private const val KEY_LAST_STAGE = "last_stage"

    private const val DEFAULT_BASE_URL = "http://192.168.1.107:5000"
    private const val DEFAULT_THEME_MODE = "system"

    fun getBaseUrl(context: Context): String {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, DEFAULT_BASE_URL)
            ?.trim()
            .orEmpty()
        return normalizeBaseUrl(raw)
    }

    fun setBaseUrl(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, normalizeBaseUrl(value))
            .apply()
    }

    fun getThemeMode(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, DEFAULT_THEME_MODE)
            ?.trim()
            ?.lowercase()
            ?: DEFAULT_THEME_MODE
    }

    fun setThemeMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.trim().lowercase())
            .apply()
    }

    fun applyTheme(context: Context) {
        when (getThemeMode(context)) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    fun getControlState(context: Context): ControlData {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ControlData(
            mode = if (prefs.getBoolean(KEY_MODE_CLOUDY, false)) "пасмурно" else "ясно",
            relay1 = prefs.getBoolean(KEY_RELAY_1, false),
            relay2 = prefs.getBoolean(KEY_RELAY_2, false),
            relay3 = prefs.getBoolean(KEY_RELAY_3, false),
            ec_onoff = prefs.getBoolean(KEY_EC_ENABLED, true),
            ph_onoff = prefs.getBoolean(KEY_PH_ENABLED, true)
        )
    }

    fun setControlState(context: Context, state: ControlData) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            state.mode?.let { putBoolean(KEY_MODE_CLOUDY, it == "пасмурно") }
            state.relay1?.let { putBoolean(KEY_RELAY_1, it) }
            state.relay2?.let { putBoolean(KEY_RELAY_2, it) }
            state.relay3?.let { putBoolean(KEY_RELAY_3, it) }
            state.ec_onoff?.let { putBoolean(KEY_EC_ENABLED, it) }
            state.ph_onoff?.let { putBoolean(KEY_PH_ENABLED, it) }
        }.apply()
    }

    fun getLastPlant(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_PLANT, "томат") ?: "томат"

    fun getLastStage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_STAGE, "рост") ?: "рост"

    fun setPlantContext(context: Context, plant: String, stage: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_PLANT, plant.trim())
            .putString(KEY_LAST_STAGE, stage.trim())
            .apply()
    }

    fun normalizeBaseUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) url = DEFAULT_BASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        if (!url.endsWith("/")) url += "/"

        return try {
            val parsedUri = Uri.parse(url)
            if (parsedUri != null &&
                parsedUri.scheme?.startsWith("http") == true &&
                parsedUri.host?.isNotEmpty() == true &&
                (parsedUri.port == -1 || (parsedUri.port > 0 && parsedUri.port <= 65535))
            ) {
                url
            } else {
                DEFAULT_BASE_URL
            }
        } catch (e: Exception) {
            DEFAULT_BASE_URL
        }
    }

    fun isValidBaseUrl(input: String): Boolean = try {
        val raw = input.trim()
        val url = if (raw.contains("://")) raw else "http://$raw"
        val uri = java.net.URI(url)
        raw.isNotEmpty() && uri.scheme in listOf("http", "https") &&
            !uri.host.isNullOrEmpty() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    } catch (_: Exception) { false }
}
