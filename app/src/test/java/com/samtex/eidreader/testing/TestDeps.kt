package com.samtex.eidreader.testing

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.diagnostics.ErrorAnalyzer
import com.samtex.eidreader.nfc.error.NfcErrorHandler
import com.samtex.eidreader.security.PrivacyManager

/** Deterministic clock advancing by [stepMs] at every read. */
class FakeClock(var now: Long = 1_700_000_000_000L, private val stepMs: Long = 1) : Clock {
    override fun nowMs(): Long = now.also { now += stepMs }
}

object TestDeps {
    val privacy = PrivacyManager()
    val errorHandler = NfcErrorHandler()
    val errorAnalyzer = ErrorAnalyzer(errorHandler, privacy)
}
