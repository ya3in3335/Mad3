package com.samtex.eidreader.nfc

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.diagnostics.LogLevel
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.nfc.apdu.ApduCommands
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.protocol.EfReadResult
import com.samtex.eidreader.nfc.protocol.TransparentFileReader
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.nfc.session.SessionConfig
import com.samtex.eidreader.nfc.transport.ApduChannel
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.Fixtures
import com.samtex.eidreader.testing.SimulatedTransport
import com.samtex.eidreader.testing.TestDeps
import com.samtex.eidreader.testing.VirtualIsoCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** All tests in this class use SIMULATED transports; they do not validate real cards. */
class ChannelAndSessionTest {

    private fun logger() = SessionLogger(FakeClock(), TestDeps.privacy)

    private suspend fun connected(transport: SimulatedTransport): SimulatedTransport = transport.also { it.connect(1000) }

    @Test fun `GET RESPONSE chaining collects all data`() = runTest {
        val t = connected(
            SimulatedTransport { cmd ->
                when (Hex.encode(cmd)) {
                    "00CA9F7F00" -> Hex.decode("0102 6104")
                    "00C0000004" -> Hex.decode("03040506 6102")
                    "00C0000002" -> Hex.decode("0708 9000")
                    else -> Hex.decode("6D00")
                }
            },
        )
        val ex = ApduChannel(t, logger()).transmit(CommandApdu(0x00, 0xCA, 0x9F, 0x7F, ne = 256), "GET DATA", publicData = true)
        assertArrayEquals(Hex.decode("0102030405060708"), ex.response.data)
        assertTrue(ex.status.isSuccess)
        assertEquals(3, ex.transmissions)
    }

    @Test fun `wrong Le is corrected once using 6CXX`() = runTest {
        val t = connected(
            SimulatedTransport { cmd ->
                when (Hex.encode(cmd)) {
                    "00B0000010" -> Hex.decode("6C04")
                    "00B0000004" -> Hex.decode("AABBCCDD 9000")
                    else -> Hex.decode("6F00")
                }
            },
        )
        val ex = ApduChannel(t, logger()).transmit(ApduCommands.readBinary(0, 16), "READ", publicData = true)
        assertEquals(4, ex.command.ne)
        assertEquals(2, ex.transmissions)
        assertEquals(listOf("00B0000010", "00B0000004"), t.sent)
    }

    @Test fun `empty and truncated responses are malformed`() = runTest {
        for (raw in listOf(ByteArray(0), byteArrayOf(0x90.toByte()))) {
            val t = connected(SimulatedTransport { raw })
            try {
                ApduChannel(t, logger()).transmit(ApduCommands.selectMasterFile(), "SELECT", publicData = true)
                fail("expected failure")
            } catch (e: NfcEngineException) {
                assertEquals(ErrorKind.MALFORMED_RESPONSE, e.kind)
            }
        }
    }

    @Test fun `unexpected response length produces a warning`() = runTest {
        val t = connected(SimulatedTransport { Hex.decode("0102 9000") })
        val logger = logger()
        val ex = ApduChannel(t, logger).transmit(ApduCommands.selectEf(0x011C), "SELECT", publicData = true)
        assertTrue(ex.lengthWarning!!.contains("none were requested"))
        assertTrue(logger.snapshot().any { it.level == LogLevel.WARNING })

        val t2 = connected(SimulatedTransport { Hex.decode("010203 9000") })
        val ex2 = ApduChannel(t2, logger).transmit(ApduCommands.readBinary(0, 2), "READ", publicData = true)
        assertTrue(ex2.lengthWarning!!.contains("more than the requested"))
    }

