package com.samtex.eidreader.domain

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.diagnostics.SessionLogger
import com.samtex.eidreader.domain.usecase.DiagnosticOperation
import com.samtex.eidreader.domain.usecase.ParseResponseUseCase
import com.samtex.eidreader.domain.usecase.RunDiagnosticUseCase
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.Fixtures
import com.samtex.eidreader.testing.SimulatedTransport
import com.samtex.eidreader.testing.TestDeps
import com.samtex.eidreader.testing.VirtualIsoCard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticAndParseTest {

    private val clock = FakeClock()
    private val useCase = RunDiagnosticUseCase(IsoDepSessionManager(), clock)
    private fun logger() = SessionLogger(clock, TestDeps.privacy)

    @Test fun `documented operations run against the simulated card`() = runTest {
        val card = VirtualIsoCard()
        val transport = SimulatedTransport { card.respond(it) }
        val discovered = Fixtures.card(Fixtures.algeriaSnapshot, transport)

        val read = useCase.run(discovered, DiagnosticOperation.READ_EF_CARD_ACCESS, logger())
        assertEquals(3, read.size)
        assertTrue(read.all { it.status.isSuccess })

        val select = useCase.run(discovered, DiagnosticOperation.SELECT_EMRTD, logger()).single()
        assertEquals(0x9000, select.status.sw)

        val dir = useCase.run(discovered, DiagnosticOperation.READ_EF_DIR, logger()).single()
        assertEquals(0x6A82, dir.status.sw)
        assertEquals(3, transport.closeCount)
    }

    @Test fun `custom commands are filtered by the policy before transmission`() = runTest {
        val transport = SimulatedTransport { Hex.decode("9000") }
        val discovered = Fixtures.card(Fixtures.algeriaSnapshot, transport)
        try {
            useCase.runCustom(discovered, CommandApdu(0x00, 0x20, 0x00, 0x80, Hex.decode("31323334")), logger())
            fail("VERIFY must be refused")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.POLICY_DENIED, e.kind)
        }
        assertTrue(transport.sent.isEmpty())
        assertEquals(0, transport.connectCount)

        val ok = useCase.runCustom(discovered, CommandApdu(0x00, 0xCA, 0x00, 0x66, ne = 256), logger())
        assertEquals(0x9000, ok.status.sw)
        assertEquals(1, transport.sent.size)
    }

    @Test fun `diagnostics on a non ISO-DEP tag fail explicitly`() = runTest {
        try {
            useCase.run(Fixtures.card(Fixtures.ultralightSnapshot, null), DiagnosticOperation.SELECT_MF, logger())
            fail("expected failure")
        } catch (e: NfcEngineException) {
            assertEquals(ErrorKind.UNSUPPORTED_TAG, e.kind)
        }
    }

    @Test fun `offline response parsing`() {
        val parse = ParseResponseUseCase()
        val ok = parse("6A 82") as ParseResponseUseCase.Outcome.Parsed
        assertEquals(0x6A82, ok.description.status.sw)
        assertEquals(0, ok.description.dataLength)

        val withData = parse(Fixtures.ALGERIA_HISTORICAL_HEX + " 90 00") as ParseResponseUseCase.Outcome.Parsed
        assertEquals("AlgeriaEIDv2", withData.description.ascii)

        assertTrue(parse("") is ParseResponseUseCase.Outcome.Invalid)
        assertTrue(parse("90") is ParseResponseUseCase.Outcome.Invalid)
        assertTrue(parse("ZZ 90 00") is ParseResponseUseCase.Outcome.Invalid)
    }
}
