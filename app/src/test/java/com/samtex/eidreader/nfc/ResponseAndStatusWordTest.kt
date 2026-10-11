package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.ResponseApdu
import com.samtex.eidreader.nfc.apdu.StatusCategory
import com.samtex.eidreader.nfc.apdu.StatusWords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseAndStatusWordTest {

    @Test fun `response with data is split into data and status`() {
        val r = ResponseApdu(Hex.decode("3114 9000"))
        assertArrayEquals(Hex.decode("3114"), r.data)
        assertEquals(0x90, r.sw1)
        assertEquals(0x00, r.sw2)
        assertTrue(r.isSuccess)
    }

    @Test fun `status only response`() {
        val r = ResponseApdu(Hex.decode("6A82"))
        assertEquals(0, r.dataLength)
        assertEquals(0x6A82, r.sw)
        assertFalse(r.isSuccess)
    }

    @Test fun `empty and one byte responses are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ResponseApdu(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { ResponseApdu(byteArrayOf(0x90.toByte())) }
    }

    @Test fun `required status words are interpreted`() {
        val expected = mapOf(
            0x9000 to "Success",
            0x6A82 to "not found",
            0x6982 to "Security status not satisfied",
            0x6700 to "Wrong length",
            0x6D00 to "Instruction code not supported",
            0x6E00 to "Class not supported",
        )
        expected.forEach { (sw, text) ->
            val info = StatusWords.interpret(sw)
            assertTrue("$sw → ${info.description}", info.description.contains(text, ignoreCase = true))
        }
        assertEquals(StatusCategory.NORMAL, StatusWords.interpret(0x9000).category)
        assertEquals(StatusCategory.CHECKING_ERROR, StatusWords.interpret(0x6982).category)
        assertTrue(StatusWords.interpret(0x6982).isSecurityRelated)
    }

    @Test fun `parametrised status words`() {
        val more = StatusWords.interpret(0x6110)
        assertEquals(16, more.indicatedLength)
        assertEquals(StatusCategory.NORMAL, more.category)
        assertEquals(256, StatusWords.interpret(0x6100).indicatedLength)

        val wrongLe = StatusWords.interpret(0x6C08)
        assertEquals(8, wrongLe.indicatedLength)

        val counter = StatusWords.interpret(0x63C2)
        assertEquals(2, counter.counter)
        assertTrue(counter.isSecurityRelated)
    }

    @Test fun `ranges proprietary and invalid values`() {
        assertEquals(StatusCategory.WARNING, StatusWords.interpret(0x6299).category)
        assertEquals(StatusCategory.EXECUTION_ERROR, StatusWords.interpret(0x6512).category)
        assertEquals(StatusCategory.PROPRIETARY, StatusWords.interpret(0x9100).category)
        assertEquals(StatusCategory.INVALID, StatusWords.interpret(0x6000).category)
        assertEquals(StatusCategory.INVALID, StatusWords.interpret(0x1234).category)
        assertEquals("6A82", StatusWords.interpret(0x6A82).hex)
    }
}
