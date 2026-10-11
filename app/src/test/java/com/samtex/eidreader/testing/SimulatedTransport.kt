package com.samtex.eidreader.testing

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.transport.ApduTransport

/**
 * SIMULATED transport for JVM unit tests. No NFC hardware is involved: every response comes
 * from [responder]. Results obtained with this class say nothing about physical card
 * compatibility, which must be validated on a real device.
 */
class SimulatedTransport(
    override val maxTransceiveLength: Int = 261,
    override val isExtendedLengthSupported: Boolean = false,
    var connectFailure: NfcEngineException? = null,
    private val responder: suspend (ByteArray) -> ByteArray,
) : ApduTransport {

    var connectCount = 0
        private set
    var closeCount = 0
        private set
    var lastTimeoutMs: Int? = null
        private set
    val sent = mutableListOf<String>()

    override var isConnected: Boolean = false
        private set

    override suspend fun connect(timeoutMs: Int) {
        connectFailure?.let { throw it }
        connectCount++
        lastTimeoutMs = timeoutMs
        isConnected = true
    }

    override suspend fun transceive(command: ByteArray): ByteArray {
        if (!isConnected) throw NfcEngineException(ErrorKind.NOT_CONNECTED, "not connected")
        sent += Hex.encode(command)
        return responder(command)
    }

    override fun close() {
        closeCount++
        isConnected = false
    }
}
