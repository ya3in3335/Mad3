package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.nfc.ats.AtqbDecoder
import com.samtex.eidreader.nfc.classifier.CardClassifier
import com.samtex.eidreader.nfc.classifier.CardFamily
import com.samtex.eidreader.nfc.protocol.EfDirAdapter
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.nfc.transport.ApduChannel
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.Fixtures
import com.samtex.eidreader.testing.SimulatedTransport
import com.samtex.eidreader.testing.TestDeps
import com.samtex.eidreader.testing.VirtualIsoCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeBAndDirectoryTest {

    @Test fun `ATQB protocol info is decoded`() {
        // 77: all bit rates, different per direction; 81: frame 256 bytes, ISO 14443-4; 85: FWI 8, ADC 01, CID supported
        val info = AtqbDecoder.decode(ByteString.fromHex("00000000"), ByteString.fromHex("778185"))!!
        assertEquals(listOf(106, 212, 424, 848), info.supportedBitRatesPiccToPcd)
        assertEquals(256, info.maxFrameSize)
        assertTrue(info.iso14443_4Compliant)
        assertEquals(8, info.fwi)
        assertTrue(info.cidSupported)
        assertFalse(info.nadSupported)
        assertTrue(info.warnings.isEmpty())
        assertNull(AtqbDecoder.decode(ByteString.EMPTY, ByteString.fromHex("7781")))
        assertTrue(AtqbDecoder.decode(ByteString.EMPTY, ByteString.fromHex("08D1F0"))!!.warnings.size >= 2)
    }

    @Test fun `Type B card signature is read from the higher-layer response`() {
        val c = CardClassifier().classify(Fixtures.typeBSnapshot)
        assertEquals(CardFamily.NATIONAL_EID, c.family)
        assertEquals("AlgeriaEIDv2", c.signature?.text)
        assertTrue("ISO/IEC 14443-3 Type B" in c.isoStandards)
        assertNull(c.atqa)
        assertTrue(c.atqb!!.iso14443_4Compliant)
        assertEquals("Not identifiable", c.manufacturer.value)
        assertTrue(c.findings.any { it.key == "atqb.decoded" })
    }

    @Test fun `EF DIR templates are parsed without guessing`() {
        val dir = Hex.decode("61 0B 4F 07 A0000002471001 50 00" + "61 0D 4F 05 A000000999 50 04 54455354")
        val entries = EfDirAdapter.parse(dir)
        assertEquals(2, entries.size)
        assertEquals("A0000002471001", entries[0].aid)
        assertTrue(entries[0].knownAs!!.contains("ICAO"))
        assertEquals("TEST", entries[1].label)
        assertNull(entries[1].knownAs)
    }

    @Test fun `EF DIR adapter reads a transparent directory`() = runTest {
        val dir = Hex.decode("61 09 4F 07 A0000002471001")
        val card = VirtualIsoCard(files = mapOf(0x2F00 to dir))
        val t = SimulatedTransport { card.respond(it) }.also { it.connect(1000) }
        val result = EfDirAdapter().probe(ApduChannel(t, SessionLogger(FakeClock(), TestDeps.privacy)))
        assertEquals(ProbeStatus.AVAILABLE, result.status)
        assertTrue(result.findings.any { it.hex == "A0000002471001" })
        assertTrue(t.sent.all { it.startsWith("00A4") || it.startsWith("00B0") })
    }

    @Test fun `missing EF DIR is reported as not present`() = runTest {
        val card = VirtualIsoCard(files = emptyMap())
        val t = SimulatedTransport { card.respond(it) }.also { it.connect(1000) }
        val result = EfDirAdapter().probe(ApduChannel(t, SessionLogger(FakeClock(), TestDeps.privacy)))
        assertEquals(ProbeStatus.NOT_PRESENT, result.status)
    }
}
