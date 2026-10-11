package com.samtex.eidreader.nfc.transport

import com.samtex.eidreader.nfc.apdu.TransportLimits

/**
 * Raw APDU transport to a contactless card. The production implementation wraps
 * [android.nfc.tech.IsoDep]; unit tests use a simulated transport.
 *
 * Implementations must throw [com.samtex.eidreader.nfc.error.NfcEngineException] for all
 * communication failures.
 */
interface ApduTransport {
    val maxTransceiveLength: Int
    val isExtendedLengthSupported: Boolean
    val isConnected: Boolean

    /** Connects (if not yet connected) and applies the transceive timeout in milliseconds. */
    suspend fun connect(timeoutMs: Int)

    /** Sends one encoded command APDU and returns the raw response (data + SW1 SW2). */
    suspend fun transceive(command: ByteArray): ByteArray

    /** Releases the connection. Must be idempotent and never throw. */
    fun close()

    val limits: TransportLimits
        get() = TransportLimits(maxTransceiveLength, isExtendedLengthSupported)
}
