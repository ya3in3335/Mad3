package com.samtex.eidreader.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samtex.eidreader.data.history.HistoryItem
import com.samtex.eidreader.data.history.HistoryRepository
import com.samtex.eidreader.data.settings.AppSettings
import com.samtex.eidreader.data.settings.SettingsRepository
import com.samtex.eidreader.data.trust.ImportResult
import com.samtex.eidreader.data.trust.TrustAnchorInfo
import com.samtex.eidreader.data.trust.TrustAnchorStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Report text shown in a dialog; null text means it could not be decrypted. */
data class OpenReport(val id: String, val text: String?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val history: HistoryRepository,
    private val trustAnchors: TrustAnchorStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val anchors: StateFlow<List<TrustAnchorInfo>> = trustAnchors.infos

    private val _importResult = MutableStateFlow<ImportResult?>(null)
    val importResult: StateFlow<ImportResult?> = _importResult.asStateFlow()

    fun importCertificate(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    // CSCA certificates are a few KiB; refuse anything unreasonably large.
                    val data = input.readNBytesCompat(MAX_CERT_FILE)
                    data
                }
            } catch (_: Exception) {
                null
            }
            _importResult.value = if (bytes == null) ImportResult.NotACertificate else trustAnchors.import(bytes)
        }
    }

    fun dismissImportResult() {
        _importResult.value = null
    }

    fun removeAnchor(fingerprint: String) = trustAnchors.remove(fingerprint)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val historyItems: StateFlow<List<HistoryItem>> =
        history.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openReport = MutableStateFlow<OpenReport?>(null)
    val openReport: StateFlow<OpenReport?> = _openReport.asStateFlow()

    fun setRedact(value: Boolean) = settingsRepository.setRedactIdentifiers(value)

    fun setPublicProbe(value: Boolean) = settingsRepository.setPublicProbe(value)

    /** Called only after the user accepted the consent dialog. */
    fun grantHistoryConsent() = settingsRepository.setHistoryConsent(true)

    /** Withdrawing consent deletes all stored history and its encryption key. */
    fun revokeHistoryConsent() {
        settingsRepository.setHistoryConsent(false)
        viewModelScope.launch { history.clear() }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    fun open(id: String) {
        viewModelScope.launch { _openReport.value = OpenReport(id, history.loadReport(id)) }
    }

    fun closeReport() {
        _openReport.value = null
    }

    private companion object {
        const val MAX_CERT_FILE = 256 * 1024
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        if (out.size() > limit) return null
    }
    return out.toByteArray()
}
