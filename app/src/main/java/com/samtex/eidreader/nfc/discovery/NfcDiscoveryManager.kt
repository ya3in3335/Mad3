package com.samtex.eidreader.nfc.discovery

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class NfcAvailability { UNSUPPORTED, DISABLED, ENABLED }

/**
 * Owns Android Reader Mode and tracks the NFC adapter state.
 *
 * Reader Mode (rather than foreground dispatch) is used because it:
 * - disables card emulation and peer-to-peer while SAMTEX is in the foreground,
 * - lets SAMTEX skip the platform NDEF check, so no command reaches the card unless SAMTEX sends it,
 * - delivers tags on a binder thread without restarting the activity.
 */
@Singleton
class NfcDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    private val _availability = MutableStateFlow(currentAvailability())
    val availability: StateFlow<NfcAvailability> = _availability.asStateFlow()

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) refresh()
        }
    }

    init {
        if (adapter != null) {
            ContextCompat.registerReceiver(
                context,
                stateReceiver,
                IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun refresh() {
        _availability.value = currentAvailability()
    }

    private fun currentAvailability(): NfcAvailability = when {
        adapter == null -> NfcAvailability.UNSUPPORTED
        isEnabledSafe() -> NfcAvailability.ENABLED
        else -> NfcAvailability.DISABLED
    }

    private fun isEnabledSafe(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: RuntimeException) {
        false
    }

    /**
     * Enables Reader Mode for [activity] (which must be resumed).
     * @return false when NFC is unavailable or the platform refused.
     */
    fun enableReaderMode(activity: Activity, onTag: (Tag) -> Unit): Boolean {
        val nfc = adapter ?: return false
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, PRESENCE_CHECK_DELAY_MS) }
        return try {
            nfc.enableReaderMode(activity, { tag -> onTag(tag) }, READER_FLAGS, extras)
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    fun disableReaderMode(activity: Activity) {
        try {
            adapter?.disableReaderMode(activity)
        } catch (_: RuntimeException) {
            // Activity no longer in the foreground or NFC service restarting: nothing to undo.
        }
    }

    companion object {
        const val PRESENCE_CHECK_DELAY_MS = 250
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    }
}
