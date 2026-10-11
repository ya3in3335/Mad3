package com.samtex.eidreader.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.data.scan.ConsoleOutcome
import com.samtex.eidreader.data.scan.ScanController
import com.samtex.eidreader.data.scan.ScanUiState
import com.samtex.eidreader.diagnostics.DiagnosticError
import com.samtex.eidreader.diagnostics.ErrorAnalyzer
import com.samtex.eidreader.domain.usecase.DiagnosticOperation
import com.samtex.eidreader.domain.usecase.ParseResponseUseCase
import com.samtex.eidreader.nfc.apdu.ApduParser
import com.samtex.eidreader.nfc.apdu.ApduPolicy
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.transport.ApduExchange
import com.samtex.eidreader.security.InputValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExchangeView(
    val label: String,
    val command: String,
    val commandInfo: String,
    val response: String,
    val status: String,
    val success: Boolean,
    val ascii: String?,
    val tlv: String?,
    val durationMs: Long,
    val warning: String?,
)

data class ConsoleEntry(
    val id: Long,
    val title: String,
    val exchanges: List<ExchangeView>,
    val error: DiagnosticError?,
)

data class DiagnosticsUiState(
    val authorized: Boolean = false,
    val running: Boolean = false,
    val customInput: String = "",
    val customError: String? = null,
    val decoderInput: String = "",
    val decoderOutput: String? = null,
    val entries: List<ConsoleEntry> = emptyList(),
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val controller: ScanController,
    private val parseResponse: ParseResponseUseCase,
    private val errorAnalyzer: ErrorAnalyzer,
) : ViewModel() {

    val scan: StateFlow<ScanUiState> = controller.state

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private var nextId = 0L

    fun setAuthorized(value: Boolean) = _state.update { it.copy(authorized = value) }

    fun setCustomInput(value: String) = _state.update {
        it.copy(customInput = value.take(InputValidator.MAX_INPUT_CHARS), customError = null)
    }

    fun setDecoderInput(value: String) = _state.update {
        it.copy(decoderInput = value.take(InputValidator.MAX_INPUT_CHARS), decoderOutput = null)
    }

    fun run(operation: DiagnosticOperation) {
        if (!_state.value.authorized || _state.value.running) return
        execute(operation.title) { controller.runOperation(operation) }
    }

    fun sendCustom() {
        val s = _state.value
        if (!s.authorized || s.running) return
        when (val parsed = InputValidator.parseCommandApdu(s.customInput)) {
            is InputValidator.Result.Invalid -> _state.update { it.copy(customError = parsed.reason) }
            is InputValidator.Result.Valid -> {
                val decision = ApduPolicy.evaluate(parsed.value)
                if (!decision.allowed) {
                    // Refused locally: nothing is transmitted to the card.
                    append(parsed.value.toHex(), ConsoleOutcome(emptyList(), errorAnalyzer.forKind(ErrorKind.POLICY_DENIED, decision.reason)))
                    return
                }
                execute(parsed.value.toHex()) { controller.runCustom(parsed.value) }
            }
        }
    }

    fun decode() {
        val output = when (val outcome = parseResponse(_state.value.decoderInput)) {
            is ParseResponseUseCase.Outcome.Invalid -> outcome.reason
            is ParseResponseUseCase.Outcome.Parsed -> buildString {
                val d = outcome.description
                appendLine("SW ${d.status}")
                appendLine("Category: ${d.status.category}")
                appendLine("Data: ${d.dataLength} bytes")
                if (d.dataHex.isNotEmpty()) appendLine(d.dataHex)
                d.ascii?.let { appendLine("ASCII: \"$it\"") }
                d.tlv?.let { appendLine("TLV:").appendLine(it) }
            }.trimEnd()
        }
        _state.update { it.copy(decoderOutput = output) }
    }

    fun clear() = _state.update { it.copy(entries = emptyList()) }

    private fun execute(title: String, block: suspend () -> ConsoleOutcome) {
        _state.update { it.copy(running = true) }
        viewModelScope.launch {
            val outcome = block()
            append(title, outcome)
            _state.update { it.copy(running = false) }
        }
    }

    private fun append(title: String, outcome: ConsoleOutcome) {
        val entry = ConsoleEntry(nextId++, title, outcome.exchanges.map(::toView), outcome.error)
        _state.update { it.copy(entries = listOf(entry) + it.entries.take(MAX_ENTRIES - 1)) }
    }

    private fun toView(e: ApduExchange): ExchangeView {
        val cmd = ApduParser.describe(e.command)
        val rsp = ApduParser.describe(e.response)
        return ExchangeView(
            label = e.label,
            command = cmd.hex,
            commandInfo = "${cmd.instruction} · ${cmd.case.label}" + if (cmd.notes.isNotEmpty()) " · " + cmd.notes.joinToString("; ") else "",
            response = if (rsp.dataLength == 0) Hex.u16(e.response.sw) else rsp.hex,
            status = rsp.status.toString(),
            success = rsp.status.isSuccess,
            ascii = rsp.ascii,
            tlv = rsp.tlv,
            durationMs = e.durationMs,
            warning = e.lengthWarning,
        )
    }

    private companion object {
        const val MAX_ENTRIES = 50
    }
}
