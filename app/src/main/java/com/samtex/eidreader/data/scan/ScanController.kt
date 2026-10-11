package com.samtex.eidreader.data.scan

import android.nfc.Tag
import com.samtex.eidreader.core.di.ApplicationScope
import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.data.history.HistoryRepository
import com.samtex.eidreader.data.settings.SettingsRepository
import com.samtex.eidreader.diagnostics.DiagnosticError
import com.samtex.eidreader.diagnostics.ErrorAnalyzer
import com.samtex.eidreader.diagnostics.LogEntry
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.domain.model.ScanOptions
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.domain.model.ScanResult
import com.samtex.eidreader.domain.usecase.DiagnosticOperation
import com.samtex.eidreader.domain.usecase.RunDiagnosticUseCase
import com.samtex.eidreader.domain.usecase.ScanCardUseCase
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.discovery.NfcAvailability
import com.samtex.eidreader.nfc.discovery.NfcDiscoveryManager
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.tag.TagSnapshotReader
import com.samtex.eidreader.nfc.transport.ApduExchange
import com.samtex.eidreader.security.PrivacyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

data class ScanUiState(
    val phase: ScanPhase,
    val nfc: NfcAvailability,
    val result: ScanResult? = null,
    val error: DiagnosticError? = null,
    /** True while a card is available for the diagnostics console. */
    val cardAvailable: Boolean = false,
    val cardLabel: String? = null,
)

data class ConsoleOutcome(val exchanges: List<ApduExchange>, val error: DiagnosticError?)

/**
 * Application-wide coordinator between Reader Mode callbacks, the scan use case, history and
 * the diagnostics console. Guarantees that only one card operation runs at a time.
 */
@Singleton
class ScanController @Inject constructor(
    private val discovery: NfcDiscoveryManager,
    private val snapshotReader: TagSnapshotReader,
    private val scanCard: ScanCardUseCase,
    private val runDiagnostic: RunDiagnosticUseCase,
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val errorAnalyzer: ErrorAnalyzer,
    private val privacy: PrivacyManager,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val busy = AtomicBoolean(false)

    @Volatile
    private var currentCard: DiscoveredCard? = null

    private val _state = MutableStateFlow(ScanUiState(phaseFor(discovery.availability.value), discovery.availability.value))
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private val consoleLogger = SessionLogger(clock, privacy)

    init {
        scope.launch {
            discovery.availability.collect { availability -> onAvailability(availability) }
        }
    }

    private fun phaseFor(availability: NfcAvailability): ScanPhase = when (availability) {
        NfcAvailability.UNSUPPORTED -> ScanPhase.NFC_UNAVAILABLE
        NfcAvailability.DISABLED -> ScanPhase.NFC_DISABLED
        NfcAvailability.ENABLED -> ScanPhase.WAITING
    }

    private fun onAvailability(availability: NfcAvailability) {
        _state.update { s ->
            val idlePhases = setOf(ScanPhase.WAITING, ScanPhase.NFC_DISABLED, ScanPhase.NFC_UNAVAILABLE)
            val phase = when {
                availability != NfcAvailability.ENABLED && !busy.get() -> phaseFor(availability)
                s.phase in idlePhases -> phaseFor(availability)
                else -> s.phase
            }
            if (availability != NfcAvailability.ENABLED) currentCard = null
            s.copy(nfc = availability, phase = phase, cardAvailable = currentCard != null)
        }
    }

    /** Called on the NFC binder thread by Reader Mode. */
    fun onTagDiscovered(tag: Tag) {
        // Never start a second session while one is running (scan or console).
        if (!busy.compareAndSet(false, true)) return
        val card = try {
            AndroidDiscoveredCard(tag, snapshotReader.read(tag))
        } catch (e: RuntimeException) {
            busy.set(false)
            _state.update { it.copy(phase = ScanPhase.ERROR, error = errorAnalyzer.analyze(e)) }
            return
        }
        currentCard = card
        scope.launch(Dispatchers.IO) {
            try {
                performScan(card)
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun performScan(card: DiscoveredCard) {
        _state.update { it.copy(phase = ScanPhase.CARD_DETECTED, result = null, error = null, cardAvailable = true, cardLabel = card.snapshot.technologies.joinToString { t -> t.label }) }
        val options = ScanOptions(publicProbe = settings.settings.value.publicProbe)
        val result = try {
            scanCard.execute(card, options) { phase -> _state.update { it.copy(phase = phase) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = ScanPhase.ERROR, error = errorAnalyzer.analyze(e)) }
            return
        }
        val phase = when {
            result.error == null -> ScanPhase.COMPLETED
            result.error.kind.isConnectionLoss -> ScanPhase.CONNECTION_LOST
            else -> ScanPhase.ERROR
        }
        if (phase == ScanPhase.CONNECTION_LOST) currentCard = null
        _state.update { it.copy(phase = phase, result = result, error = result.error, cardAvailable = currentCard != null) }
        try {
            history.saveIfConsented(result)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // History is optional; a storage failure must not affect the scan result.
        }
    }

    /** Clears the current result and waits for the next card. */
    fun scanAgain() {
        currentCard = null
        _state.update { ScanUiState(phaseFor(it.nfc), it.nfc) }
    }

    suspend fun runOperation(operation: DiagnosticOperation): ConsoleOutcome =
        withCard { card -> runDiagnostic.run(card, operation, consoleLogger) }

    suspend fun runCustom(command: CommandApdu): ConsoleOutcome =
        withCard { card -> listOf(runDiagnostic.runCustom(card, command, consoleLogger)) }

    fun consoleLog(): List<LogEntry> = consoleLogger.snapshot()

    private suspend fun withCard(block: suspend (DiscoveredCard) -> List<ApduExchange>): ConsoleOutcome {
        val card = currentCard
            ?: return ConsoleOutcome(emptyList(), errorAnalyzer.forKind(ErrorKind.NOT_CONNECTED, "No card detected"))
        if (!busy.compareAndSet(false, true)) {
            return ConsoleOutcome(emptyList(), errorAnalyzer.forKind(ErrorKind.SESSION_BUSY))
        }
        return try {
            ConsoleOutcome(block(card), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = errorAnalyzer.analyze(e)
            if (e is NfcEngineException && e.kind.isConnectionLoss) {
                currentCard = null
                _state.update { it.copy(cardAvailable = false) }
            }
            ConsoleOutcome(emptyList(), error)
        } finally {
            busy.set(false)
        }
    }
}
