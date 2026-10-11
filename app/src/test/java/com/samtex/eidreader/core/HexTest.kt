package com.samtex.eidreader.core

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.core.util.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HexTest {
    @Test fun `encode produces upper-case digits with optional separator`() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("000F7F80FF", Hex.encode(bytes))
        assertEquals("00 0F 7F 80 FF", Hex.encode(bytes, " "))
        assertEquals("", Hex.encode(ByteArray(0)))
    }

    @Test fun `decode accepts separators prefixes and mixed case`() {
        val expected = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x02, 0x47)
        assertArrayEquals(expected, Hex.decode("A000000247"))
        assertArrayEquals(expected, Hex.decode("a0 00 00 02 47"))
        assertArrayEquals(expected, Hex.decode("A0:00:00:02:47"))
        assertArrayEquals(expected, Hex.decode("0xA0 0x00 0x00 0x02 0x47"))
        assertArrayEquals(expected, Hex.decode("  a0-00-00-02-47\n"))
    }

    @Test fun `round trip of all byte values`() {
        val all = ByteArray(256) { it.toByte() }
        assertArrayEquals(all, Hex.decode(Hex.encode(all)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd number of digits is rejected`() {
        Hex.decode("ABC")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid character is rejected`() {
        Hex.decode("0G")
    }

    @Test fun `decodeOrNull returns null for invalid input and empty array for blank`() {
        assertNull(Hex.decodeOrNull("XYZ"))
        assertEquals(0, Hex.decodeOrNull("")!!.size)
    }

    @Test fun `ascii rendering of the Algerian signature fixture`() {
        val bytes = Hex.decode("416C67657269614549447632")
        assertTrue(Hex.isPrintableAscii(bytes))
        assertEquals("AlgeriaEIDv2", Hex.toPrintableAscii(bytes))
    }

    @Test fun `non printable bytes are replaced by dots`() {
        val bytes = byteArrayOf(0x41, 0x00, 0x42, 0x7F)
        assertFalse(Hex.isPrintableAscii(bytes))
        assertEquals("A.B.", Hex.toPrintableAscii(bytes))
        assertFalse(Hex.isPrintableAscii(ByteArray(0)))
    }

    @Test fun `u16 and byte formatting`() {
        assertEquals("0008", Hex.u16(8))
        assertEquals("9000", Hex.u16(0x9000))
        assertEquals("20", Hex.byte(0x20))
    }

    @Test fun `ByteString has value semantics and is immutable`() {
        val source = byteArrayOf(1, 2, 3)
        val a = ByteString.of(source)
        source[0] = 9
        assertEquals(ByteString.fromHex("010203"), a)
        assertEquals(a.hashCode(), ByteString.fromHex("010203").hashCode())
        val copy = a.toByteArray()
        copy[0] = 7
        assertEquals(1, a[0])
        assertEquals(ByteString.fromHex("0203"), a.slice(1))
    }
}
