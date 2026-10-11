package com.samtex.eidreader.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.samtex.eidreader.data.scan.ScanController
import com.samtex.eidreader.nfc.discovery.NfcAvailability
import com.samtex.eidreader.nfc.discovery.NfcDiscoveryManager
import com.samtex.eidreader.ui.navigation.SamtexNavHost
import com.samtex.eidreader.ui.theme.SamtexTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var discovery: NfcDiscoveryManager

    @Inject lateinit var controller: ScanController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SamtexTheme {
                SamtexNavHost(onOpenNfcSettings = ::openNfcSettings)
            }
        }

        // Reader Mode is only active while the activity is resumed, and is re-enabled
        // automatically when the user turns NFC on while SAMTEX is in the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                discovery.refresh()
                try {
                    discovery.availability.collect { availability ->
                        if (availability == NfcAvailability.ENABLED) {
                            discovery.enableReaderMode(this@MainActivity, controller::onTagDiscovered)
                        } else {
                            discovery.disableReaderMode(this@MainActivity)
                        }
                    }
                } finally {
                    discovery.disableReaderMode(this@MainActivity)
                }
            }
        }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }
}
