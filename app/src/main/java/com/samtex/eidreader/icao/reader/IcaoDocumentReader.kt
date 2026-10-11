package com.samtex.eidreader.icao.reader

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.icao.AccessProtocol
import com.samtex.eidreader.icao.FieldSource
import com.samtex.eidreader.icao.FileReadStatus
import com.samtex.eidreader.icao.IdentityField
import com.samtex.eidreader.icao.IdentityFieldKey
import com.samtex.eidreader.icao.IdentityReadResult
import com.samtex.eidreader.icao.IdentityRecord
import com.samtex.eidreader.icao.IdentityTechnicalReport
import com.samtex.eidreader.icao.ReadOutcome
import com.samtex.eidreader.icao.ReadStep
import com.samtex.eidreader.icao.TrustLevels
import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.lds.Dg11
import com.samtex.eidreader.icao.lds.Dg12
import com.samtex.eidreader.icao.lds.LdsFormatException
import com.samtex.eidreader.icao.lds.LdsParsers
import com.samtex.eidreader.icao.lds.PortraitImage
import com.samtex.eidreader.icao.mrz.MrzData
import com.samtex.eidreader.icao.pa.CheckStatus
import com.samtex.eidreader.icao.pa.HashCheck
import com.samtex.eidreader.icao.pa.PassiveAuthReport
import com.samtex.eidreader.icao.pa.PassiveAuthenticator
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.nfc.session.SessionConfig
import com.samtex.eidreader.nfc.transport.ApduTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class ReadOptions(
    /** Read DG2 (facial image). Off unless the holder asks for it. */
    val readPortrait: Boolean = false,
)

/** Internal: stops the read with an explicit outcome. Messages never contain secrets or personal data. */
private class ReadAbort(val outcome: ReadOutcome, message: String) : Exception(message)

/**
 * Reads the ICAO Doc 9303 LDS1 application of an eMRTD / eID card with the holder's access data.
 *
 * Protocol selection follows ICAO Doc 9303 Part 11 and is driven by what the card advertises:
 * - If EF.CardAccess advertises PACE, PACE is run (CAN if supplied, otherwise MRZ-derived key).
 *   A PACE authentication failure is reported as such and is NOT retried with BAC (no downgrade).
 *   Only when PACE cannot be executed for protocol reasons (unsupported parameters) and an MRZ key
 *   is available does the reader fall back to BAC, which the standard allows for chips that
 *   support both.
 * - Otherwise BAC is run with the MRZ-derived key.
 *
 * Only DG1, DG11, DG12 and (on request) DG2 are read, plus EF.COM and EF.SOD. Fingerprints/iris
 * (DG3/DG4) are never requested; proprietary DG13 is not decoded. No PIN is ever tried and each
 * credential is used for at most one authentication attempt per read.
 *
 * The caller owns [AccessCredentials] and must close (wipe) them when the read attempt ends.
 */
