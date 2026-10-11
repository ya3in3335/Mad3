package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.diagnostics.DiagnosticError
import com.samtex.eidreader.diagnostics.ErrorAnalyzer
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.domain.model.IsoDepSessionReport
import com.samtex.eidreader.domain.model.IsoDepStatus
import com.samtex.eidreader.domain.model.ScanOptions
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.domain.model.ScanResult
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.error.NfcErrorHandler
import com.samtex.eidreader.nfc.protocol.CardProtocolAdapter
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.nfc.protocol.ProtocolAdapterRegistry
import com.samtex.eidreader.nfc.protocol.ProtocolProbeResult
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.nfc.transport.ApduChannel
import com.samtex.eidreader.security.PrivacyManager
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject

/**
 * Runs one complete scan of a discovered card:
 * 1. passive metadata from the Android NFC stack (no communication),
 * 2. ISO-DEP connection check (only if the tag supports IsoDep),
 * 3. optional documented public probes (ICAO EF.CardAccess, eMRTD AID),
 * 4. classification with verified/inferred provenance.
 *
 * Communication failures never discard what was already learned: a partial result with an
 * explicit [DiagnosticError] is returned instead.
 */
class ScanCardUseCase @Inject constructor(
    private val sessionManager: IsoDepSessionManager,
    private val identify: IdentifyCardUseCase,
    private val adapters: ProtocolAdapterRegistry,
    private val errorAnalyzer: ErrorAnalyzer,
    private val errorHandler: NfcErrorHandler,
    private val privacy: PrivacyManager,
    private val clock: Clock,
) {

    suspend fun execute(
        card: DiscoveredCard,
        options: ScanOptions,
        onPhase: (ScanPhase) -> Unit = {},
    ): ScanResult {
        val start = clock.nowMs()
        val logger = SessionLogger(clock, privacy)
        val snapshot = card.snapshot
        onPhase(ScanPhase.CARD_DETECTED)
        logger.lifecycle("Card detected: ${snapshot.technologies.joinToString { it.label }.ifEmpty { "no known technology" }}")

        val probes = mutableListOf<ProtocolProbeResult>()
        var error: DiagnosticError? = null
        var isoReport = IsoDepSessionReport(IsoDepStatus.NOT_SUPPORTED)

        val transport = if (snapshot.isoDep != null) card.openIsoDepTransport() else null
        if (transport == null) {
            logger.lifecycle("ISO-DEP not available: reporting passive metadata only")
        } else {
            onPhase(ScanPhase.CONNECTING)
            var channelRef: ApduChannel? = null
            try {
                sessionManager.withSession(transport, logger, clock) { channel ->
                    channelRef = channel
                    isoReport = IsoDepSessionReport(
                        status = IsoDepStatus.ESTABLISHED,
                        maxTransceiveLength = channel.maxTransceiveLength,
                        extendedLengthSupported = channel.isExtendedLengthSupported,
                        transceiveTimeoutMs = sessionManager.config.transceiveTimeoutMs,
                    )
                    onPhase(ScanPhase.READING)
                    if (options.publicProbe) {
                        for (adapter in adapters.applicableTo(snapshot)) {
                            probes += runAdapter(adapter, channel, logger)
                        }
                    } else {
                        logger.lifecycle("Public probe disabled in settings: no APDU sent")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorAnalyzer.analyze(e)
                val lost = errorHandler.isConnectionLoss(e)
                isoReport = isoReport.copy(status = if (lost) IsoDepStatus.CONNECTION_LOST else IsoDepStatus.FAILED)
                logger.error("${error.kind}: ${error.title}")
            }
            isoReport = isoReport.copy(apduCount = channelRef?.exchangeCount ?: 0)
        }

        val classification = identify(snapshot, probes)
        logger.lifecycle("Classified as: ${classification.family.label}")
        return ScanResult(
            id = UUID.randomUUID().toString(),
            timestampMs = start,
            durationMs = clock.nowMs() - start,
            snapshot = snapshot,
            classification = classification,
            isoDep = isoReport,
            probes = probes,
            log = logger.snapshot(),
            error = error,
            options = options,
        )
    }

    private suspend fun runAdapter(adapter: CardProtocolAdapter, channel: ApduChannel, logger: SessionLogger): ProtocolProbeResult {
        logger.lifecycle("Probe: ${adapter.name} (${adapter.reference})")
        return try {
            adapter.probe(channel).also { logger.lifecycle("Probe result: ${it.status} — ${it.summary}") }
        } catch (e: NfcEngineException) {
            // Losing the card ends the whole session; other failures only affect this adapter.
            if (e.kind.isConnectionLoss || e.kind == ErrorKind.SESSION_BUSY) throw e
            logger.warning("Probe ${adapter.id} failed: ${e.kind}")
            ProtocolProbeResult(adapter.id, adapter.name, adapter.reference, ProbeStatus.ERROR, errorAnalyzer.analyze(e).title)
        }
    }
}
