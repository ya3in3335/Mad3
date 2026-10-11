package com.samtex.eidreader.nfc.classifier

/** How a piece of information was obtained. Shown next to every value in the UI and reports. */
enum class Provenance {
    /** Read directly from the card or reported by the Android NFC stack. */
    VERIFIED,
    /** Derived by interpretation (registries, heuristics, naming conventions). */
    INFERRED,
    /** Not obtainable without authorization or not exposed by Android. */
    RESTRICTED,
}

data class Finding(
    val key: String,
    val label: String,
    val value: String,
    val provenance: Provenance,
    val explanation: String? = null,
    val hex: String? = null,
)