@Singleton
class IcaoDocumentReader @Inject constructor(
    private val sessionManager: IsoDepSessionManager,
    private val passiveAuthenticator: PassiveAuthenticator,
    private val clock: Clock,
) {

    suspend fun read(
        transport: ApduTransport,
        credentials: AccessCredentials,
        options: ReadOptions = ReadOptions(),
        onStep: (ReadStep) -> Unit = {},
    ): IdentityReadResult {
        val ctx = Context(clock.nowMs())
        try {
            if (!credentials.hasMrzKey && !credentials.hasCan) {
                return ctx.failure(ReadOutcome.ACCESS_CREDENTIALS_REQUIRED, "No access data supplied")
            }
            onStep(ReadStep.CONNECTING)
            return sessionManager.withSession(transport, null, clock, IDENTITY_SESSION) {
                ctx.connected = true
                runInterruptible(Dispatchers.IO) { readBlocking(transport, credentials, options, onStep, ctx) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReadAbort) {
            ctx.log += "Stopped: ${e.outcome}"
            return ctx.failure(e.outcome, e.message)
        } catch (e: NfcEngineException) {
            return if (e.kind.isConnectionLoss) {
                ctx.failure(ReadOutcome.CARD_REMOVED, e.kind.name)
            } else {
                ctx.failure(ReadOutcome.PROTOCOL_UNSUPPORTED, e.kind.name)
            }
        } catch (e: Exception) {
            return if (TransportCardService.connectionLossCause(e) != null) {
                ctx.failure(ReadOutcome.CARD_REMOVED, "Connection lost")
            } else {
                ctx.failure(ReadOutcome.PROTOCOL_UNSUPPORTED, e.javaClass.simpleName)
            }
        }
    }

    /** Mutable bookkeeping of one read. */
    private inner class Context(val start: Long) {
        var connected = false
        var authenticated = false
        var protocol: AccessProtocol? = null
        var paceOid: String? = null
        var paceAdvertised = false
        var apduCount = 0
        val files = linkedMapOf<String, FileReadStatus>()
        val log = mutableListOf<String>()

        fun technical() = IdentityTechnicalReport(protocol, paceOid, paceAdvertised, files.toMap(), apduCount, clock.nowMs() - start, log.toList())

        fun failure(outcome: ReadOutcome, detail: String?) = IdentityReadResult(
            outcome = outcome,
            record = null,
            passiveAuth = null,
            trust = TrustLevels(connected, authenticated, false, false, false, false),
            technical = technical(),
            detail = detail,
        )
    }

    private fun swOf(t: Throwable?): Int {
        var c = t
        var depth = 0
        while (c != null && depth++ < 10) {
            if (c is CardServiceException && c.sw != CardServiceException.SW_NONE && c.sw != -1) return c.sw and 0xFFFF
            c = c.cause
        }
        return -1
    }

    private fun abortIfLost(e: Throwable) {
        if (TransportCardService.connectionLossCause(e) != null) throw ReadAbort(ReadOutcome.CARD_REMOVED, "Card removed")
    }

    private fun isAuthenticationFailure(sw: Int) =
        sw == 0x6300 || (sw and 0xFFF0) == 0x63C0 || sw == 0x6982 || sw == 0x6983 || sw == 0x6984 || sw == 0x6985 || sw == 0x6988

    private fun readBlocking(
        transport: ApduTransport,
        credentials: AccessCredentials,
        options: ReadOptions,
        onStep: (ReadStep) -> Unit,
        ctx: Context,
    ): IdentityReadResult {
        val cardService = TransportCardService(transport) { ins, sw, sm ->
            ctx.apduCount++
            ctx.log += "INS ${Hex.byte(ins and 0xFF)} → ${Hex.u16(sw)}${if (sm) " (secure messaging)" else ""}"
        }
        cardService.open()
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            true,
        )
        service.open()

        // 1. EF.CardAccess (public, read before authentication).
        onStep(ReadStep.READING_CARD_ACCESS)
        val paceInfos = readPaceInfos(service, ctx)
        ctx.paceAdvertised = paceInfos.isNotEmpty()

        // 2. Access control.
        onStep(ReadStep.AUTHENTICATING)
        authenticate(service, credentials, paceInfos, ctx)
        ctx.authenticated = true

        // 3. Files.
        onStep(ReadStep.READING_COM)
        val comBytes = readFile(service, PassportService.EF_COM, "EF.COM", ctx)
        val com = comBytes?.let {
            try {
                LdsParsers.parseCom(it)
            } catch (e: LdsFormatException) {
                ctx.log += "EF.COM not decodable"
                null
            }
        }
        onStep(ReadStep.READING_SOD)
        val sodBytes = readFile(service, PassportService.EF_SOD, "EF.SOD", ctx)
        val sodGroups: Set<Int>? = sodBytes?.let {
            try {
                SODFile(ByteArrayInputStream(it)).dataGroupHashes.keys
            } catch (_: Exception) {
                null
            }
        }
        val advertised: Set<Int>? = sodGroups ?: com?.dataGroups?.toSet()
        fun listed(dg: Int) = advertised == null || dg in advertised

        val raw = linkedMapOf<Int, ByteArray>()
        onStep(ReadStep.READING_DG1)
        val dg1 = readFile(service, PassportService.EF_DG1, "DG1", ctx)
        if (dg1 == null) {
            val status = ctx.files["DG1"]
            throw ReadAbort(
                if (status == FileReadStatus.ACCESS_DENIED) ReadOutcome.ACCESS_DENIED else ReadOutcome.FILE_NOT_FOUND,
                "DG1 not readable",
            )
        }
        raw[1] = dg1
        if (listed(11)) {
            onStep(ReadStep.READING_DG11)
            readFile(service, PassportService.EF_DG11, "DG11", ctx)?.let { raw[11] = it }
        }
        if (listed(12)) {
            onStep(ReadStep.READING_DG12)
            readFile(service, PassportService.EF_DG12, "DG12", ctx)?.let { raw[12] = it }
        }
        if (options.readPortrait && listed(2)) {
            onStep(ReadStep.READING_DG2)
            readFile(service, PassportService.EF_DG2, "DG2", ctx)?.let { raw[2] = it }
        } else if (advertised?.contains(2) == true) {
            ctx.files["DG2"] = FileReadStatus.NOT_REQUESTED
        }
        advertised?.filter { it == 3 || it == 4 }?.forEach { ctx.files["DG$it"] = FileReadStatus.SKIPPED_BY_POLICY }
        val undecoded = advertised?.filter { it == 13 }.orEmpty()
        undecoded.forEach { ctx.files["DG$it"] = FileReadStatus.SKIPPED_BY_POLICY }

        // 4. Decode.
        val mrz = try {
            LdsParsers.parseDg1(dg1)
        } catch (e: LdsFormatException) {
            wipe(raw)
            throw ReadAbort(ReadOutcome.DATA_FORMAT_UNSUPPORTED, e.message ?: "DG1 format")
        }
        val dg11 = raw[11]?.let { bytes ->
            try {
                LdsParsers.parseDg11(bytes)
            } catch (e: LdsFormatException) {
                ctx.files["DG11"] = FileReadStatus.ERROR
                ctx.log += "DG11 not decodable"
                null
            }
        }
        val dg12 = raw[12]?.let { bytes ->
            try {
                LdsParsers.parseDg12(bytes)
            } catch (e: LdsFormatException) {
                ctx.files["DG12"] = FileReadStatus.ERROR
                ctx.log += "DG12 not decodable"
                null
            }
        }
        val portrait: PortraitImage? = raw[2]?.let { bytes ->
            try {
                LdsParsers.parseDg2(bytes)
            } catch (e: LdsFormatException) {
                ctx.files["DG2"] = FileReadStatus.ERROR
                ctx.log += "DG2 not decodable"
                null
            }
        }

        // 5. Passive authentication over exactly the bytes that were read.
        onStep(ReadStep.VERIFYING)
        val pa: PassiveAuthReport = passiveAuthenticator.verify(sodBytes, raw)
        wipe(raw)
        sodBytes?.fill(0)
        comBytes?.fill(0)

        val fields = buildFields(mrz, dg11, dg12) { dg -> pa.hashes[dg]?.let { it == HashCheck.MATCH } }
        onStep(ReadStep.DONE)
        return IdentityReadResult(
            outcome = ReadOutcome.READING_SUCCESSFUL,
            record = IdentityRecord(fields, mrz, portrait, undecoded),
            passiveAuth = pa,
            trust = TrustLevels(
                nfcConnection = true,
                accessAuthenticated = true,
                dataRead = true,
                hashesMatched = pa.hashesMatched,
                signatureVerified = pa.signature == CheckStatus.VALID,
                chainVerified = pa.chain == CheckStatus.VALID,
            ),
            technical = ctx.technical(),
            detail = null,
        )
    }

    private fun wipe(raw: Map<Int, ByteArray>) = raw.values.forEach { it.fill(0) }

    private fun readPaceInfos(service: PassportService, ctx: Context): List<PACEInfo> {
        return try {
            val file = service.getInputStream(PassportService.EF_CARD_ACCESS).use { CardAccessFile(it) }
            ctx.files["EF.CardAccess"] = FileReadStatus.READ
            file.securityInfos.filterIsInstance<PACEInfo>()
        } catch (e: Exception) {
            abortIfLost(e)
            ctx.files["EF.CardAccess"] = if (swOf(e) == 0x6A82) FileReadStatus.NOT_PRESENT else FileReadStatus.ERROR
            emptyList()
        }
    }

    private fun authenticate(service: PassportService, credentials: AccessCredentials, paceInfos: List<PACEInfo>, ctx: Context) {
        if (paceInfos.isNotEmpty()) {
            val useCan = credentials.hasCan
            if (useCan || credentials.hasMrzKey) {
                for (info in paceInfos.take(MAX_PACE_CONFIGURATIONS)) {
                    try {
                        val key = if (useCan) {
                            credentials.withCan { PACEKeySpec.createCANKey(it) }
                        } else {
                            credentials.withMrzKey { d, b, e -> PACEKeySpec.createMRZKey(BACKey(d, b, e)) }
                        }
                        service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), info.parameterId)
                        ctx.protocol = if (useCan) AccessProtocol.PACE_CAN else AccessProtocol.PACE_MRZ
                        ctx.paceOid = info.objectIdentifier
                        ctx.log += "PACE established (${info.objectIdentifier})"
                        selectApplication(service, secure = true)
                        return
                    } catch (e: CardServiceException) {
                        abortIfLost(e)
                        val sw = swOf(e)
                        if (isAuthenticationFailure(sw)) {
                            throw ReadAbort(ReadOutcome.AUTHENTICATION_FAILED, "PACE rejected (SW ${Hex.u16(sw)})")
                        }
                        ctx.log += "PACE configuration not usable (SW ${if (sw < 0) "none" else Hex.u16(sw)})"
                    } catch (e: GeneralSecurityException) {
                        ctx.log += "PACE parameters not supported locally"
                    } catch (e: IllegalArgumentException) {
                        ctx.log += "PACE parameters not supported locally"
                    }
                }
            }
            if (!credentials.hasMrzKey) {
                throw ReadAbort(ReadOutcome.PROTOCOL_UNSUPPORTED, "PACE with CAN could not be established")
            }
            ctx.log += "Falling back to BAC (PACE could not be executed for protocol reasons)"
        }

        if (!credentials.hasMrzKey) {
            throw ReadAbort(ReadOutcome.ACCESS_CREDENTIALS_REQUIRED, "Card does not advertise PACE; card number and dates are required for BAC")
        }
        selectApplication(service, secure = false)
        try {
            credentials.withMrzKey { d, b, e -> service.doBAC(BACKey(d, b, e)) }
            ctx.protocol = AccessProtocol.BAC
            ctx.log += "BAC established"
        } catch (e: CardServiceException) {
            abortIfLost(e)
            val sw = swOf(e)
            when {
                isAuthenticationFailure(sw) -> throw ReadAbort(ReadOutcome.AUTHENTICATION_FAILED, "BAC rejected (SW ${Hex.u16(sw)})")
                sw == 0x6D00 || sw == 0x6E00 || sw == 0x6A81 -> throw ReadAbort(ReadOutcome.PROTOCOL_UNSUPPORTED, "BAC not supported (SW ${Hex.u16(sw)})")
                else -> throw ReadAbort(ReadOutcome.AUTHENTICATION_FAILED, "BAC failed (SW ${if (sw < 0) "none" else Hex.u16(sw)})")
            }
        }
    }

    private fun selectApplication(service: PassportService, secure: Boolean) {
        try {
            service.sendSelectApplet(secure)
        } catch (e: CardServiceException) {
            abortIfLost(e)
            when (swOf(e)) {
                0x6A82 -> throw ReadAbort(ReadOutcome.APPLICATION_NOT_FOUND, "ICAO LDS1 application not found")
                0x6982 -> throw ReadAbort(ReadOutcome.ACCESS_DENIED, "Application selection refused")
                else -> throw ReadAbort(ReadOutcome.PROTOCOL_UNSUPPORTED, "Application selection failed")
            }
        }
    }

    private fun readFile(service: PassportService, fid: Short, name: String, ctx: Context): ByteArray? {
        return try {
            val bytes = service.getInputStream(fid).use { it.readBytes() }
            ctx.files[name] = FileReadStatus.READ
            bytes
        } catch (e: Exception) {
            if (e is CardServiceException || e is IOException || e is RuntimeException) {
                abortIfLost(e)
                ctx.files[name] = when (swOf(e)) {
                    0x6A82 -> FileReadStatus.NOT_PRESENT
                    0x6982, 0x6985 -> FileReadStatus.ACCESS_DENIED
                    else -> FileReadStatus.ERROR
                }
                null
            } else {
                throw e
            }
        }
    }

    private fun buildFields(mrz: MrzData, dg11: Dg11?, dg12: Dg12?, verified: (Int) -> Boolean?): List<IdentityField> {
        val out = mutableListOf<IdentityField>()
        fun add(key: IdentityFieldKey, value: String?, source: FieldSource, dg: Int) {
            if (!value.isNullOrBlank()) out += IdentityField(key, value, source, verified(dg))
        }
        val m = FieldSource.DG1_MRZ
        add(IdentityFieldKey.DOCUMENT_TYPE, mrz.documentCode, m, 1)
        add(IdentityFieldKey.ISSUING_STATE, mrz.issuingState, m, 1)
        add(IdentityFieldKey.DOCUMENT_NUMBER, mrz.documentNumber, m, 1)
        add(IdentityFieldKey.SURNAME, mrz.primaryIdentifier, m, 1)
        add(IdentityFieldKey.GIVEN_NAMES, mrz.secondaryIdentifier, m, 1)
        add(IdentityFieldKey.NATIONALITY, mrz.nationality, m, 1)
        add(IdentityFieldKey.DATE_OF_BIRTH, formatMrzDate(mrz.dateOfBirth, birth = true), m, 1)
        add(IdentityFieldKey.SEX, mrz.sex, m, 1)
        add(IdentityFieldKey.DATE_OF_EXPIRY, formatMrzDate(mrz.dateOfExpiry, birth = false), m, 1)
        add(IdentityFieldKey.MRZ_OPTIONAL_DATA_1, mrz.optionalData1, m, 1)
        add(IdentityFieldKey.MRZ_OPTIONAL_DATA_2, mrz.optionalData2, m, 1)
        dg11?.let { d ->
            val s = FieldSource.DG11
            add(IdentityFieldKey.FULL_NAME_NATIONAL, d.fullName, s, 11)
            add(IdentityFieldKey.OTHER_NAMES, d.otherNames.joinToString("\n").ifEmpty { null }, s, 11)
            add(IdentityFieldKey.PERSONAL_NUMBER, d.personalNumber, s, 11)
            add(IdentityFieldKey.FULL_DATE_OF_BIRTH, d.fullDateOfBirth?.let(::formatFullDate), s, 11)
            add(IdentityFieldKey.PLACE_OF_BIRTH, d.placeOfBirth, s, 11)
            add(IdentityFieldKey.ADDRESS, d.permanentAddress, s, 11)
            add(IdentityFieldKey.TELEPHONE, d.telephone, s, 11)
            add(IdentityFieldKey.PROFESSION, d.profession, s, 11)
            add(IdentityFieldKey.TITLE, d.title, s, 11)
            add(IdentityFieldKey.PERSONAL_SUMMARY, d.personalSummary, s, 11)
            add(IdentityFieldKey.OTHER_TD_NUMBERS, d.otherValidTdNumbers, s, 11)
            add(IdentityFieldKey.CUSTODY_INFORMATION, d.custodyInformation, s, 11)
        }
        dg12?.let { d ->
            val s = FieldSource.DG12
            add(IdentityFieldKey.ISSUING_AUTHORITY, d.issuingAuthority, s, 12)
            add(IdentityFieldKey.DATE_OF_ISSUE, d.dateOfIssue?.let(::formatFullDate), s, 12)
            add(IdentityFieldKey.NAMES_OF_OTHER_PERSONS, d.namesOfOtherPersons.joinToString("\n").ifEmpty { null }, s, 12)
            add(IdentityFieldKey.ENDORSEMENTS, d.endorsementsAndObservations, s, 12)
            add(IdentityFieldKey.TAX_EXIT_REQUIREMENTS, d.taxOrExitRequirements, s, 12)
            add(IdentityFieldKey.PERSONALIZATION_TIME, d.personalizationDateTime, s, 12)
        }
        return out
    }

    companion object {
        /** PACE is slower on-chip than diagnostics; still bounded. */
        val IDENTITY_SESSION = SessionConfig(transceiveTimeoutMs = 5_000, sessionTimeoutMs = 90_000)
        private const val MAX_PACE_CONFIGURATIONS = 3

        /** YYMMDD → yyyy-MM-dd. Birth dates never lie in the future; expiry dates use 20YY below 70. */
        fun formatMrzDate(yymmdd: String, birth: Boolean, today: LocalDate = LocalDate.now()): String {
            if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return yymmdd
            val yy = yymmdd.substring(0, 2).toInt()
            val century = if (birth) {
                if (2000 + yy > today.year) 1900 else 2000
            } else {
                if (yy >= 70) 1900 else 2000
            }
            return "${century + yy}-${yymmdd.substring(2, 4)}-${yymmdd.substring(4, 6)}"
        }

        /** yyyymmdd → yyyy-MM-dd (other formats are shown unchanged). */
        fun formatFullDate(value: String): String =
            if (value.length == 8 && value.all { it.isDigit() }) "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}" else value
    }
}
