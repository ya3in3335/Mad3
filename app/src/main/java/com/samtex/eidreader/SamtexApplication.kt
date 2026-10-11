package com.samtex.eidreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.samtex.eidreader.core.crypto.CryptoProviders
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SamtexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CryptoProviders.install()
        applyDefaultLanguage()
    }

    /** Arabic is the default interface language on first launch; the user can change it in Settings. */
    private fun applyDefaultLanguage() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_LOCALE_INITIALIZED, false)) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
            }
            prefs.edit().putBoolean(KEY_LOCALE_INITIALIZED, true).apply()
        }
    }

    private companion object {
        const val PREFS = "samtex_app"
        const val KEY_LOCALE_INITIALIZED = "locale_initialized_v1"
    }
}
