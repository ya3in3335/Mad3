package com.samtex.eidreader.domain.usecase

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.domain.model.DiscoveredCard
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.ApduPolicy
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.protocol.TransparentFileReader
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.nfc.transport.ApduChannel
import com.samtex.eidreader.nfc.transport.ApduExchange
import javax.inject.Inject

/** A documented, read-only diagnostic operation offered in the console. */
enum class DiagnosticOperation(val title: String, val reference: String) {
    SELECT_MF("SELECT Master File (3F00)", "ISO/IEC 7816-4"),
    READ_EF_CARD_ACCESS("Read EF.CardAccess (011C)", "ICAO Doc 9303 Part 10/11 — public"),
    READ_EF_DIR("Read EF.DIR (2F00)", "ISO/IEC 7816-4 — application directory"),
    READ_EF_ATR_INFO("Read EF.ATR/INFO (2F01)", "ISO/IEC 7816-4 — card capabilities"),
    SELECT_EMRTD("SELECT eMRTD application", "ICAO Doc 9303 Part 10 — AID A0000002471001"),
}

/** Executes console operations against the current card inside a guarded ISO-DEP session. */
class RunDiagnosticUseCase @Inject constructor(
    private val sessionManager: IsoDepSessionManager,
    private val clock: Clock,
) {

    suspend fun run(card: DiscoveredCard, operation: DiagnosticOperation, logger: SessionLogger): List<ApduExchange> =
        withChannel(card, logger) { channel ->
            when (operation) {
                DiagnosticOperation.SELECT_MF ->
                    listOf(channel.transmit(ApduCommands.selectMasterFile(), "SELECT MF", publicData = true))
                DiagnosticOperation.SELECT_EMRTD ->
                    listOf(channel.transmit(ApduCommands.selectByAid(ApduCommands.AID_ICAO_EMRTD), "SELECT eMRTD AID", publicData = true))
                DiagnosticOperation.READ_EF_CARD_ACCESS -> readFile(channel, ApduCommands.FID_EF_CARD_ACCESS, "EF.CardAccess")
                DiagnosticOperation.READ_EF_DIR -> readFile(channel, ApduCommands.FID_EF_DIR, "EF.DIR")
                DiagnosticOperation.READ_EF_ATR_INFO -> readFile(channel, ApduCommands.FID_EF_ATR_INFO, "EF.ATR/INFO")
            }
        }

    /** Sends one user-entered command after the authorization policy has approved it. */
    suspend fun runCustom(card: DiscoveredCard, command: CommandApdu, logger: SessionLogger): ApduExchange {
        val decision = ApduPolicy.evaluate(command)
        if (!decision.allowed) throw NfcEngineException(ErrorKind.POLICY_DENIED, decision.reason)
        return withChannel(card, logger) { channel ->
            channel.transmit(command, "Custom ${command.toHex()}".take(48), publicData = false)
        }
    }

    private suspend fun readFile(channel: ApduChannel, fid: Int, label: String): List<ApduExchange> {
        val before = channel.history.size
        TransparentFileReader.readTlvFile(channel, fid, label)
        return channel.history.drop(before)
    }

    private suspend fun <T> withChannel(card: DiscoveredCard, logger: SessionLogger, block: suspend (ApduChannel) -> T): T {
        val transport = card.openIsoDepTransport()
            ?: throw NfcEngineException(ErrorKind.UNSUPPORTED_TAG, "The current tag does not support ISO-DEP")
        return sessionManager.withSession(transport, logger, clock, block = block)
    }
}
