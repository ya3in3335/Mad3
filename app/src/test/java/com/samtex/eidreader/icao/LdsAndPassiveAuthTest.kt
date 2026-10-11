package com.samtex.eidreader.icao

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.icao.lds.LdsFormatException
import com.samtex.eidreader.icao.lds.LdsParsers
import com.samtex.eidreader.icao.pa.Authenticity
import com.samtex.eidreader.icao.pa.CertificateParser
import com.samtex.eidreader.icao.pa.CheckStatus
import com.samtex.eidreader.icao.pa.HashCheck
import com.samtex.eidreader.icao.pa.PassiveAuthenticator
import com.samtex.eidreader.testing.SyntheticDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.util.Base64

class LdsAndPassiveAuthTest {
    private val doc = SyntheticDocument

    @Test fun `EF COM lists data groups`() {
        val com = LdsParsers.parseCom(doc.com)
        assertEquals("0107", com.ldsVersion)
        assertEquals(listOf(1, 2, 11, 12), com.dataGroups)
    }

    @Test fun `DG11 decodes documented fields and only lists unknown tags`() {
        val dg11 = LdsParsers.parseDg11(doc.dg11)
        assertEquals("تجريبي, عينة", dg11.fullName)
        assertEquals("19900101", dg11.fullDateOfBirth)
        assertEquals("SPECIMEN STREET 1", dg11.permanentAddress)
        assertEquals(listOf("5F7E"), dg11.undecodedTags)
        assertNull(dg11.personalNumber)
    }

    @Test fun `DG12 decodes BCD dates`() {
        val dg12 = LdsParsers.parseDg12(doc.dg12)
        assertEquals("SPECIMEN AUTHORITY", dg12.issuingAuthority)
        assertEquals("20220101", dg12.dateOfIssue)
    }

    @Test fun `DG2 portrait extraction`() {
        val p = LdsParsers.parseDg2(doc.dg2)!!
        assertEquals("image/jpeg", p.mimeType)
        assertFalse(p.isJpeg2000)
        assertTrue(p.data.contentEquals(doc.portraitBytes))
    }

    @Test fun `malformed LDS files are rejected`() {
        assertThrows(LdsFormatException::class.java) { LdsParsers.parseDg1(Hex.decode("6B00")) }
        assertThrows(LdsFormatException::class.java) { LdsParsers.parseDg1(Hex.decode("6103 5F1F")) }
        assertThrows(LdsFormatException::class.java) { LdsParsers.parseDg1(Hex.decode("6105 5F1F02 4142")) }
        assertThrows(LdsFormatException::class.java) { LdsParsers.parseDg11(Hex.decode("6101")) }
        assertThrows(LdsFormatException::class.java) { LdsParsers.parseDg2(Hex.decode("7500")) }
    }

    @Test fun `readable names`() {
        assertEquals("DOE, JOHN PAUL", LdsParsers.readableName("DOE<<JOHN<PAUL"))
        assertEquals("SINGLE", LdsParsers.readableName("SINGLE<<"))
    }

    @Test fun `passive authentication full chain`() {
        val groups = mapOf(1 to doc.dg1, 11 to doc.dg11)
        val pa = PassiveAuthenticator { listOf(doc.pki.otherCsca, doc.pki.csca) }
        val report = pa.verify(doc.sod(groups), groups)
        assertEquals(CheckStatus.VALID, report.signature)
        assertEquals(CheckStatus.VALID, report.chain)
        assertEquals(Authenticity.ESTABLISHED, report.authenticity)
        assertEquals("SHA-256", report.digestAlgorithm)
    }

    @Test fun `CSCA with matching name but wrong key is rejected`() {
        val groups = mapOf(1 to doc.dg1, 11 to doc.dg11)
        val report = PassiveAuthenticator { listOf(doc.pki.otherCsca) }.verify(doc.sod(groups), groups)
        assertEquals(CheckStatus.INVALID, report.chain)
        assertEquals(Authenticity.FAILED, report.authenticity)
    }

    @Test fun `data group missing from SOD or altered`() {
        val sod = doc.sod(mapOf(1 to doc.dg1, 11 to doc.dg11))
        val report = PassiveAuthenticator { listOf(doc.pki.csca) }.verify(sod, mapOf(1 to doc.dg1, 12 to doc.dg12))
        assertEquals(HashCheck.NOT_IN_SOD, report.hashes[12])
        assertEquals(Authenticity.FAILED, report.authenticity)

        val altered = doc.dg1.copyOf().also { it[10] = 0 }
        assertEquals(HashCheck.MISMATCH, PassiveAuthenticator { emptyList() }.verify(sod, mapOf(1 to altered)).hashes[1])
    }

    @Test fun `tampered SOD signature is detected`() {
        val groups = mapOf(1 to doc.dg1, 11 to doc.dg11)
        val sod = doc.sod(groups)
        // Flip a byte inside the signature value (last bytes of the CMS structure).
        val tampered = sod.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 0x01).toByte() }
        val report = PassiveAuthenticator { listOf(doc.pki.csca) }.verify(tampered, groups)
        assertEquals(CheckStatus.INVALID, report.signature)
        assertEquals(Authenticity.FAILED, report.authenticity)
    }

    @Test fun `missing or garbage SOD makes verification unavailable`() {
        assertEquals(CheckStatus.UNAVAILABLE, PassiveAuthenticator { emptyList() }.verify(null, emptyMap()).signature)
        assertEquals(Authenticity.NOT_ESTABLISHED, PassiveAuthenticator { emptyList() }.verify(Hex.decode("770100"), mapOf(1 to doc.dg1)).authenticity)
    }

    @Test fun `certificate parser accepts DER and PEM and flags CA certificates`() {
        val der = doc.pki.csca.encoded
        assertTrue(CertificateParser.isCaCertificate(CertificateParser.parse(der).single()))
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END CERTIFICATE-----\n"
        assertEquals(1, CertificateParser.parse(pem.toByteArray()).size)
        assertFalse(CertificateParser.isCaCertificate(doc.pki.ds))
        assertThrows(CertificateException::class.java) { CertificateParser.parse("not a cert".toByteArray()) }
    }
}