    @Test fun `commands violating transport limits are never sent`() = runTest {
        val t = connected(SimulatedTransport(maxTransceiveLength = 10) { Hex.decode("9000") })
        try {
            ApduChannel(t, logger()).transmit(CommandApdu(0x00, 0xA4, 0x04, 0x0C, ByteArray(16)), "SELECT", publicData = true)
            fail("expected failure")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.PAYLOAD_TOO_LARGE, e.kind)
        }
        try {
            ApduChannel(t, logger()).transmit(CommandApdu(0x00, 0xB0, 0, 0, ne = 1000), "READ", publicData = true)
            fail("expected failure")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.EXTENDED_LENGTH_UNSUPPORTED, e.kind)
        }
        assertTrue(t.sent.isEmpty())
    }

    @Test fun `endless GET RESPONSE loop is bounded`() = runTest {
        val t = connected(SimulatedTransport { Hex.decode("00 6101") })
        try {
            ApduChannel(t, logger(), maxResponseChunks = 5).transmit(ApduCommands.readBinary(0, 1), "READ", publicData = true)
            fail("expected failure")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.MALFORMED_RESPONSE, e.kind)
        }
    }

    @Test fun `non public response data is redacted in the session log`() = runTest {
        val t = connected(SimulatedTransport { Hex.decode("4A4F484E 9000") })
        val logger = logger()
        ApduChannel(t, logger).transmit(ApduCommands.readBinary(0, 4), "READ", publicData = false)
        val text = logger.snapshot().joinToString { it.message }
        assertFalse(text.contains("4A 4F 48 4E"))
        assertTrue(text.contains("<4 bytes redacted>"))
    }

    @Test fun `session connects applies timeout and always closes`() = runTest {
        val manager = IsoDepSessionManager().apply { config = SessionConfig(transceiveTimeoutMs = 1234) }
        val t = SimulatedTransport { Hex.decode("9000") }
        val sw = manager.withSession(t, logger()) { ch -> ch.transmit(ApduCommands.selectMasterFile(), "SELECT MF", true).status.sw }
        assertEquals(0x9000, sw)
        assertEquals(1, t.connectCount)
        assertEquals(1234, t.lastTimeoutMs)
        assertEquals(1, t.closeCount)
        assertFalse(t.isConnected)
        assertFalse(manager.isBusy)
    }

    @Test fun `session closes after connection interruption`() = runTest {
        val manager = IsoDepSessionManager()
        val card = VirtualIsoCard(removeAfterCommands = 1)
        val t = SimulatedTransport { card.respond(it) }
        try {
            manager.withSession(t, logger()) { ch ->
                ch.transmit(ApduCommands.selectMasterFile(), "1", true)
                ch.transmit(ApduCommands.selectMasterFile(), "2", true)
            }
            fail("expected tag lost")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.TAG_LOST, e.kind)
            assertTrue(e.kind.isConnectionLoss)
        }
        assertEquals(1, t.closeCount)
        assertFalse(manager.isBusy)
    }

    @Test fun `connect failure releases the session`() = runTest {
        val manager = IsoDepSessionManager()
        val t = SimulatedTransport(connectFailure = NfcEngineException(ErrorKind.TAG_LOST, "gone")) { Hex.decode("9000") }
        try {
            manager.withSession(t, logger()) { }
            fail("expected failure")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.TAG_LOST, e.kind)
        }
        assertEquals(1, t.closeCount)
        assertFalse(manager.isBusy)
    }

    @Test fun `session timeout is enforced`() = runTest {
        val manager = IsoDepSessionManager().apply { config = SessionConfig(sessionTimeoutMs = 3_000) }
        val t = SimulatedTransport { delay(10_000); Hex.decode("9000") }
        try {
            manager.withSession(t, logger()) { ch -> ch.transmit(ApduCommands.selectMasterFile(), "slow", true) }
            fail("expected timeout")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.TIMEOUT, e.kind)
        }
        assertEquals(1, t.closeCount)
        assertFalse(manager.isBusy)
    }

    @Test fun `concurrent sessions are rejected`() = runTest {
        val manager = IsoDepSessionManager()
        val gate = CompletableDeferred<Unit>()
        val first = async {
            manager.withSession(SimulatedTransport { Hex.decode("9000") }, logger()) { gate.await() }
        }
        // Let the first session start.
        while (!manager.isBusy) delay(1)
        val second = SimulatedTransport { Hex.decode("9000") }
        try {
            manager.withSession(second, logger()) { }
            fail("expected busy")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.SESSION_BUSY, e.kind)
        }
        assertEquals(0, second.connectCount)
        gate.complete(Unit)
        first.await()
        assertFalse(manager.isBusy)
    }

    @Test fun `transparent file reader reads EF CardAccess in chunks`() = runTest {
        val card = VirtualIsoCard()
        val t = connected(SimulatedTransport(maxTransceiveLength = 12) { card.respond(it) })
        val result = TransparentFileReader.readTlvFile(ApduChannel(t, logger()), 0x011C, "EF.CardAccess")
        assertTrue(result is EfReadResult.Success)
        assertArrayEquals(Fixtures.EF_CARD_ACCESS, (result as EfReadResult.Success).content)
        assertFalse(result.truncated)
        // header (4) + chunks of 10 bytes: 10 + 8
        assertEquals(listOf("00A4020C02011C", "00B0000004", "00B000040A", "00B0000E08"), t.sent)
    }

    @Test fun `transparent file reader reports missing and protected files`() = runTest {
        val t = connected(SimulatedTransport { VirtualIsoCard(files = emptyMap()).respond(it) })
        val missing = TransparentFileReader.readTlvFile(ApduChannel(t, logger()), 0x011C, "EF.CardAccess")
        assertEquals(0x6A82, (missing as EfReadResult.SelectFailed).status.sw)

        val protectedCard = VirtualIsoCard(protectedFiles = setOf(0x011C))
        val t2 = connected(SimulatedTransport { protectedCard.respond(it) })
        val denied = TransparentFileReader.readTlvFile(ApduChannel(t2, logger()), 0x011C, "EF.CardAccess")
        assertEquals(0x6982, (denied as EfReadResult.SelectFailed).status.sw)
    }

    @Test fun `short file ending early is accepted`() = runTest {
        val card = VirtualIsoCard(files = mapOf(0x2F01 to Hex.decode("4701")))
        val t = connected(SimulatedTransport { card.respond(it) })
        val r = TransparentFileReader.readTlvFile(ApduChannel(t, logger()), 0x2F01, "EF.ATR")
        assertArrayEquals(Hex.decode("4701"), (r as EfReadResult.Success).content)
    }
}
