package com.samtex.eidreader.icao.reader

import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.transport.ApduTransport
import kotlinx.coroutines.runBlocking
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CommandAPDU
import net.sf.scuba.smartcards.ResponseAPDU

/**
 * Bridges JMRTD (blocking SCUBA [CardService]) onto SAMTEX's [ApduTransport], so authenticated
 * reads benefit from the same session guard, timeouts and error translation as diagnostics.
 *
 * Privacy: only the instruction byte, the status word and whether the APDU is secure-messaging
 * protected are reported to [onExchange]. Command and response data are never logged.
 */
class TransportCardService(
    private val transport: ApduTransport,
    private val onExchange: (ins: Int, sw: Int, secureMessaging: Boolean) -> Unit = { _, _, _ -> },
) : CardService() {

    @Volatile
    private var opened = false

    override fun open() {
        if (!transport.isConnected) throw CardServiceException("ISO-DEP transport is not connected")
        opened = true
    }

    override fun isOpen(): Boolean = opened && transport.isConnected

    override fun transmit(command: CommandAPDU): ResponseAPDU {
        if (!opened) throw CardServiceException("Card service not open")
        val raw = try {
            runBlocking { transport.transceive(command.bytes) }
        } catch (e: NfcEngineException) {
            throw CardServiceException(e.message ?: "Transport failure", e)
        }
        if (raw.size < 2) throw CardServiceException("Response shorter than SW1 SW2")
        val response = ResponseAPDU(raw)
        onExchange(command.ins, response.sw and 0xFFFF, (command.cla and 0x0C) != 0)
        return response
    }

    override fun getATR(): ByteArray = ByteArray(0)

    override fun isExtendedAPDULengthSupported(): Boolean = transport.isExtendedLengthSupported

    /** The owning [com.samtex.eidreader.nfc.session.IsoDepSessionManager] closes the transport. */
    override fun close() {
        opened = false
    }

    override fun isConnectionLost(e: Exception): Boolean = connectionLossCause(e) != null

    companion object {
        fun connectionLossCause(t: Throwable?): NfcEngineException? {
            var c = t
            var depth = 0
            while (c != null && depth++ < 10) {
                if (c is NfcEngineException && c.kind.isConnectionLoss) return c
                c = c.cause
            }
            return null
        }
    }
}
