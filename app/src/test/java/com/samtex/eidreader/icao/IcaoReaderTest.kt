package com.samtex.eidreader.icao

import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.pa.Authenticity
import com.samtex.eidreader.icao.pa.PassiveAuthenticator
import com.samtex.eidreader.icao.reader.IcaoDocumentReader
import com.samtex.eidreader.icao.reader.ReadOptions
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.SimulatedEmrtdChip
import com.samtex.eidreader.testing.SyntheticDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * End-to-end reads against the SIMULATED chip using JMRTD's real BAC/PACE/secure messaging client.
 * These tests validate SAMTEX's integration logic only; they are not evidence of CNIBE compatibility.
 */
class IcaoReaderTest {

    private val doc = SyntheticDocument
    private fun reader(trusted: Boolean = true) = IcaoDocumentReader(
        IsoDepSessionManager(),
        PassiveAuthenticator { if (trusted) listOf(doc.pki.csca) else emptyList() },
        FakeClock(),
    )

    private fun mrzCredentials(number: String = doc.DOC_NUMBER, dob: LocalDate = LocalDate.of(1990, 1, 1)) =
        (AccessCredentials.create(number, dob, LocalDate.of(2032, 1, 1), null, LocalDate.of(2026, 1, 1)) as AccessCredentials.Validation.Valid).credentials

    private fun canCredentials(can: String) =
        (AccessCredentials.create(null, null, null, can, LocalDate.of(2026, 1, 1)) as AccessCredentials.Validation.Valid).credentials

    private fun read(chip: SimulatedEmrtdChip, creds: AccessCredentials, portrait: Boolean = false): IdentityReadResult = runBlocking {
        val steps = mutableListOf<ReadStep>()
        reader().read(chip.transport(), creds, ReadOptions(readPortrait = portrait)) { steps += it }.also {
            if (it.outcome == ReadOutcome.READING_SUCCESSFUL) assertEquals(ReadStep.DONE, steps.last())
            creds.close()
        }
    }

    @Test fun `BAC read returns parsed identity with verified authenticity`() {
        val chip = doc.chip(bac = true, pace = false)
        val result = read(chip, mrzCredentials())
        assertEquals(result.detail, ReadOutcome.READING_SUCCESSFUL, result.outcome)
        assertEquals(AccessProtocol.BAC, result.technical.protocol)
        assertTrue(chip.secureMessagingActive)
        val r = result.record!!
        assertEquals("TESTEUR", r.field(IdentityFieldKey.SURNAME)!!.value)
        assertEquals("SPECIMEN ALPHA", r.field(IdentityFieldKey.GIVEN_NAMES)!!.value)
        assertEquals("1990-01-01", r.field(IdentityFieldKey.DATE_OF_BIRTH)!!.value)
        assertEquals("تجريبي, عينة", r.field(IdentityFieldKey.FULL_NAME_NATIONAL)!!.value)
        assertEquals("مدينة تجريبية", r.field(IdentityFieldKey.PLACE_OF_BIRTH)!!.value)
        assertEquals("SPECIMEN AUTHORITY", r.field(IdentityFieldKey.ISSUING_AUTHORITY)!!.value)
        assertEquals("2022-01-01", r.field(IdentityFieldKey.DATE_OF_ISSUE)!!.value)
        assertTrue(r.fields.all { it.hashVerified == true })
        assertNull("portrait not requested", r.portrait)
        assertEquals(FileReadStatus.NOT_REQUESTED, result.technical.files["DG2"])
        assertTrue(result.trust.authenticityEstablished)
        assertEquals(Authenticity.ESTABLISHED, result.passiveAuth!!.authenticity)
        // No fingerprint / iris / proprietary DG requested; only standard read-only and access-control INS used.
        assertTrue(chip.instructions.all { it in setOf(0xA4, 0xB0, 0x84, 0x82) })
    }

    @Test fun `PACE with MRZ key is preferred when advertised`() {
        val chip = doc.chip(bac = true, pace = true)
        val result = read(chip, mrzCredentials(), portrait = true)
        assertEquals(result.detail, ReadOutcome.READING_SUCCESSFUL, result.outcome)
        assertEquals(AccessProtocol.PACE_MRZ, result.technical.protocol)
        assertEquals(SyntheticDocument.PACE_OID, result.technical.paceProtocolOid)
        assertFalse("no BAC after successful PACE", 0x82 in chip.instructions)
        val portrait = result.record!!.portrait
        assertNotNull(portrait)
        assertEquals("image/jpeg", portrait!!.mimeType)
        assertTrue(portrait.data.contentEquals(SyntheticDocument.portraitBytes))
        assertTrue(result.trust.authenticityEstablished)
    }

