package com.samtex.eidreader.icao.pa

import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.TlvParseException
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.OperatorCreationException
import org.jmrtd.lds.SODFile
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

enum class CheckStatus { VALID, INVALID, UNAVAILABLE }

enum class HashCheck { MATCH, MISMATCH, NOT_IN_SOD }

enum class Authenticity {
    /** Hashes match, SOD signature valid and DS certificate chains to a trusted CSCA. */
    ESTABLISHED,
    /** Some check could not be performed (e.g. no trust anchor); nothing contradicted the data. */
    NOT_ESTABLISHED,
    /** A check failed: data or signature does not match. */
    FAILED,
}

data class PassiveAuthReport(
    val sodPresent: Boolean,
    val digestAlgorithm: String?,
    val hashes: Map<Int, HashCheck>,
    val signature: CheckStatus,
    val chain: CheckStatus,
    val dsSubject: String?,
    val dsIssuer: String?,
    val dsNotAfter: Date?,
    val cscaSubject: String?,
    val notes: List<String>,
) {
    val hashesMatched: Boolean get() = hashes.isNotEmpty() && hashes.values.all { it == HashCheck.MATCH }

    val authenticity: Authenticity
        get() = when {
            hashes.values.any { it != HashCheck.MATCH } || signature == CheckStatus.INVALID || chain == CheckStatus.INVALID -> Authenticity.FAILED
            hashesMatched && signature == CheckStatus.VALID && chain == CheckStatus.VALID -> Authenticity.ESTABLISHED
            else -> Authenticity.NOT_ESTABLISHED
        }

    companion object {
        fun unavailable(note: String) = PassiveAuthReport(
            false, null, emptyMap(), CheckStatus.UNAVAILABLE, CheckStatus.UNAVAILABLE, null, null, null, null, listOf(note),
        )
    }
}

/** Source of trusted Country Signing CA certificates. */
fun interface TrustAnchorProvider {
    fun anchors(): List<X509Certificate>
}

/**
 * ICAO Doc 9303 Part 11 passive authentication:
 * 1. the hash of every data group read must equal the value in the signed EF.SOD,
 * 2. the EF.SOD CMS signature must verify with the Document Signer (DS) certificate,
 * 3. the DS certificate must be signed by a trusted CSCA certificate supplied by the user.
 *
 * Without a trusted CSCA the result can never be [Authenticity.ESTABLISHED]: a signature that
 * only verifies against a certificate embedded in the chip proves nothing about its origin.
 */
@Singleton
class PassiveAuthenticator @Inject constructor(private val trustAnchors: TrustAnchorProvider) {

    private val bc = BouncyCastleProvider()

    fun verify(sodBytes: ByteArray?, dataGroups: Map<Int, ByteArray>, now: Date = Date()): PassiveAuthReport {
        if (sodBytes == null) return PassiveAuthReport.unavailable("EF.SOD was not available, so no data could be verified.")
        val notes = mutableListOf<String>()
        val sod = try {
            SODFile(ByteArrayInputStream(sodBytes))
        } catch (e: IOException) {
            return PassiveAuthReport.unavailable("EF.SOD could not be parsed.")
        } catch (e: RuntimeException) {
            return PassiveAuthReport.unavailable("EF.SOD could not be parsed.")
        }

        // 1. Data group hashes.
        val digestAlg = sod.digestAlgorithm
        val expected = sod.dataGroupHashes
        val hashes = dataGroups.mapValues { (dg, content) ->
            val reference = expected[dg] ?: return@mapValues HashCheck.NOT_IN_SOD
            val actual = try {
                MessageDigest.getInstance(digestAlg, bc).digest(content)
            } catch (e: GeneralSecurityException) {
                notes += "Unsupported digest algorithm $digestAlg"
                return@mapValues HashCheck.MISMATCH
            }
            if (MessageDigest.isEqual(actual, reference)) HashCheck.MATCH else HashCheck.MISMATCH
        }

        // 2. CMS signature with the embedded DS certificate.
        val ds: X509Certificate? = try {
            sod.docSigningCertificate
        } catch (e: RuntimeException) {
            null
        }
        val signature = if (ds == null) {
            notes += "EF.SOD contains no Document Signer certificate."
            CheckStatus.UNAVAILABLE
        } else {
            verifySignature(sodBytes, ds, notes)
        }

        // 3. Chain to a trusted CSCA.
        var cscaSubject: String? = null
        val chain = when {
            ds == null -> CheckStatus.UNAVAILABLE
            else -> {
                val candidates = trustAnchors.anchors().filter { it.subjectX500Principal == ds.issuerX500Principal }
                if (candidates.isEmpty()) {
                    notes += "No trusted CSCA certificate for issuer \"${ds.issuerX500Principal.name}\" is installed."
                    CheckStatus.UNAVAILABLE
                } else {
                    val match = candidates.firstOrNull { csca ->
                        try {
                            ds.verify(csca.publicKey, bc)
                            true
                        } catch (_: GeneralSecurityException) {
                            false
                        }
                    }
                    if (match == null) {
                        CheckStatus.INVALID
                    } else {
                        cscaSubject = match.subjectX500Principal.name
                        try {
                            match.checkValidity(now)
                        } catch (_: GeneralSecurityException) {
                            notes += "The trusted CSCA certificate is outside its validity period."
                        }
                        CheckStatus.VALID
                    }
                }
            }
        }
        if (ds != null && ds.notAfter.before(now)) {
            notes += "The Document Signer certificate has expired (normal for older cards; the document may still be valid)."
        }

        return PassiveAuthReport(
            sodPresent = true,
            digestAlgorithm = digestAlg,
            hashes = hashes,
            signature = signature,
            chain = chain,
            dsSubject = ds?.subjectX500Principal?.name,
            dsIssuer = ds?.issuerX500Principal?.name,
            dsNotAfter = ds?.notAfter,
            cscaSubject = cscaSubject,
            notes = notes,
        )
    }

    private fun verifySignature(sodBytes: ByteArray, ds: X509Certificate, notes: MutableList<String>): CheckStatus {
        return try {
            // EF.SOD = 77 { ContentInfo(SignedData) }
            val contentInfo = BerTlv.parse(sodBytes).firstOrNull { it.tag == 0x77 }?.value
                ?: return CheckStatus.UNAVAILABLE.also { notes += "EF.SOD wrapper (tag 77) missing." }
            val cms = CMSSignedData(contentInfo)
            val signers = cms.signerInfos.signers
            if (signers.isEmpty()) return CheckStatus.UNAVAILABLE.also { notes += "EF.SOD has no signer." }
            val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(ds)
            if (signers.all { it.verify(verifier) }) CheckStatus.VALID else CheckStatus.INVALID
        } catch (e: TlvParseException) {
            notes += "EF.SOD is not valid BER-TLV."
            CheckStatus.UNAVAILABLE
        } catch (e: CMSException) {
            notes += "EF.SOD signature could not be verified (${e.javaClass.simpleName})."
            CheckStatus.INVALID
        } catch (e: OperatorCreationException) {
            notes += "Signature algorithm not supported."
            CheckStatus.UNAVAILABLE
        } catch (e: RuntimeException) {
            notes += "EF.SOD signature check failed (${e.javaClass.simpleName})."
            CheckStatus.INVALID
        }
    }
}
