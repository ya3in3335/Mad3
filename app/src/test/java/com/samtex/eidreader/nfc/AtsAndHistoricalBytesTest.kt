package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.ats.AtsAnalyzer
import com.samtex.eidreader.nfc.ats.HistoricalBytesFormat
import com.samtex.eidreader.nfc.ats.HistoricalBytesParser
import com.samtex.eidreader.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtsAndHistoricalBytesTest {

    @Test fun `full ATS with interface bytes and Algerian historical bytes`() {
        // TL=0x11 (17), T0=0x78 (TA,TB,TC present, FSCI=8 → 256), TA=0x77, TB=0x81, TC=0x02, + 12 historical bytes
        val ats = Hex.decode("11 78 77 81 02" + Fixtures.ALGERIA_HISTORICAL_HEX)
        val result = AtsAnalyzer.parse(ats)
        assertTrue(result is AtsAnalyzer.Result.Success)
        val info = (result as AtsAnalyzer.Result.Success).info
        assertEquals(256, info.fsc)
        assertEquals(listOf(106, 212, 424, 848), info.supportedBitRatesPiccToPcd)
        assertEquals(listOf(106, 212, 424, 848), info.supportedBitRatesPcdToPicc)
        assertFalse(info.sameBitRateBothDirections)
        assertEquals(8, info.fwi)
        assertEquals(1, info.sfgi)
        assertTrue(info.cidSupported)
        assertFalse(info.nadSupported)
        assertEquals(ByteString.fromHex(Fixtures.ALGERIA_HISTORICAL_HEX), info.historicalBytes)
        assertEquals(302.06, info.fwtMicros / 256, 0.01)
        assertTrue(info.warnings.isEmpty())
    }

    @Test fun `ATS defaults when only TL is present`() {
        val info = (AtsAnalyzer.parse(byteArrayOf(0x01)) as AtsAnalyzer.Result.Success).info
        assertEquals(32, info.fsc)
        assertEquals(4, info.fwi)
        assertTrue(info.historicalBytes.isEmpty())
    }

    @Test fun `ATS with absent interface bytes uses defaults`() {
        // T0=0x05 → FSCI 5 (64 bytes), no TA/TB/TC, 2 historical bytes
        val info = (AtsAnalyzer.parse(Hex.decode("04 05 80 00")) as AtsAnalyzer.Result.Success).info
        assertEquals(64, info.fsc)
        assertEquals(4, info.fwi)
        assertTrue(info.cidSupported)
        assertEquals(ByteString.fromHex("8000"), info.historicalBytes)
    }

    @Test fun `ATS length mismatches and truncation are reported`() {
        assertTrue(AtsAnalyzer.parse(ByteArray(0)) is AtsAnalyzer.Result.Failure)
        assertTrue(AtsAnalyzer.parse(Hex.decode("05 78 77")) is AtsAnalyzer.Result.Failure)
        // TL says 3 but T0 announces TA, TB and TC
        assertTrue(AtsAnalyzer.parse(Hex.decode("03 70 77")) is AtsAnalyzer.Result.Failure)
        // CRC included → explicit message
        val crc = AtsAnalyzer.parse(Hex.decode("03 05 80 AB CD")) as AtsAnalyzer.Result.Failure
        assertTrue(crc.reason.contains("CRC"))
    }

    @Test fun `ATS RFU values produce warnings`() {
        val info = (AtsAnalyzer.parse(Hex.decode("03 2D F0")) as AtsAnalyzer.Result.Success).info
        assertNull(info.fsc)
        assertEquals(4, info.fwi)
        assertTrue(info.warnings.isNotEmpty())
    }

    @Test fun `Algerian historical bytes are proprietary ASCII`() {
        val info = HistoricalBytesParser.parse(ByteString.fromHex(Fixtures.ALGERIA_HISTORICAL_HEX))
        assertEquals(HistoricalBytesFormat.PROPRIETARY, info.format)
        assertEquals("AlgeriaEIDv2", info.ascii)
        assertEquals(0x41, info.categoryIndicator)
        assertTrue(info.compactTlv.isEmpty())
    }

    @Test fun `category 80 compact TLV`() {
        // 80 | 31 C0 (card service data) | 73 C8 21 10 (card capabilities)
        val info = HistoricalBytesParser.parse(ByteString.fromHex("8031C073C821 10"))
        assertEquals(HistoricalBytesFormat.COMPACT_TLV, info.format)
        assertEquals(2, info.compactTlv.size)
        assertEquals("Card service data", info.compactTlv[0].tagName)
        assertEquals(ByteString.fromHex("C821 10"), info.compactTlv[1].value)
        assertNull(info.ascii)
    }

    @Test fun `category 00 requires status indicator`() {
        val info = HistoricalBytesParser.parse(ByteString.fromHex("00 31C0 019000"))
        assertEquals(HistoricalBytesFormat.COMPACT_TLV_WITH_STATUS, info.format)
        assertEquals(ByteString.fromHex("019000"), info.statusIndicator)
        assertEquals(1, info.compactTlv.size)

        val short = HistoricalBytesParser.parse(ByteString.fromHex("0001"))
        assertTrue(short.warnings.isNotEmpty())
    }

    @Test fun `truncated compact TLV object is reported`() {
        val info = HistoricalBytesParser.parse(ByteString.fromHex("80 35 01 02"))
        assertTrue(info.warnings.any { it.contains("truncated") })
    }

    @Test fun `empty or absent historical bytes`() {
        assertEquals(HistoricalBytesFormat.EMPTY, HistoricalBytesParser.parse(null).format)
        assertEquals(HistoricalBytesFormat.EMPTY, HistoricalBytesParser.parse(ByteString.EMPTY).format)
    }
}
