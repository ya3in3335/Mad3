package com.samtex.eidreader.diagnostics

import com.samtex.eidreader.core.util.Clock
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.transport.ApduExchange
import com.samtex.eidreader.security.PrivacyManager

enum class LogLevel { INFO, APDU, WARNING, ERROR }

data class LogEntry(val timestampMs: Long, val level: LogLevel, val message: String)

/**
 * In-memory, per-session structured log. Entries are sanitized when written: response data
 * from non-public objects is reduced to its length, and free text is scrubbed of identifiers.
 * The log is never written to Logcat.
 */
class SessionLogger(
    private val clock: Clock,
    private val privacy: PrivacyManager,
    private val maxEntries: Int = 500,
) {
    private val entries = ArrayList<LogEntry>()

    @Synchronized
    private fun add(level: LogLevel, message: String) {
        if (entries.size >= maxEntries) entries.removeAt(0)
        entries += LogEntry(clock.nowMs(), level, message)
    }

    fun lifecycle(message: String) = add(LogLevel.INFO, message)

    fun warning(message: String) = add(LogLevel.WARNING, privacy.sanitizeMessage(message))

    fun error(message: String) = add(LogLevel.ERROR, privacy.sanitizeMessage(message))

    fun apdu(exchange: ApduExchange) {
        val cmd = exchange.command
        val header = Hex.encode(byteArrayOf(cmd.cla.toByte(), cmd.ins.toByte(), cmd.p1.toByte(), cmd.p2.toByte()), " ")
        val cmdData = if (cmd.nc > 0) " Lc=${cmd.nc} [${Hex.encode(cmd.data, " ")}]" else ""
        val le = cmd.ne?.let { " Le=$it" } ?: ""
        add(LogLevel.APDU, "→ ${exchange.label}: $header$cmdData$le")
        val data = privacy.sanitizeData(exchange.response.data, exchange.publicData)
        val dataPart = if (data.isEmpty()) "" else "$data "
        add(
            LogLevel.APDU,
            "← $dataPart${exchange.status.hex} (${exchange.status.description}) in ${exchange.durationMs} ms" +
                if (exchange.transmissions > 1) ", ${exchange.transmissions} transmissions" else "",
        )
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()
}
