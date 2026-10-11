package com.samtex.eidreader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samtex.eidreader.data.history.HistoryItem
import com.samtex.eidreader.data.history.HistoryRepository
import com.samtex.eidreader.data.settings.AppSettings
import com.samtex.eidreader.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
) : ViewModel() {

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
}
