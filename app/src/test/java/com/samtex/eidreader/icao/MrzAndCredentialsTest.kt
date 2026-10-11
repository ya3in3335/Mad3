package com.samtex.eidreader.icao

import com.samtex.eidreader.icao.access.AccessCredentials
import com.samtex.eidreader.icao.mrz.MrzCheckDigit
import com.samtex.eidreader.icao.mrz.MrzFormat
import com.samtex.eidreader.icao.mrz.MrzFormatException
import com.samtex.eidreader.icao.mrz.MrzParser
import com.samtex.eidreader.icao.reader.IcaoDocumentReader
import com.samtex.eidreader.testing.SyntheticDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MrzAndCredentialsTest {

    /** ICAO Doc 9303 Part 5 TD1 specimen. */
    private val td1Specimen = "I<UTOD231458907<<<<<<<<<<<<<<<\n7408122F1204159UTO<<<<<<<<<<<6\nERIKSSON<<ANNA<MARIA<<<<<<<<<<"

    /** ICAO Doc 9303 Part 4 TD3 specimen. */
    private val td3Specimen = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<10"

    @Test fun `check digits follow ICAO 7-3-1 weighting`() {
        assertEquals('3', MrzCheckDigit.compute("L898902C<"))
        assertEquals('1', MrzCheckDigit.compute("690806"))
        assertEquals('6', MrzCheckDigit.compute("940623"))
        assertEquals('7', MrzCheckDigit.compute("D23145890"))
        assertTrue(MrzCheckDigit.isValid("<<<<<<<<<<<<<<", '<'))
        assertThrows(IllegalArgumentException::class.java) { MrzCheckDigit.compute("a") }
    }

    @Test fun `ICAO TD1 specimen parses with valid check digits`() {
        val m = MrzParser.parse(td1Specimen)
        assertEquals(MrzFormat.TD1, m.format)
        assertEquals("I", m.documentCode)
        assertEquals("UTO", m.issuingState)
        assertEquals("D23145890", m.documentNumber)
        assertEquals("740812", m.dateOfBirth)
        assertEquals("F", m.sex)
        assertEquals("120415", m.dateOfExpiry)
        assertEquals("ERIKSSON", m.primaryIdentifier)
        assertEquals("ANNA MARIA", m.secondaryIdentifier)
        assertTrue(m.allChecksValid)
    }

    @Test fun `ICAO TD3 specimen parses with valid check digits`() {
        val m = MrzParser.parse(td3Specimen)
        assertEquals(MrzFormat.TD3, m.format)
        assertEquals("L898902C3", m.documentNumber)
        assertEquals("ZE184226B", m.optionalData1)
        assertTrue(m.allChecksValid)
    }

    @Test fun `corrupted MRZ is detected not repaired`() {
        val broken = td1Specimen.replace("7408122", "7408132")
        val m = MrzParser.parse(broken)
        assertFalse(m.dateOfBirthValid)
        assertFalse(m.allChecksValid)
        assertThrows(MrzFormatException::class.java) { MrzParser.parse("TOO SHORT") }
        assertThrows(MrzFormatException::class.java) { MrzParser.parse(td1Specimen.replace('E', 'é')) }
    }

    @Test fun `long document numbers continue in the optional field`() {
        fun cd(s: String) = MrzCheckDigit.compute(s)
        val longNo = "ABC123456789" // 12 characters
        val l1 = "I<UTO" + longNo.substring(0, 9) + "<" + longNo.substring(9) + cd(longNo) + "<".repeat(11)
        val l2body = "740812" + cd("740812") + "F" + "320415" + cd("320415") + "UTO" + "<".repeat(11)
        val composite = l1.substring(5, 30) + l2body.substring(0, 7) + l2body.substring(8, 15) + l2body.substring(18, 29)
        val mrz = l1 + l2body + cd(composite) + "DOE<<JANE".padEnd(30, '<')
        val m = MrzParser.parse(mrz)
        assertEquals(longNo, m.documentNumber)
        assertTrue(m.allChecksValid)
    }

    @Test fun `synthetic TD1 document is self-consistent`() {
        val m = MrzParser.parse(SyntheticDocument.MRZ)
        assertTrue(m.allChecksValid)
        assertEquals("DZA", m.issuingState)
        assertEquals(SyntheticDocument.DOC_NUMBER, m.documentNumber)
    }

    @Test fun `MRZ date formatting infers the century safely`() {
        val today = LocalDate.of(2026, 10, 1)
        assertEquals("1990-01-01", IcaoDocumentReader.formatMrzDate("900101", birth = true, today = today))
        assertEquals("2005-06-30", IcaoDocumentReader.formatMrzDate("050630", birth = true, today = today))
        assertEquals("2032-01-01", IcaoDocumentReader.formatMrzDate("320101", birth = false, today = today))
        assertEquals("ABCDEF", IcaoDocumentReader.formatMrzDate("ABCDEF", birth = true, today = today))
        assertEquals("2022-01-01", IcaoDocumentReader.formatFullDate("20220101"))
    }

    @Test fun `credentials are validated and wiped`() {
        val today = LocalDate.of(2026, 10, 1)
        val valid = AccessCredentials.create(" 1234-56789 ", LocalDate.of(1990, 1, 1), LocalDate.of(2032, 1, 1), null, today)
        val creds = (valid as AccessCredentials.Validation.Valid).credentials
        assertTrue(creds.hasMrzKey)
        assertEquals(SyntheticDocument.mrzInformation, creds.mrzInformation())
        creds.close()
        assertFalse(creds.hasMrzKey)
        assertThrows(IllegalStateException::class.java) { creds.mrzInformation() }

        val invalid = AccessCredentials.create("12*", LocalDate.of(2030, 1, 1), LocalDate.of(2020, 1, 1), "12AB", today)
        val problems = (invalid as AccessCredentials.Validation.Invalid).errors
        assertEquals(
            setOf(
                AccessCredentials.Problem.DOCUMENT_NUMBER,
                AccessCredentials.Problem.DATE_OF_BIRTH,
                AccessCredentials.Problem.DATE_OF_EXPIRY,
                AccessCredentials.Problem.CAN,
            ),
            problems,
        )
        assertEquals(
            setOf(AccessCredentials.Problem.MISSING),
            (AccessCredentials.create(null, null, null, null, today) as AccessCredentials.Validation.Invalid).errors,
        )
        val canOnly = AccessCredentials.create(null, null, null, "123456", today) as AccessCredentials.Validation.Valid
        assertTrue(canOnly.credentials.hasCan)
        assertFalse(canOnly.credentials.hasMrzKey)
    }

    @Test fun `ICAO BAC key derivation test vector`() {
        // ICAO Doc 9303 Part 11, Appendix D: MRZ information "L898902C<369080619406236".
        val seed = org.jmrtd.Util.computeKeySeed("L898902C<", "690806", "940623", "SHA-1", true)
        assertEquals("239AB9CB282DAF66231DC5A4DF6BFBAE", com.samtex.eidreader.core.util.Hex.encode(seed))
        val kEnc = org.jmrtd.Util.deriveKey(seed, org.jmrtd.Util.ENC_MODE)
        val kMac = org.jmrtd.Util.deriveKey(seed, org.jmrtd.Util.MAC_MODE)
        // The ICAO values are DES-parity-adjusted; JMRTD leaves parity bits as derived (DES ignores them).
        fun noParity(b: ByteArray) = b.copyOf(16).map { (it.toInt() and 0xFE).toByte() }.toByteArray()
        fun hex(s: String) = com.samtex.eidreader.core.util.Hex.decode(s)
        assertEquals(com.samtex.eidreader.core.util.Hex.encode(noParity(hex("AB94FDECF2674FDFB9B391F85D7F76F2"))), com.samtex.eidreader.core.util.Hex.encode(noParity(kEnc.encoded)))
        assertEquals(com.samtex.eidreader.core.util.Hex.encode(noParity(hex("7962D9ECE03D1ACD4C76089DCE131543"))), com.samtex.eidreader.core.util.Hex.encode(noParity(kMac.encoded)))
    }
}
