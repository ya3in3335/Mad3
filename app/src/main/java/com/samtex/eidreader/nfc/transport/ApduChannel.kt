package com.samtex.eidreader.nfc.transport

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.ApduValidator
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.apdu.ResponseApdu
import com.samtex.eidreader.nfc.apdu.StatusWordInfo
import com.samtex.eidreader.nfc.apdu.StatusWords
import com.samtex.eidreader.nfc.apdu.ValidationIssue
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import java.io.ByteArrayOutputStream

/** Result of one logical command exchange (including any GET RESPONSE / Le correction). */
data class ApduExchange(
    val label: String,
    val command: CommandApdu,
    val response: ResponseApdu,
    val status: StatusWordInfo,
    val durationMs: Long,
    val transmissions: Int,
    val lengthWarning: String?,
    val publicData: Boolean,
)

/**
 * ISO/IEC 7816-4 command/response channel on top of an [ApduTransport].
 *
 * - Validates every command against ISO rules and the transport limits before sending.
 * - Handles 6CXX (wrong Le → resend once with the exact length) and 61XX
 *   (GET RESPONSE loop, bounded by [maxResponseChunks]).
 * - Validates response lengths and records every exchange in the [SessionLogger].
 */
class ApduChannel(
    private val transport: ApduTransport,
    private val logger: SessionLogger?,
    private val clock: Clock = Clock.SYSTEM,
    private val maxResponseChunks: Int = 32,
) {
    val maxTransceiveLength: Int get() = transport.maxTransceiveLength
    val isExtendedLengthSupported: Boolean get() = transport.isExtendedLengthSupported

    /** Largest Ne that comfortably fits the transport buffer for a short READ BINARY. */
    val maxShortReadLength: Int
        get() = (transport.maxTransceiveLength - 2).coerceIn(1, MAX_READ_CHUNK)

    private val _history = mutableListOf<ApduExchange>()

    /** Every completed exchange of this channel, in order. */
    val history: List<ApduExchange> get() = _history.toList()

    val exchangeCount: Int get() = _history.size

    suspend fun transmit(command: CommandApdu, label: String, publicData: Boolean): ApduExchange {
        val issues = ApduValidator.validate(command, transport.limits)
        if (ApduValidator.hasErrors(issues)) {
            val reason = issues.first { it.severity == ValidationIssue.Severity.ERROR }.message
            val kind = if (command.isExtended && !transport.isExtendedLengthSupported) {
                ErrorKind.EXTENDED_LENGTH_UNSUPPORTED
            } else if (command.encode().size > transport.maxTransceiveLength) {
                ErrorKind.PAYLOAD_TOO_LARGE
            } else {
                ErrorKind.INVALID_INPUT
            }
            logger?.warning("Command '$label' rejected before transmission: $reason")
            throw NfcEngineException(kind, reason)
        }

        val start = clock.nowMs()
        var transmissions = 0
        var current = command
        var raw = send(current).also { transmissions++ }

        // 6CXX: wrong Le, resend once with the exact length indicated by the card.
        if (raw.sw1 == 0x6C && current.ne != null) {
            val exact = if (raw.sw2 == 0) CommandApdu.MAX_SHORT_NE else raw.sw2
            current = current.withNe(exact)
            raw = send(current).also { transmissions++ }
        }

        // 61XX: more data available, fetch it with GET RESPONSE.
        var response = raw
        if (raw.sw1 == 0x61) {
            val buffer = ByteArrayOutputStream()
            buffer.write(raw.data)
            var chunk = raw
            var chunks = 0
            while (chunk.sw1 == 0x61) {
                if (++chunks > maxResponseChunks) {
                    throw NfcEngineException(ErrorKind.MALFORMED_RESPONSE, "Too many GET RESPONSE iterations")
                }
                val n = if (chunk.sw2 == 0) CommandApdu.MAX_SHORT_NE else chunk.sw2
                chunk = send(ApduCommands.getResponse(n)).also { transmissions++ }
                buffer.write(chunk.data)
            }
            response = ResponseApdu.of(buffer.toByteArray(), chunk.sw)
        }

        val duration = clock.nowMs() - start
        val ne = current.ne
        val lengthWarning = when {
            ne == null && response.dataLength > 0 ->
                "Card returned ${response.dataLength} data bytes although none were requested"
            ne != null && response.dataLength > ne && raw.sw1 != 0x61 ->
                "Card returned ${response.dataLength} bytes, more than the requested Ne=$ne"
            else -> null
        }
        val exchange = ApduExchange(
            label = label,
            command = current,
            response = response,
            status = StatusWords.interpret(response.sw),
            durationMs = duration,
            transmissions = transmissions,
            lengthWarning = lengthWarning,
            publicData = publicData,
        )
        _history += exchange
        logger?.apdu(exchange)
        lengthWarning?.let { logger?.warning(it) }
        return exchange
    }

    private suspend fun send(command: CommandApdu): ResponseApdu {
        val raw = transport.transceive(command.encode())
        if (raw.size < 2) {
            throw NfcEngineException(
                ErrorKind.MALFORMED_RESPONSE,
                if (raw.isEmpty()) "Empty response from card" else "Response shorter than the SW1 SW2 trailer",
            )
        }
        return ResponseApdu(raw)
    }

    companion object {
        /** Conservative chunk size used by most eMRTD readers for READ BINARY without SM. */
        const val MAX_READ_CHUNK = 224
    }
}
