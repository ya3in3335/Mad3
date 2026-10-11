package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.nfc.ats.AtqaSakDecoder
import com.samtex.eidreader.nfc.ats.UidSize
import com.samtex.eidreader.nfc.classifier.CardClassifier
import com.samtex.eidreader.nfc.classifier.CardFamily
import com.samtex.eidreader.nfc.classifier.EidSignatureDetector
import com.samtex.eidreader.nfc.classifier.IcManufacturers
import com.samtex.eidreader.nfc.classifier.Provenance
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.nfc.protocol.ProtocolProbeResult
import com.samtex.eidreader.nfc.tag.NfcTechnology
import com.samtex.eidreader.nfc.tag.TagSnapshot
import com.samtex.eidreader.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardClassifierTest {

    private val classifier = CardClassifier()

    @Test fun `ATQA bytes from Android are decoded LSB first`() {
        val atqa = AtqaSakDecoder.decodeAtqa(ByteString.of(0x08, 0x00))!!
        assertEquals(0x0008, atqa.value)
        assertEquals("0x0008", atqa.hex)
        assertEquals(UidSize.SINGLE, atqa.uidSize)
        assertTrue(atqa.compliant)

        val ul = AtqaSakDecoder.decodeAtqa(ByteString.of(0x44, 0x00))!!
        assertEquals(UidSize.DOUBLE, ul.uidSize)
        assertEquals(0x0044, ul.value)

        val desfire = AtqaSakDecoder.decodeAtqa(ByteString.of(0x44, 0x03))!!
        assertEquals(0x0344, desfire.value)
        assertEquals(3, desfire.proprietaryCoding)

        assertNull(AtqaSakDecoder.decodeAtqa(ByteString.of(0x08)))
        assertFalse(AtqaSakDecoder.decodeAtqa(ByteString.of(0x00, 0x00))!!.compliant)
    }

    @Test fun `SAK bits`() {
        val sak = AtqaSakDecoder.decodeSak(0x20)
        assertTrue(sak.iso14443_4Compliant)
        assertFalse(sak.uidIncomplete)
        assertEquals("0x20", sak.hex)
        assertFalse(AtqaSakDecoder.decodeSak(0x08).iso14443_4Compliant)
        assertTrue(AtqaSakDecoder.decodeSak(0x04).uidIncomplete)
        assertTrue(AtqaSakDecoder.decodeSak(0x28).iso14443_4Compliant)
    }

    @Test fun `AlgeriaEIDv2 signature is detected`() {
        val sig = EidSignatureDetector.detect(ByteString.fromHex(Fixtures.ALGERIA_HISTORICAL_HEX))!!
        assertEquals("AlgeriaEIDv2", sig.text)
        assertEquals("Algeria", sig.country)
        assertEquals(2, sig.generation)
        assertTrue(sig.isKnown)
        assertEquals(Fixtures.ALGERIA_HISTORICAL_HEX, sig.hex)
    }

    @Test fun `signature embedded after binary bytes is still detected`() {
        val sig = EidSignatureDetector.detect(ByteString.fromHex("8001" + Fixtures.ALGERIA_HISTORICAL_HEX))
        assertEquals("AlgeriaEIDv2", sig?.text)
    }

    @Test fun `no false signature on binary or unrelated text`() {
        assertNull(EidSignatureDetector.detect(ByteString.fromHex("8031C073C82110")))
        assertNull(EidSignatureDetector.detect(ByteString.of(*"JCOP41V22".map { it.code }.toIntArray())))
        assertNull(EidSignatureDetector.detect(null))
    }

    @Test fun `unknown country signature is reported as not known`() {
        val sig = EidSignatureDetector.detect(ByteString.of(*"ExampleEIDv1".map { it.code }.toIntArray()))!!
        assertFalse(sig.isKnown)
        assertEquals(1, sig.generation)
    }

    @Test fun `random UID is not attributed to Fujitsu`() {
        val r = IcManufacturers.identify(ByteString.fromHex("08A1B2C3"), UidSize.SINGLE)
        assertTrue(r is IcManufacturers.Result.RandomUid)
        assertEquals("Fujitsu", (r as IcManufacturers.Result.RandomUid).misreportedAs)
    }

    @Test fun `seven byte UID identifies manufacturer`() {
        val r = IcManufacturers.identify(ByteString.fromHex("04112233445566"), UidSize.DOUBLE)
        assertEquals(IcManufacturers.Result.Identified(0x04, "NXP Semiconductors"), r)
        assertTrue(IcManufacturers.identify(ByteString.fromHex("EE112233445566"), UidSize.DOUBLE) is IcManufacturers.Result.UnknownCode)
        assertTrue(IcManufacturers.identify(ByteString.fromHex("12345678"), UidSize.SINGLE) is IcManufacturers.Result.NotDerivable)
        assertTrue(IcManufacturers.identify(null, null) is IcManufacturers.Result.NotDerivable)
    }

    @Test fun `Algerian eID fixture classification separates verified and inferred`() {
        val c = classifier.classify(Fixtures.algeriaSnapshot)
        assertEquals(CardFamily.NATIONAL_EID, c.family)
        assertTrue(c.cardType.startsWith("Algerian electronic identity card"))
        assertEquals(Provenance.INFERRED, c.cardTypeProvenance)
        assertTrue(c.isoDepCapable)
        assertTrue("ISO/IEC 14443-4 (ISO-DEP)" in c.isoStandards)
        assertTrue("ISO/IEC 14443-3 Type A" in c.isoStandards)
        assertEquals("0x0008", c.atqa?.hex)
        assertEquals("0x20", c.sak?.hex)
        assertEquals("AlgeriaEIDv2", c.historicalBytes.ascii)
        assertEquals("Not identifiable (random UID)", c.manufacturer.value)

        val signature = c.findings.single { it.key == "signature" }
        assertEquals(Provenance.VERIFIED, signature.provenance)
        assertEquals(Provenance.INFERRED, c.findings.single { it.key == "signature.meaning" }.provenance)
        assertEquals(Provenance.RESTRICTED, c.findings.single { it.key == "ats" }.provenance)
        // No APDU probes yet → no 7816-4 / ICAO claim
        assertFalse(c.isoStandards.any { it.contains("7816") || it.contains("9303") })
    }

    @Test fun `probe results enrich classification`() {
        val probes = listOf(
            ProtocolProbeResult("icao.emrtd", "eMRTD", "ref", ProbeStatus.AVAILABLE, "present"),
        )
        val c = classifier.classify(Fixtures.algeriaSnapshot, probes)
        assertTrue(c.cardType.contains("ICAO eMRTD application present"))
        assertTrue("ICAO Doc 9303" in c.isoStandards)
        assertTrue("ISO/IEC 7816-4 (APDU)" in c.isoStandards)
    }

    @Test fun `ISO-DEP card without signature but with eMRTD is an eMRTD`() {
        val snapshot = Fixtures.algeriaSnapshot.copy(isoDep = Fixtures.algeriaSnapshot.isoDep!!.copy(historicalBytes = null))
        val probes = listOf(ProtocolProbeResult("icao.emrtd", "eMRTD", "ref", ProbeStatus.AVAILABLE, "present"))
        assertEquals(CardFamily.EMRTD, classifier.classify(snapshot, probes).family)
        assertEquals(CardFamily.ISO_DEP_SMART_CARD, classifier.classify(snapshot).family)
    }

    @Test fun `non ISO-DEP tags`() {
        val ul = classifier.classify(Fixtures.ultralightSnapshot)
        assertEquals(CardFamily.MIFARE_ULTRALIGHT, ul.family)
        assertFalse(ul.isoDepCapable)
        assertEquals("NXP Semiconductors", ul.manufacturer.value)
        assertEquals(Provenance.INFERRED, ul.manufacturer.provenance)

        val felica = classifier.classify(TagSnapshot(uid = ByteString.fromHex("0102030405060708"), technologies = listOf(NfcTechnology.NFC_F)))
        assertEquals(CardFamily.FELICA, felica.family)

        val empty = classifier.classify(TagSnapshot(uid = null, technologies = emptyList(), unknownTechnologies = listOf("Vendor")))
        assertEquals(CardFamily.UNKNOWN, empty.family)
        assertEquals(listOf("Vendor"), empty.technologies)
    }

    @Test fun `ISO 15693 manufacturer from UID`() {
        // Android NfcV UID is LSB first: ... 04 E0 → manufacturer 04 (NXP)
        val snapshot = TagSnapshot(uid = ByteString.fromHex("11223344556604E0"), technologies = listOf(NfcTechnology.NFC_V))
        val c = classifier.classify(snapshot)
        assertEquals(CardFamily.ISO15693, c.family)
        assertNotNull(c.manufacturer)
        assertEquals("NXP Semiconductors", c.manufacturer.value)
    }
}
