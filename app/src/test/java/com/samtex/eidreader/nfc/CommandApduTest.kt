package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCase
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.CommandApdu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandApduTest {

    private fun hex(apdu: CommandApdu) = Hex.encode(apdu.encode())

    @Test fun `case 1 header only`() {
        val apdu = CommandApdu(0x00, 0xA4, 0x00, 0x00)
        assertEquals(ApduCase.CASE_1, apdu.case)
        assertEquals("00A40000", hex(apdu))
    }

    @Test fun `case 2 short with Le 256 encoded as 00`() {
        assertEquals("00B0000000", hex(CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 256)))
        val apdu = CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 4)
        assertEquals(ApduCase.CASE_2_SHORT, apdu.case)
        assertEquals("00B0000004", hex(apdu))
    }

    @Test fun `case 3 short SELECT eMRTD AID`() {
        val apdu = ApduCommands.selectByAid(ApduCommands.AID_ICAO_EMRTD)
        assertEquals(ApduCase.CASE_3_SHORT, apdu.case)
        assertEquals("00A4040C07A0000002471001", hex(apdu))
    }

    @Test fun `case 4 short`() {
        val apdu = CommandApdu(0x00, 0xA4, 0x04, 0x00, Hex.decode("A0000002471001"), ne = 256)
        assertEquals(ApduCase.CASE_4_SHORT, apdu.case)
        assertEquals("00A4040007A000000247100100", hex(apdu))
    }

    @Test fun `extended cases`() {
        val c2e = CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 65536)
        assertEquals(ApduCase.CASE_2_EXTENDED, c2e.case)
        assertEquals("00B00000000000", hex(c2e))

        val data = ByteArray(300) { 0x11 }
        val c3e = CommandApdu(0x00, 0xD6, 0x00, 0x00, data)
        assertEquals(ApduCase.CASE_3_EXTENDED, c3e.case)
        assertEquals("00D6000000012C", hex(c3e).substring(0, 14))
        assertEquals(4 + 3 + 300, c3e.encode().size)

        val c4e = CommandApdu(0x00, 0xB0, 0x00, 0x00, byteArrayOf(1), ne = 1000)
        assertEquals(ApduCase.CASE_4_EXTENDED, c4e.case)
        assertEquals("00B00000000001" + "01" + "03E8", hex(c4e))
    }

    @Test fun `decode round trips every case`() {
        val samples = listOf(
            CommandApdu(0x00, 0xA4, 0x00, 0x00),
            CommandApdu(0x00, 0xB0, 0x00, 0x10, ne = 200),
            CommandApdu(0x00, 0xB0, 0x00, 0x10, ne = 256),
            ApduCommands.selectEf(0x011C),
            CommandApdu(0x00, 0xA4, 0x04, 0x00, Hex.decode("A0000002471001"), ne = 256),
            CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 65536),
            CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 1000),
            CommandApdu(0x00, 0xD6, 0x00, 0x00, ByteArray(300) { it.toByte() }),
            CommandApdu(0x00, 0xD6, 0x00, 0x00, ByteArray(300) { it.toByte() }, ne = 65536),
        )
        samples.forEach { assertEquals(it, CommandApdu.decode(it.encode())) }
    }

    @Test fun `decode rejects malformed input`() {
        assertThrows(IllegalArgumentException::class.java) { CommandApdu.decode(byteArrayOf(0, 1, 2)) }
        // Lc = 5 but only 2 data bytes
        assertThrows(IllegalArgumentException::class.java) { CommandApdu.decode(Hex.decode("00A40400050102")) }
        // Extended marker without length
        assertThrows(IllegalArgumentException::class.java) { CommandApdu.decode(Hex.decode("00B000000000")) }
    }

    @Test fun `field ranges are validated`() {
        assertThrows(IllegalArgumentException::class.java) { CommandApdu(0x100, 0, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { CommandApdu(0, 0xB0, 0, 0, ne = 0) }
        assertThrows(IllegalArgumentException::class.java) { CommandApdu(0, 0xB0, 0, 0, ne = 65537) }
        assertThrows(IllegalArgumentException::class.java) { ApduCommands.readBinary(0x8000, 4) }
        assertThrows(IllegalArgumentException::class.java) { ApduCommands.selectByAid(byteArrayOf(1, 2)) }
    }

    @Test fun `secure messaging bits are detected`() {
        assertTrue(CommandApdu(0x0C, 0xB0, 0, 0, ne = 1).usesSecureMessaging)
        assertFalse(CommandApdu(0x00, 0xB0, 0, 0, ne = 1).usesSecureMessaging)
        assertFalse(CommandApdu(0x80, 0xCA, 0, 0, ne = 1).usesSecureMessaging)
    }

    @Test fun `read binary encodes 15 bit offsets`() {
        assertEquals("00B0012C10", hex(ApduCommands.readBinary(300, 16)))
    }
}
