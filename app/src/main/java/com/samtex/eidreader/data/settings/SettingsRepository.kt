package com.samtex.eidreader.data.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** Explicit consent to store scan history. Off by default. */
    val historyConsent: Boolean = false,
    /** Redact identifiers in the UI by default. */
    val redactIdentifiers: Boolean = true,
    /** Run documented public probes after detection. */
    val publicProbe: Boolean = true,
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load() = AppSettings(
        historyConsent = prefs.getBoolean(KEY_HISTORY, false),
        redactIdentifiers = prefs.getBoolean(KEY_REDACT, true),
        publicProbe = prefs.getBoolean(KEY_PROBE, true),
    )

    fun setHistoryConsent(value: Boolean) = write(KEY_HISTORY, value)
    fun setRedactIdentifiers(value: Boolean) = write(KEY_REDACT, value)
    fun setPublicProbe(value: Boolean) = write(KEY_PROBE, value)

    private fun write(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
        _settings.value = load()
    }

    private companion object {
        const val PREFS = "samtex_settings"
        const val KEY_HISTORY = "history_consent"
        const val KEY_REDACT = "redact_identifiers"
        const val KEY_PROBE = "public_probe"
    }
}
