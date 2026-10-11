package com.samtex.eidreader.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samtex.eidreader.R
import com.samtex.eidreader.data.scan.ScanController
import com.samtex.eidreader.data.scan.ScanUiState
import com.samtex.eidreader.data.settings.AppSettings
import com.samtex.eidreader.data.settings.SettingsRepository
import com.samtex.eidreader.diagnostics.ReportFormat
import com.samtex.eidreader.diagnostics.ReportOptions
import com.samtex.eidreader.domain.usecase.ExportReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Shared by the home screen and the technical details screen. */
@HiltViewModel
class ScanViewModel @Inject constructor(
    private val controller: ScanController,
    settingsRepository: SettingsRepository,
    private val exportReport: ExportReportUseCase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val state: StateFlow<ScanUiState> = controller.state
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = _messages.receiveAsFlow()

    fun scanAgain() = controller.scanAgain()

    fun suggestedFileName(format: ReportFormat): String {
        val result = state.value.result ?: return "SAMTEX-report.${format.extension}"
        return exportReport.suggestedFileName(result, ReportOptions(format))
    }

    fun export(uri: Uri, options: ReportOptions) {
        val result = state.value.result ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
                stream.use { exportReport.export(result, options, it) }
                R.string.export_success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                R.string.export_failed
            }
            _messages.send(message)
        }
    }
}