    @Test fun `PACE with CAN`() {
        val chip = doc.chip(bac = false, pace = true, can = "123456")
        val result = read(chip, canCredentials("123456"))
        assertEquals(result.detail, ReadOutcome.READING_SUCCESSFUL, result.outcome)
        assertEquals(AccessProtocol.PACE_CAN, result.technical.protocol)
    }

    @Test fun `wrong MRZ data fails authentication without retries or downgrade`() {
        val bacChip = doc.chip(bac = true, pace = false)
        val bac = read(bacChip, mrzCredentials(number = "999999999"))
        assertEquals(ReadOutcome.AUTHENTICATION_FAILED, bac.outcome)
        assertNull(bac.record)
        // Exactly one authentication attempt: one challenge, one cryptogram. (JMRTD re-sends the same
        // cryptogram without Le for chips that reject Le=28; the chip refuses the replay.)
        assertEquals(1, bacChip.instructions.count { it == 0x84 })

        val paceChip = doc.chip(bac = true, pace = true)
        val pace = read(paceChip, mrzCredentials(dob = LocalDate.of(1991, 1, 1)))
        assertEquals(ReadOutcome.AUTHENTICATION_FAILED, pace.outcome)
        assertFalse("must not downgrade to BAC after a PACE authentication failure", 0x82 in paceChip.instructions)
        assertFalse(pace.trust.accessAuthenticated)
    }

    @Test fun `wrong CAN fails authentication`() {
        val chip = doc.chip(bac = false, pace = true, can = "123456")
        assertEquals(ReadOutcome.AUTHENTICATION_FAILED, read(chip, canCredentials("654321")).outcome)
    }

    @Test fun `CAN only on a card without PACE asks for MRZ data`() {
        val chip = doc.chip(bac = true, pace = false)
        assertEquals(ReadOutcome.ACCESS_CREDENTIALS_REQUIRED, read(chip, canCredentials("123456")).outcome)
    }

    @Test fun `missing LDS application is reported`() {
        val chip = doc.chip(hasApplication = false)
        assertEquals(ReadOutcome.APPLICATION_NOT_FOUND, read(chip, mrzCredentials()).outcome)
    }

    @Test fun `card removal during reading`() {
        val chip = doc.chip(removeAfterCommands = 8)
        val result = read(chip, mrzCredentials())
        assertEquals(ReadOutcome.CARD_REMOVED, result.outcome)
        assertNull(result.record)
    }

    @Test fun `tampered data group is detected by passive authentication`() {
        val chip = doc.chip(tamperDg1 = true)
        val result = read(chip, mrzCredentials())
        assertEquals(ReadOutcome.READING_SUCCESSFUL, result.outcome)
        assertEquals(Authenticity.FAILED, result.passiveAuth!!.authenticity)
        assertFalse(result.trust.authenticityEstablished)
        assertEquals(false, result.record!!.field(IdentityFieldKey.SURNAME)!!.hashVerified)
    }

    @Test fun `authenticity is not established without a trusted CSCA`() = runBlocking {
        val chip = doc.chip()
        val creds = mrzCredentials()
        val result = reader(trusted = false).read(chip.transport(), creds)
        creds.close()
        assertEquals(ReadOutcome.READING_SUCCESSFUL, result.outcome)
        assertTrue(result.trust.signatureVerified)
        assertTrue(result.trust.hashesMatched)
        assertFalse(result.trust.chainVerified)
        assertFalse(result.trust.authenticityEstablished)
        assertEquals(Authenticity.NOT_ESTABLISHED, result.passiveAuth!!.authenticity)
    }

    @Test fun `technical report contains no personal data`() {
        val chip = doc.chip(pace = true)
        val result = read(chip, mrzCredentials())
        val text = result.technical.toString() + result.detail
        listOf("TESTEUR", "SPECIMEN", doc.DOC_NUMBER, "900101", "تجريبي").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test fun `record wipe clears the portrait`() {
        val chip = doc.chip(pace = true)
        val record = read(chip, mrzCredentials(), portrait = true).record!!
        val data = record.portrait!!.data
        record.wipe()
        assertNull(record.portrait)
        assertTrue(data.all { it.toInt() == 0 })
    }
}
