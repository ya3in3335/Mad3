package com.samtex.eidreader.nfc.protocol

import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.TlvParseException

data class SecurityInfo(
    val oid: String,
    val protocolName: String,
    val family: String,
    val version: Long?,
    val parameterId: Long?,
    val parameterName: String?,
)

/**
 * Decodes the SecurityInfos structure of EF.CardAccess (ICAO Doc 9303 Part 11, BSI TR-03110).
 * Only object identifiers and public parameters are decoded; no key material is involved.
 */
object SecurityInfoDecoder {

    private const val BSI = "0.4.0.127.0.7.2.2"

    private val cipherSuffix = mapOf(
        "1" to "3DES-CBC-CBC",
        "2" to "AES-CBC-CMAC-128",
        "3" to "AES-CBC-CMAC-192",
        "4" to "AES-CBC-CMAC-256",
    )

    private val paceMapping = mapOf(
        "1" to "DH-GM", "2" to "ECDH-GM", "3" to "DH-IM", "4" to "ECDH-IM", "6" to "ECDH-CAM",
    )

    private val caMapping = mapOf("1" to "DH", "2" to "ECDH")

    /** BSI TR-03110 Part 3 / ICAO 9303-11 standardized domain parameters. */
    private val standardizedParameters = mapOf(
        0L to "1024-bit MODP group with 160-bit prime order subgroup",
        1L to "2048-bit MODP group with 224-bit prime order subgroup",
        2L to "2048-bit MODP group with 256-bit prime order subgroup",
        8L to "NIST P-192 (secp192r1)",
        9L to "brainpoolP192r1",
        10L to "NIST P-224 (secp224r1)",
        11L to "brainpoolP224r1",
        12L to "NIST P-256 (secp256r1)",
        13L to "brainpoolP256r1",
        14L to "brainpoolP320r1",
        15L to "NIST P-384 (secp384r1)",
        16L to "brainpoolP384r1",
        17L to "brainpoolP512r1",
        18L to "NIST P-521 (secp521r1)",
    )

    fun describeOid(oid: String): Pair<String, String> {
        if (oid.startsWith("$BSI.")) {
            val rest = oid.removePrefix("$BSI.").split('.')
            when (rest.firstOrNull()) {
                "4" -> {
                    val mapping = rest.getOrNull(1)?.let { paceMapping[it] }
                    val cipher = rest.getOrNull(2)?.let { cipherSuffix[it] }
                    if (mapping != null) {
                        return "PACE" to listOfNotNull("id-PACE-$mapping", cipher).joinToString("-")
                    }
                    return "PACE" to "id-PACE (domain parameters)"
                }
                "3" -> {
                    val alg = rest.getOrNull(1)?.let { caMapping[it] }
                    val cipher = rest.getOrNull(2)?.let { cipherSuffix[it] }
                    return "Chip Authentication" to listOfNotNull("id-CA" + (alg?.let { "-$it" } ?: ""), cipher).joinToString("-")
                }
                "1" -> return "Chip Authentication public key" to "id-PK" + (rest.getOrNull(1)?.let { caMapping[it]?.let { a -> "-$a" } } ?: "")
                "2" -> return "Terminal Authentication" to "id-TA"
                "5" -> return "Restricted Identification" to "id-RI"
                "6" -> return "Card info locator" to "id-CI"
                "8" -> return "Privileged terminal info" to "id-PT"
                "11" -> return "PACE/CAM or PSA info" to "id-PS"
            }
            return "BSI TR-03110" to oid
        }
        if (oid.startsWith("2.23.136.1.1.")) {
            return when (oid) {
                "2.23.136.1.1.5" -> "Active Authentication" to "id-icao-mrtd-security-aaProtocolObject"
                "2.23.136.1.1.10" -> "Terminal authentication (ICAO)" to oid
                else -> "ICAO 9303 security" to oid
            }
        }
        return "Unknown" to oid
    }

    /** @throws TlvParseException if EF.CardAccess is not a well-formed SET OF SecurityInfo. */
    fun decode(cardAccess: ByteArray): List<SecurityInfo> {
        val roots = BerTlv.parse(cardAccess)
        val set = roots.firstOrNull { it.tag == 0x31 } ?: throw TlvParseException("EF.CardAccess is not a SET (tag 31)")
        return set.children.filter { it.tag == 0x30 }.mapNotNull { seq ->
            val oidTlv = seq.children.firstOrNull { it.tag == 0x06 } ?: return@mapNotNull null
            val oid = BerTlv.decodeOid(oidTlv.value)
            val ints = seq.children.filter { it.tag == 0x02 }
            val version = ints.getOrNull(0)?.let { safeInt(it.value) }
            val parameter = ints.getOrNull(1)?.let { safeInt(it.value) }
            val (family, name) = describeOid(oid)
            val isPace = family == "PACE"
            SecurityInfo(
                oid = oid,
                protocolName = name,
                family = family,
                version = version,
                parameterId = parameter,
                parameterName = if (isPace) parameter?.let { standardizedParameters[it] ?: "Proprietary parameter $it" } else null,
            )
        }
    }

    private fun safeInt(value: ByteArray): Long? = try {
        BerTlv.decodeInteger(value)
    } catch (_: TlvParseException) {
        null
    }
}
