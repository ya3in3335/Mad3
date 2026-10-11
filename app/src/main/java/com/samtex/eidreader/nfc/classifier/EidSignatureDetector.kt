package com.samtex.eidreader.nfc.classifier

import com.samtex.eidreader.core.util.ByteString

/** A textual card signature found in the historical bytes. */
data class CardSignature(
    /** Exact ASCII text read from the card (verified). */
    val text: String,
    /** Hex of the matched bytes. */
    val hex: String,
    /** Interpretation of the signature (inferred). */
    val interpretation: String,
    val country: String?,
    val generation: Int?,
    val isKnown: Boolean,
)

/**
 * Detects national eID signatures carried as ASCII in ISO-DEP historical bytes.
 *
 * Known example (observed on an Algerian eID, used as a test fixture — not guaranteed for
 * every card): historical bytes `416C67657269614549447632` = "AlgeriaEIDv2".
 */
object EidSignatureDetector {

    private val PATTERN = Regex("([A-Z][A-Za-z]{2,30})[-_ ]?(?:EID|eID|Eid)[-_ ]?v?(\\d{1,2})?")

    private val knownCountries = mapOf(
        "Algeria" to "Algerian electronic identity card",
    )

    fun detect(historicalBytes: ByteString?): CardSignature? {
        if (historicalBytes == null || historicalBytes.isEmpty()) return null
        // Extract the longest printable run so that signatures embedded after a category byte are still found.
        val bytes = historicalBytes.toByteArray()
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            if (v in 0x20..0x7E) {
                if (curStart < 0) curStart = i
                val len = i - curStart + 1
                if (len > bestLen) {
                    bestLen = len
                    bestStart = curStart
                }
            } else {
                curStart = -1
            }
        }
        if (bestLen < 4) return null
        val text = String(bytes, bestStart, bestLen, Charsets.US_ASCII)
        val match = PATTERN.find(text) ?: return null
        val country = match.groupValues[1]
        val generation = match.groupValues[2].toIntOrNull()
        val known = knownCountries[country]
        val interpretation = buildString {
            append(known ?: "National electronic identity card ($country)")
            if (generation != null) append(", signature version $generation")
        }
        val matchedBytes = text.substring(match.range).toByteArray(Charsets.US_ASCII)
        return CardSignature(
            text = text.substring(match.range),
            hex = ByteString.of(matchedBytes).hex(),
            interpretation = interpretation,
            country = country,
            generation = generation,
            isKnown = known != null,
        )
    }
}
