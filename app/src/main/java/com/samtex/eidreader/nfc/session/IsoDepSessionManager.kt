package com.samtex.eidreader.nfc.session

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.transport.ApduChannel
import com.samtex.eidreader.nfc.transport.ApduTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

data class SessionConfig(
    /** ISO-DEP transceive timeout applied with IsoDep.setTimeout(). */
    val transceiveTimeoutMs: Int = 2_000,
    /** Upper bound for a complete session (connect + all exchanges). */
    val sessionTimeoutMs: Long = 15_000,
)

/**
 * Owns the lifecycle of ISO-DEP sessions.
 *
 * Guarantees:
 * - At most one communication session at a time (a second request fails fast with
 *   [ErrorKind.SESSION_BUSY] instead of interleaving APDUs on the same card).
 * - The connection is always closed, whatever the outcome (success, error, timeout, cancellation).
 * - A session never exceeds [SessionConfig.sessionTimeoutMs].
 */
@Singleton
class IsoDepSessionManager @Inject constructor() {

    private val mutex = Mutex()

    var config: SessionConfig = SessionConfig()

    val isBusy: Boolean get() = mutex.isLocked

    suspend fun <T> withSession(
        transport: ApduTransport,
        logger: SessionLogger?,
        clock: Clock = Clock.SYSTEM,
        sessionConfig: SessionConfig = config,
        block: suspend (ApduChannel) -> T,
    ): T {
        if (!mutex.tryLock()) {
            throw NfcEngineException(ErrorKind.SESSION_BUSY, "Another card session is already running")
        }
        try {
            return withTimeout(sessionConfig.sessionTimeoutMs) {
                logger?.lifecycle("Connecting ISO-DEP (timeout ${sessionConfig.transceiveTimeoutMs} ms)")
                transport.connect(sessionConfig.transceiveTimeoutMs)
                logger?.lifecycle(
                    "ISO-DEP connected: max transceive ${transport.maxTransceiveLength} bytes, " +
                        "extended length ${if (transport.isExtendedLengthSupported) "supported" else "not supported"}",
                )
                block(ApduChannel(transport, logger, clock))
            }
        } catch (e: TimeoutCancellationException) {
            throw NfcEngineException(ErrorKind.TIMEOUT, "Session exceeded ${sessionConfig.sessionTimeoutMs} ms", e)
        } finally {
            transport.close()
            logger?.lifecycle("ISO-DEP connection closed")
            mutex.unlock()
        }
    }
}
