package com.samtex.eidreader.nfc.transport

import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException

/**
 * [ApduTransport] backed by Android's [IsoDep] (ISO/IEC 14443-4).
 *
 * All blocking I/O runs on [ioDispatcher]. Android exceptions are translated into
 * [NfcEngineException] so callers never deal with platform types.
 */
class IsoDepTransport(
    private val isoDep: IsoDep,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ApduTransport {

    override val maxTransceiveLength: Int
        get() = try {
            isoDep.maxTransceiveLength
        } catch (_: RuntimeException) {
            DEFAULT_MAX_TRANSCEIVE
        }

    override val isExtendedLengthSupported: Boolean
        get() = try {
            isoDep.isExtendedLengthApduSupported
        } catch (_: RuntimeException) {
            false
        }

    override val isConnected: Boolean
        get() = try {
            isoDep.isConnected
        } catch (_: RuntimeException) {
            false
        }

    override suspend fun connect(timeoutMs: Int) {
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Timeout must be within $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS ms" }
        mapErrors("connect") {
            runInterruptible(ioDispatcher) {
                if (!isoDep.isConnected) isoDep.connect()
                isoDep.timeout = timeoutMs
            }
        }
    }

    override suspend fun transceive(command: ByteArray): ByteArray {
        if (command.size > maxTransceiveLength) {
            throw NfcEngineException(
                ErrorKind.PAYLOAD_TOO_LARGE,
                "Command of ${command.size} bytes exceeds the device limit of $maxTransceiveLength bytes",
            )
        }
        if (!isConnected) throw NfcEngineException(ErrorKind.NOT_CONNECTED, "IsoDep is not connected")
        return mapErrors("transceive") {
            runInterruptible(ioDispatcher) { isoDep.transceive(command) }
        } ?: throw NfcEngineException(ErrorKind.MALFORMED_RESPONSE, "Card returned no response")
    }

    override fun close() {
        try {
            isoDep.close()
        } catch (_: IOException) {
            // Already closed or tag gone: nothing to release.
        } catch (_: RuntimeException) {
            // SecurityException for stale tags etc.
        }
    }

    private inline fun <T> mapErrors(operation: String, block: () -> T): T = try {
        block()
    } catch (e: TagLostException) {
        throw NfcEngineException(ErrorKind.TAG_LOST, "Card left the field during $operation", e)
    } catch (e: SecurityException) {
        throw NfcEngineException(ErrorKind.STALE_TAG, "Tag handle is out of date during $operation", e)
    } catch (e: IllegalStateException) {
        throw NfcEngineException(ErrorKind.COMMUNICATION, "Illegal NFC state during $operation", e)
    } catch (e: IOException) {
        val kind = if (e.message?.contains("timeout", ignoreCase = true) == true) ErrorKind.TIMEOUT else ErrorKind.COMMUNICATION
        throw NfcEngineException(kind, "I/O failure during $operation", e)
    }

    companion object {
        const val DEFAULT_MAX_TRANSCEIVE = 253
        const val MIN_TIMEOUT_MS = 50
        const val MAX_TIMEOUT_MS = 10_000
    }
}
