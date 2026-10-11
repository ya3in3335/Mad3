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
import com.samtex.eidreader.domain.usecase.ReadIdentityUseCase
import com.samtex.eidreader.domain.usecase.RunDiagnosticUseCase
import com.samtex.eidreader.icao.IdentityReadResult
import com.samtex.eidreader.icao.ReadOutcome
import com.samtex.eidreader.icao.ReadStep
import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.reader.ReadOptions
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class IdentityPhase { IDLE, WAITING_FOR_CARD, READING, FINISHED }

/** Identity session state. Personal data stays hidden until [revealed] is set by the holder. */
data class IdentityUiState(
    val phase: IdentityPhase = IdentityPhase.IDLE,
    val step: ReadStep? = null,
    val result: IdentityReadResult? = null,
    val revealed: Boolean = false,
    val portraitRevealed: Boolean = false,
)

private class PendingRead(val credentials: AccessCredentials, val options: ReadOptions, var retriedAfterRemoval: Boolean = false)

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
    private val readIdentity: ReadIdentityUseCase,
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

    private val _identity = MutableStateFlow(IdentityUiState())
    val identity: StateFlow<IdentityUiState> = _identity.asStateFlow()

    @Volatile
    private var pending: PendingRead? = null
    private var pendingExpiry: Job? = null

    @Volatile
    private var identityJob: Job? = null

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
        // Never start a second session while one is running (scan, identity read or console).
        if (!busy.compareAndSet(false, true)) return
        val card = try {
            AndroidDiscoveredCard(tag, snapshotReader.read(tag))
        } catch (e: RuntimeException) {
            busy.set(false)
            _state.update { it.copy(phase = ScanPhase.ERROR, error = errorAnalyzer.analyze(e)) }
            return
        }
        currentCard = card
        val identityRequested = pending != null
        val job = scope.launch(Dispatchers.IO) {
            try {
                if (identityRequested) performIdentityRead(card, cardWasAlreadyPresent = false) else performScan(card)
            } finally {
                busy.set(false)
            }
        }
        if (identityRequested) identityJob = job
    }

    // ---- Identity reading -------------------------------------------------------------------

    /**
     * Starts an authorized identity read. If a card is already in the field it is used at once;
     * otherwise the next detected card is read. Unused credentials are wiped after [PENDING_TTL_MS].
     */
    fun requestIdentityRead(credentials: AccessCredentials, options: ReadOptions) {
        endIdentitySession()
        when (_state.value.nfc) {
            NfcAvailability.UNSUPPORTED -> {
                credentials.close()
                _identity.value = IdentityUiState(IdentityPhase.FINISHED, result = IdentityReadResult.failure(ReadOutcome.NFC_NOT_AVAILABLE))
                return
            }
            NfcAvailability.DISABLED -> {
                credentials.close()
                _identity.value = IdentityUiState(IdentityPhase.FINISHED, result = IdentityReadResult.failure(ReadOutcome.NFC_DISABLED))
                return
            }
            NfcAvailability.ENABLED -> Unit
        }
        pending = PendingRead(credentials, options)
        _identity.value = IdentityUiState(IdentityPhase.WAITING_FOR_CARD)
        pendingExpiry = scope.launch {
            delay(PENDING_TTL_MS)
            cancelIdentityRead()
        }
        val card = currentCard ?: return
        if (busy.compareAndSet(false, true)) {
            identityJob = scope.launch(Dispatchers.IO) {
                try {
                    performIdentityRead(card, cardWasAlreadyPresent = true)
                } finally {
                    busy.set(false)
                }
            }
        }
    }

    private suspend fun performIdentityRead(card: DiscoveredCard, cardWasAlreadyPresent: Boolean) {
        val request = pending ?: return
        _identity.value = IdentityUiState(IdentityPhase.READING, ReadStep.CONNECTING)
        val result = try {
            readIdentity(card, request.credentials, request.options) { step -> _identity.update { it.copy(step = step) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            IdentityReadResult.failure(ReadOutcome.PROTOCOL_UNSUPPORTED, e.javaClass.simpleName)
        }
        // A handle to a card that was lifted in the meantime is stale: wait once for a fresh tap.
        if (result.outcome == ReadOutcome.CARD_REMOVED && cardWasAlreadyPresent && !request.retriedAfterRemoval) {
            request.retriedAfterRemoval = true
            currentCard = null
            _identity.value = IdentityUiState(IdentityPhase.WAITING_FOR_CARD)
            return
        }
        if (pending !== request) {
            // The session was ended or replaced while reading: discard the result.
            result.record?.wipe()
            return
        }
        pending = null
        pendingExpiry?.cancel()
        request.credentials.close()
        _identity.value = IdentityUiState(IdentityPhase.FINISHED, result = result)
    }

    fun revealIdentity(reveal: Boolean) = _identity.update { it.copy(revealed = reveal, portraitRevealed = if (reveal) it.portraitRevealed else false) }

    fun revealPortrait(reveal: Boolean) = _identity.update { it.copy(portraitRevealed = reveal && it.revealed) }

    /** Abandons a pending or running read and wipes the credentials. */
    fun cancelIdentityRead() {
        identityJob?.cancel()
        identityJob = null
        pending?.credentials?.close()
        pending = null
        pendingExpiry?.cancel()
        if (_identity.value.phase == IdentityPhase.WAITING_FOR_CARD || _identity.value.phase == IdentityPhase.READING) {
            _identity.value = IdentityUiState()
        }
    }

    /** Ends the session: wipes credentials, personal data and the portrait from memory. */
    fun endIdentitySession() {
        identityJob?.cancel()
        identityJob = null
        pending?.credentials?.close()
        pending = null
        pendingExpiry?.cancel()
        _identity.value.result?.record?.wipe()
        _identity.value = IdentityUiState()
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

    private companion object {
        const val PENDING_TTL_MS = 120_000L
    }

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
