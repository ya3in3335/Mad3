package com.samtex.eidreader.diagnostics

import com.samtex.eidreader.core.util.ByteString
import com.samtex.eidreader.domain.model.ScanOptions
import com.samtex.eidreader.domain.usecase.ExportReportUseCase
import com.samtex.eidreader.domain.usecase.IdentifyCardUseCase
import com.samtex.eidreader.domain.usecase.ScanCardUseCase
import com.samtex.eidreader.nfc.classifier.CardClassifier
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import com.samtex.eidreader.nfc.protocol.EfDirAdapter
import com.samtex.eidreader.nfc.protocol.IcaoCardAccessAdapter
import com.samtex.eidreader.nfc.protocol.IcaoEmrtdApplicationAdapter
import com.samtex.eidreader.nfc.protocol.ProtocolAdapterRegistry
import com.samtex.eidreader.nfc.session.IsoDepSessionManager
import com.samtex.eidreader.security.InputValidator
import com.samtex.eidreader.testing.FakeClock
import com.samtex.eidreader.testing.Fixtures
import com.samtex.eidreader.testing.SimulatedTransport
import com.samtex.eidreader.testing.TestDeps
import com.samtex.eidreader.testing.VirtualIsoCard
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json as KJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class ReportAndPrivacyTest {

    private val clock = FakeClock()
    private val generator = ReportGenerator(TestDeps.privacy, clock)

    private suspend fun scan() = ScanCardUseCase(
        IsoDepSessionManager(), IdentifyCardUseCase(CardClassifier()),
        ProtocolAdapterRegistry(EfDirAdapter(), IcaoCardAccessAdapter(), IcaoEmrtdApplicationAdapter()),
        TestDeps.errorAnalyzer, TestDeps.errorHandler, TestDeps.privacy, clock,
    ).execute(Fixtures.card(Fixtures.algeriaSnapshot, VirtualIsoCard().let { c -> SimulatedTransport { c.respond(it) } }), ScanOptions())

    @Test fun `JSON report is valid and redacts identifiers by default`() = runTest {
        val json = generator.generate(scan(), ReportOptions(ReportFormat.JSON))
        val root = KJson.parseToJsonElement(json).jsonObject
        assertEquals("SAMTEX", root["report"]!!.jsonObject["generator"]!!.jsonPrimitive.content)
        assertEquals("true", root["report"]!!.jsonObject["identifiersRedacted"]!!.jsonPrimitive.content)
        val card = root["card"]!!.jsonObject
        assertEquals("08 ** ** **", card["uid"]!!.jsonPrimitive.content)
        assertEquals("0x0008", root["nfcA"]!!.jsonObject["atqa"]!!.jsonPrimitive.content)
        assertEquals("0x20", root["nfcA"]!!.jsonObject["sak"]!!.jsonPrimitive.content)
        assertEquals("416C67657269614549447632", root["isoDep"]!!.jsonObject["historicalBytes"]!!.jsonPrimitive.content)
        assertEquals("AlgeriaEIDv2", root["signature"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("false", root["isoDep"]!!.jsonObject["fullAtsExposed"]!!.jsonPrimitive.content)
        assertEquals(3, root["protocols"]!!.jsonArray.size)
        assertFalse(json.contains("08A1B2C3"))
        assertFalse(json.contains("A1 B2 C3"))
    }

    @Test fun `identifiers are only included on explicit request`() = runTest {
        val result = scan()
        val json = generator.generate(result, ReportOptions(ReportFormat.JSON, includeIdentifiers = true))
        val root = KJson.parseToJsonElement(json) as JsonObject
        assertEquals("08 A1 B2 C3", root["card"]!!.jsonObject["uid"]!!.jsonPrimitive.content)
        val txt = generator.generate(result, ReportOptions(ReportFormat.TXT))
        assertTrue(txt.contains("Tag ID:         08 ** ** **"))
        assertFalse(txt.contains("A1B2C3"))
    }

    @Test fun `text report contains the key technical sections`() = runTest {
        val txt = generator.generate(scan(), ReportOptions(ReportFormat.TXT))
        listOf("[Card]", "[ISO-DEP]", "[Signature]", "[Public protocol probes]", "[Findings]", "[Session log]", "AlgeriaEIDv2 (416C67657269614549447632) [VERIFIED]")
            .forEach { assertTrue(it, txt.contains(it)) }
    }

    @Test fun `export writes UTF-8 and suggests a file name`() = runTest {
        val export = ExportReportUseCase(generator)
        val result = scan()
        val out = ByteArrayOutputStream()
        export.export(result, ReportOptions(ReportFormat.TXT), out)
        assertTrue(out.toString(Charsets.UTF_8.name()).startsWith("SAMTEX"))
        assertTrue(export.suggestedFileName(result, ReportOptions(ReportFormat.JSON)).matches(Regex("SAMTEX-report-\\d{8}-\\d{6}\\.json")))
    }

    @Test fun `json escaping`() {
        val s = Json.encode(mapOf("a" to "q\"b\\n\n\u0001", "b" to listOf(1, true, null), "c" to emptyMap<String, Any>(), "d" to Double.NaN))
        val parsed = KJson.parseToJsonElement(s).jsonObject
        assertEquals("q\"b\\n\n\u0001", parsed["a"]!!.jsonPrimitive.content)
        assertEquals(3, parsed["b"]!!.jsonArray.size)
    }

    @Test fun `privacy manager redaction`() {
        val p = TestDeps.privacy
        assertEquals("08 ** ** **", p.redactIdentifier(ByteString.fromHex("08A1B2C3")))
        assertEquals("04 ** ** ** ** ** **", p.redactIdentifier(ByteString.fromHex("04112233445566")))
        assertEquals("08 A1 B2 C3", p.identifier(ByteString.fromHex("08A1B2C3"), reveal = true))
        assertEquals("<3 bytes redacted>", p.sanitizeData(byteArrayOf(1, 2, 3), publicData = false))
        assertEquals("01 02 03", p.sanitizeData(byteArrayOf(1, 2, 3), publicData = true))
        assertEquals("id <redacted> end", p.sanitizeMessage("id 0102030405060708090A end"))
        assertEquals("n <redacted>", p.sanitizeMessage("n 123456789012"))
        assertEquals("", p.sanitizeMessage(null))
    }

    @Test fun `input validation of console APDUs`() {
        assertTrue(InputValidator.parseCommandApdu("00 A4 04 0C 07 A0 00 00 02 47 10 01") is InputValidator.Result.Valid)
        assertTrue(InputValidator.parseCommandApdu("00A4") is InputValidator.Result.Invalid)
        assertTrue(InputValidator.parseCommandApdu("00 A4 04 0C 09 A0") is InputValidator.Result.Invalid)
        assertTrue(InputValidator.parseCommandApdu("00 B0 00 00 00 03 E8") is InputValidator.Result.Invalid) // extended
        assertTrue(InputValidator.parseCommandApdu("GG") is InputValidator.Result.Invalid)
        assertTrue(InputValidator.parseCommandApdu("00".repeat(InputValidator.MAX_INPUT_CHARS)) is InputValidator.Result.Invalid)
        assertTrue(InputValidator.parseHex("   ") is InputValidator.Result.Invalid)
    }

    @Test fun `error classification and analysis`() = runTest {
        val h = TestDeps.errorHandler
        assertEquals(ErrorKind.TAG_LOST, h.classify(NfcEngineException(ErrorKind.TAG_LOST, "x")))
        assertEquals(ErrorKind.COMMUNICATION, h.classify(IOException("x")))
        assertEquals(ErrorKind.INVALID_INPUT, h.classify(IllegalArgumentException("x")))
        assertEquals(ErrorKind.UNEXPECTED, h.classify(RuntimeException("x")))
        val timeout = try {
            withTimeout(10) { delay(1_000) }
            null
        } catch (e: TimeoutCancellationException) {
            e
        }
        assertEquals(ErrorKind.TIMEOUT, h.classify(timeout!!))
        assertTrue(h.isConnectionLoss(NfcEngineException(ErrorKind.TIMEOUT, "t")))

        ErrorKind.entries.forEach { kind ->
            val d = TestDeps.errorAnalyzer.forKind(kind)
            assertTrue(d.title.isNotBlank() && d.explanation.isNotBlank() && d.suggestion.isNotBlank())
        }
        val analyzed = TestDeps.errorAnalyzer.analyze(NfcEngineException(ErrorKind.TAG_LOST, "uid 08A1B2C3D4E5F6A7B8 lost"))
        assertFalse(analyzed.technicalDetail.contains("08A1B2C3"))
    }

    @Test fun `session logger is bounded`() {
        val logger = SessionLogger(clock, TestDeps.privacy, maxEntries = 3)
        repeat(5) { logger.lifecycle("e$it") }
        assertEquals(listOf("e2", "e3", "e4"), logger.snapshot().map { it.message })
    }
}
