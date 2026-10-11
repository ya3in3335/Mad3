package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.ApduParser
import com.samtex.eidreader.nfc.apdu.ApduPolicy
import com.samtex.eidreader.nfc.apdu.ApduValidator
import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.apdu.ResponseApdu
import com.samtex.eidreader.nfc.apdu.TlvParseException
import com.samtex.eidreader.nfc.apdu.TransportLimits
import com.samtex.eidreader.nfc.apdu.ValidationIssue
import com.samtex.eidreader.nfc.protocol.SecurityInfoDecoder
import com.samtex.eidreader.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyValidatorTlvTest {

    @Test fun `documented read-only commands are allowed`() {
        listOf(
            ApduCommands.selectByAid(ApduCommands.AID_ICAO_EMRTD),
            ApduCommands.selectEf(0x011C),
            ApduCommands.selectMasterFile(),
            ApduCommands.readBinary(0, 4),
            CommandApdu(0x00, 0xCA, 0x9F, 0x7F, ne = 256),
            CommandApdu(0x00, 0xB2, 0x01, 0x0C, ne = 256),
        ).forEach { assertTrue(it.toHex(), ApduPolicy.evaluate(it).allowed) }
    }

    @Test fun `authentication PIN write and secure messaging are denied`() {
        val denied = listOf(
            CommandApdu(0x00, 0x20, 0x00, 0x80, Hex.decode("31323334")), // VERIFY PIN
            CommandApdu(0x00, 0x24, 0x00, 0x80, Hex.decode("3132333435363738")), // CHANGE REFERENCE DATA
            CommandApdu(0x00, 0x2C, 0x03, 0x80), // RESET RETRY COUNTER
            CommandApdu(0x00, 0x22, 0xC1, 0xA4, Hex.decode("800A04007F00070202040202")), // MSE:Set AT (PACE)
            CommandApdu(0x10, 0x86, 0x00, 0x00, Hex.decode("7C00"), ne = 256), // GENERAL AUTHENTICATE (chained)
            CommandApdu(0x00, 0x86, 0x00, 0x00, Hex.decode("7C00"), ne = 256),
            CommandApdu(0x00, 0x82, 0x00, 0x00, ByteArray(40)), // EXTERNAL AUTHENTICATE
            CommandApdu(0x00, 0x84, 0x00, 0x00, ne = 8), // GET CHALLENGE
            CommandApdu(0x00, 0xD6, 0x00, 0x00, byteArrayOf(1)), // UPDATE BINARY
            CommandApdu(0x00, 0xE4, 0x00, 0x00), // DELETE FILE
            CommandApdu(0x0C, 0xB0, 0x00, 0x00, ne = 4), // secure messaging
            CommandApdu(0x80, 0xCA, 0x00, 0x00, ne = 4), // proprietary class
            CommandApdu(0x01, 0xB0, 0x00, 0x00, ne = 4), // logical channel 1
            CommandApdu(0x00, 0x12, 0x00, 0x00), // unknown INS
            CommandApdu(0x00, 0xA4, 0x7F, 0x00), // undocumented SELECT P1
        )
        denied.forEach {
            val decision = ApduPolicy.evaluate(it)
            assertFalse("${it.toHex()} must be denied", decision.allowed)
            assertTrue(decision.reason.isNotBlank())
        }
    }

    @Test fun `validator checks ISO rules and transport limits`() {
        val limits = TransportLimits(maxTransceiveLength = 20, extendedLengthSupported = false)
        assertTrue(ApduValidator.validate(ApduCommands.readBinary(0, 4), limits).isEmpty())

        val tooLong = CommandApdu(0x00, 0xA4, 0x04, 0x00, ByteArray(30))
        assertTrue(ApduValidator.hasErrors(ApduValidator.validate(tooLong, limits)))

        val extended = CommandApdu(0x00, 0xB0, 0x00, 0x00, ne = 1000)
        assertTrue(ApduValidator.validate(extended, limits).any { it.message.contains("Extended") })

        val badIns = CommandApdu(0x00, 0x6A, 0x00, 0x00)
        assertTrue(ApduValidator.hasErrors(ApduValidator.validate(badIns, null)))
        assertTrue(ApduValidator.hasErrors(ApduValidator.validate(CommandApdu(0xFF, 0xB0, 0, 0), null)))

        val bigLe = ApduValidator.validate(ApduCommands.readBinary(0, 200), limits)
        assertEquals(ValidationIssue.Severity.WARNING, bigLe.single().severity)
    }

    @Test fun `BER-TLV parsing of nested structures and long lengths`() {
        val nodes = BerTlv.parse(Fixtures.EF_CARD_ACCESS)
        assertEquals(1, nodes.size)
        assertEquals(0x31, nodes[0].tag)
        val seq = nodes[0].children.single()
        assertEquals(3, seq.children.size)
        assertEquals("0.4.0.127.0.7.2.2.4.2.2", BerTlv.decodeOid(seq.children[0].value))

        val longForm = Hex.decode("5F1F 81 80" + "AA".repeat(128))
        val parsed = BerTlv.parse(longForm).single()
        assertEquals(0x5F1F, parsed.tag)
        assertEquals(128, parsed.length)
        assertEquals(22, BerTlv.encodedLength(Fixtures.EF_CARD_ACCESS.copyOfRange(0, 4)))
        assertEquals(132, BerTlv.encodedLength(longForm))
    }

    @Test fun `malformed TLV is rejected`() {
        assertThrows(TlvParseException::class.java) { BerTlv.parse(Hex.decode("30 05 01 02")) }
        assertThrows(TlvParseException::class.java) { BerTlv.parse(Hex.decode("30 80 00 00")) }
        assertThrows(TlvParseException::class.java) { BerTlv.parse(Hex.decode("5F")) }
        assertNull(BerTlv.parseOrNull(Hex.decode("30 05 01")))
        assertNull(BerTlv.encodedLength(Hex.decode("31")))
    }

    @Test fun `DER integers`() {
        assertEquals(13L, BerTlv.decodeInteger(byteArrayOf(0x0D)))
        assertEquals(-1L, BerTlv.decodeInteger(byteArrayOf(0xFF.toByte())))
        assertEquals(256L, BerTlv.decodeInteger(byteArrayOf(0x01, 0x00)))
    }

    @Test fun `EF CardAccess security infos are decoded`() {
        val info = SecurityInfoDecoder.decode(Fixtures.EF_CARD_ACCESS).single()
        assertEquals("PACE", info.family)
        assertEquals("id-PACE-ECDH-GM-AES-CBC-CMAC-128", info.protocolName)
        assertEquals(2L, info.version)
        assertEquals(13L, info.parameterId)
        assertEquals("brainpoolP256r1", info.parameterName)
    }

    @Test fun `EF CardAccess that is not a SET fails`() {
        assertThrows(TlvParseException::class.java) { SecurityInfoDecoder.decode(Hex.decode("3000")) }
    }

    @Test fun `parser describes commands and responses`() {
        val cmd = ApduParser.describe(ApduCommands.selectByAid(ApduCommands.AID_ICAO_EMRTD))
        assertEquals("SELECT", cmd.instruction)
        assertTrue(cmd.notes.any { it.contains("AID") })
        val rsp = ApduParser.describe(ResponseApdu(Fixtures.EF_CARD_ACCESS + byteArrayOf(0x90.toByte(), 0x00)))
        assertEquals(22, rsp.dataLength)
        assertTrue(rsp.status.isSuccess)
        assertTrue(rsp.tlv!!.startsWith("31 [20]"))
        val ascii = ApduParser.describe(ResponseApdu(Hex.decode(Fixtures.ALGERIA_HISTORICAL_HEX + "9000")))
        assertEquals("AlgeriaEIDv2", ascii.ascii)
    }
}
