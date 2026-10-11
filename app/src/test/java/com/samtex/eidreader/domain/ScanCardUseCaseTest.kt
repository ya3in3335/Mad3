package com.samtex.eidreader.domain

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.domain.model.IsoDepStatus
import com.samtex.eidreader.domain.model.ScanOptions
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.domain.usecase.IdentifyCardUseCase
import com.samtex.eidreader.domain.usecase.ScanCardUseCase
import com.samtex.eidreader.nfc.classifier.CardClassifier
import com.samtex.eidreader.nfc.classifier.CardFamily
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.protocol.EfDirAdapter
import com.samtex.eidreader.nfc.protocol.IcaoCardAccessAdapter
import com.samtex.eidreader.nfc.protocol.IcaoEmrtdApplicationAdapter
import com.samtex.eidreader.nfc.protocol.ProbeStatus
import com.samtex.eidreader.nfc.protocol.ProtocolAdapterRegistry
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.Fixtures
import com.samtex.eidreader.testing.SimulatedTransport
import com.samtex.eidreader.testing.TestDeps
import com.samtex.eidreader.testing.VirtualIsoCard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End-to-end scan pipeline against SIMULATED cards (no physical NFC hardware). */
class ScanCardUseCaseTest {

    private val sessionManager = IsoDepSessionManager()
    private val useCase = ScanCardUseCase(
        sessionManager = sessionManager,
        identify = IdentifyCardUseCase(CardClassifier()),
        adapters = ProtocolAdapterRegistry(EfDirAdapter(), IcaoCardAccessAdapter(), IcaoEmrtdApplicationAdapter()),
        errorAnalyzer = TestDeps.errorAnalyzer,
        errorHandler = TestDeps.errorHandler,
        privacy = TestDeps.privacy,
        clock = FakeClock(),
    )

    @Test fun `simulated Algerian eID scan completes with public data only`() = runTest {
        val card = VirtualIsoCard()
        val transport = SimulatedTransport { card.respond(it) }
        val phases = mutableListOf<ScanPhase>()

        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions()) { phases += it }

        assertEquals(listOf(ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING), phases)
        assertNull(result.error)
        assertEquals(IsoDepStatus.ESTABLISHED, result.isoDep.status)
        assertEquals(CardFamily.NATIONAL_EID, result.classification.family)
        assertEquals("AlgeriaEIDv2", result.classification.signature?.text)

        val cardAccess = result.probes.single { it.adapterId == "icao.cardaccess" }
        assertEquals(ProbeStatus.AVAILABLE, cardAccess.status)
        assertTrue(cardAccess.findings.any { it.value.contains("id-PACE-ECDH-GM-AES-CBC-CMAC-128") })
        assertTrue(cardAccess.accessNotes.any { it.contains("PACE") })

        val emrtd = result.probes.single { it.adapterId == "icao.emrtd" }
        assertEquals(ProbeStatus.AVAILABLE, emrtd.status)
        assertTrue(emrtd.findings.any { it.value == "Access restricted" })

        // Only SELECT and READ BINARY were ever sent; nothing authenticating or writing.
        assertTrue(transport.sent.all { it.startsWith("00A4") || it.startsWith("00B0") })
        assertEquals(transport.sent.size, result.isoDep.apduCount)
        assertEquals(1, transport.closeCount)
        assertFalse(sessionManager.isBusy)
    }

    @Test fun `public probe disabled sends no APDU`() = runTest {
        val transport = SimulatedTransport { Hex.decode("9000") }
        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions(publicProbe = false))
        assertTrue(transport.sent.isEmpty())
        assertEquals(1, transport.connectCount)
        assertEquals(IsoDepStatus.ESTABLISHED, result.isoDep.status)
        assertTrue(result.probes.isEmpty())
    }

    @Test fun `card removal during reading returns a partial result`() = runTest {
        val card = VirtualIsoCard(removeAfterCommands = 2)
        val transport = SimulatedTransport { card.respond(it) }
        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions())
        assertEquals(ErrorKind.TAG_LOST, result.error?.kind)
        assertEquals(IsoDepStatus.CONNECTION_LOST, result.isoDep.status)
        // Passive metadata survives the interruption.
        assertEquals("AlgeriaEIDv2", result.classification.signature?.text)
        assertEquals(1, transport.closeCount)
    }

    @Test fun `card without ICAO applications reports not present`() = runTest {
        val card = VirtualIsoCard(files = emptyMap(), applications = emptySet())
        val transport = SimulatedTransport { card.respond(it) }
        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions())
        assertNull(result.error)
        assertTrue(result.probes.all { it.status == ProbeStatus.NOT_PRESENT })
    }

    @Test fun `malformed card answers only fail the affected probe`() = runTest {
        val transport = SimulatedTransport { ByteArray(0) }
        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions())
        assertNull(result.error)
        assertTrue(result.probes.all { it.status == ProbeStatus.ERROR })
        assertEquals(IsoDepStatus.ESTABLISHED, result.isoDep.status)
    }

    @Test fun `connection failure is classified`() = runTest {
        val transport = SimulatedTransport(connectFailure = NfcEngineException(ErrorKind.COMMUNICATION, "io")) { Hex.decode("9000") }
        val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions())
        assertEquals(ErrorKind.COMMUNICATION, result.error?.kind)
        assertEquals(IsoDepStatus.FAILED, result.isoDep.status)
        assertEquals(1, transport.closeCount)
    }

    @Test fun `non ISO-DEP tag never opens a transport`() = runTest {
        val card = mockk<com.samtex.eidreader.domain.model.DiscoveredCard>()
        every { card.snapshot } returns Fixtures.ultralightSnapshot
        val result = useCase.execute(card, ScanOptions())
        verify(exactly = 0) { card.openIsoDepTransport() }
        assertEquals(IsoDepStatus.NOT_SUPPORTED, result.isoDep.status)
        assertEquals(CardFamily.MIFARE_ULTRALIGHT, result.classification.family)
        assertNull(result.error)
    }

    @Test fun `repeated scans reuse the session manager cleanly`() = runTest {
        repeat(3) {
            val card = VirtualIsoCard()
            val transport = SimulatedTransport { card.respond(it) }
            val result = useCase.execute(Fixtures.card(Fixtures.algeriaSnapshot, transport), ScanOptions())
            assertNull(result.error)
            assertEquals(1, transport.closeCount)
        }
        assertFalse(sessionManager.isBusy)
    }
}
