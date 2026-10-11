package com.samtex.eidreader.testing

import com.samtex.eidreader.core.crypto.CryptoProviders
import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.icao.mrz.MrzCheckDigit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import net.sf.scuba.data.Gender
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

/**
 * SYNTHETIC test document. Every value is invented ("SPECIMEN"/"TESTEUR"), no real citizen data.
 * The MRZ follows the TD1 layout with valid check digits; the PKI is a throw-away test CSCA/DS.
 */
object SyntheticDocument {
    init {
        CryptoProviders.install()
    }

    // Synthetic access data matching the MRZ below.
    const val DOC_NUMBER = "123456789"
    const val DOB = "900101"
    const val DOE = "320101"

    /** TD1 MRZ (3 × 30) with check digits computed for the synthetic values. */
    val MRZ: String by lazy {
        fun cd(s: String) = MrzCheckDigit.compute(s)
        val l1 = "IDDZA" + DOC_NUMBER + cd(DOC_NUMBER) + "<".repeat(15)
        val l2Body = DOB + cd(DOB) + "M" + DOE + cd(DOE) + "DZA" + "<".repeat(11)
        val composite = l1.substring(5, 30) + l2Body.substring(0, 7) + l2Body.substring(8, 15) + l2Body.substring(18, 29)
        val l2 = l2Body + cd(composite)
        val l3 = "TESTEUR<<SPECIMEN<ALPHA".padEnd(30, '<')
        l1 + l2 + l3
    }

    val mrzInformation: String get() {
        fun cd(s: String) = MrzCheckDigit.compute(s)
        return DOC_NUMBER + cd(DOC_NUMBER) + DOB + cd(DOB) + DOE + cd(DOE)
    }

    val dg1: ByteArray get() = SimulatedEmrtdChip.tlv(0x61, SimulatedEmrtdChip.tlv(0x5F1F, MRZ.toByteArray(Charsets.US_ASCII)))

    /** DG11 with an Arabic (UTF-8) synthetic name, place of birth and address. */
    val dg11: ByteArray get() {
        val t = SimulatedEmrtdChip::tlv
        val body = t(0x5C, byteArrayOf(0x5F, 0x0E, 0x5F, 0x11, 0x5F, 0x42, 0x5F, 0x2B)) +
            t(0x5F0E, "تجريبي<<عينة".toByteArray(Charsets.UTF_8)) +
            t(0x5F2B, "19900101".toByteArray(Charsets.US_ASCII)) +
            t(0x5F11, "مدينة<تجريبية".toByteArray(Charsets.UTF_8)) +
            t(0x5F42, "SPECIMEN<STREET<1".toByteArray(Charsets.UTF_8)) +
            t(0x5F7E, byteArrayOf(1, 2, 3)) // unknown/proprietary element: must not be decoded
        return t(0x6B, body)
    }

    val dg12: ByteArray get() {
        val t = SimulatedEmrtdChip::tlv
        val body = t(0x5C, byteArrayOf(0x5F, 0x19, 0x5F, 0x26)) +
            t(0x5F19, "SPECIMEN AUTHORITY".toByteArray(Charsets.UTF_8)) +
            t(0x5F26, byteArrayOf(0x20, 0x22, 0x01, 0x01)) // BCD date
        return t(0x6C, body)
    }

    val com: ByteArray get() {
        val t = SimulatedEmrtdChip::tlv
        return t(0x60, t(0x5F01, "0107".toByteArray()) + t(0x5F36, "040000".toByteArray()) + t(0x5C, byteArrayOf(0x61, 0x75, 0x6B, 0x6C)))
    }

    /** Fake "JPEG" payload (markers only, not a real picture). */
    val portraitBytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())

    val dg2: ByteArray by lazy {
        val image = FaceImageInfo(
            Gender.UNSPECIFIED, FaceImageInfo.EyeColor.UNSPECIFIED, 0, 0, 0,
            intArrayOf(0, 0, 0), intArrayOf(0, 0, 0),
            FaceImageInfo.FACE_IMAGE_TYPE_BASIC, 0, 0, 0, 0,
            arrayOf(), 4, 5, ByteArrayInputStream(portraitBytes), portraitBytes.size, FaceImageInfo.IMAGE_DATA_TYPE_JPEG,
        )
        DG2File(listOf(FaceInfo(listOf(image)))).encoded
    }

    /** id-PACE-ECDH-GM-AES-CBC-CMAC-128 with brainpoolP256r1 (standardized parameter 13). */
    const val PACE_OID = "0.4.0.127.0.7.2.2.4.2.2"
    val cardAccess: ByteArray = Hex.decode("31143012060A04007F0007020204020202010202010D")

    class Pki(val csca: X509Certificate, val ds: X509Certificate, val dsKey: PrivateKey, val otherCsca: X509Certificate)

    val pki: Pki by lazy {
        val cscaKeys = rsa()
        val otherKeys = rsa()
        val dsKeys = rsa()
        val csca = cert("C=DZ,O=SAMTEX TEST,CN=TEST CSCA", cscaKeys.public, "C=DZ,O=SAMTEX TEST,CN=TEST CSCA", cscaKeys.private, ca = true)
        val other = cert("C=DZ,O=SAMTEX TEST,CN=TEST CSCA", otherKeys.public, "C=DZ,O=SAMTEX TEST,CN=TEST CSCA", otherKeys.private, ca = true)
        val ds = cert("C=DZ,O=SAMTEX TEST,CN=TEST DS", dsKeys.public, "C=DZ,O=SAMTEX TEST,CN=TEST CSCA", cscaKeys.private, ca = false)
        Pki(csca, ds, dsKeys.private, other)
    }

    fun sod(groups: Map<Int, ByteArray>): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val hashes = groups.mapValues { sha.digest(it.value) }
        return SODFile("SHA-256", "SHA256withRSA", hashes, pki.dsKey, pki.ds).encoded
    }

    /** Builds a complete simulated chip. */
    fun chip(
        bac: Boolean = true,
        pace: Boolean = false,
        can: String? = null,
        includeDg2: Boolean = true,
        tamperDg1: Boolean = false,
        hasApplication: Boolean = true,
        removeAfterCommands: Int? = null,
    ): SimulatedEmrtdChip {
        val groups = linkedMapOf(1 to dg1, 11 to dg11, 12 to dg12)
        if (includeDg2) groups[2] = dg2
        val sod = sod(groups)
        val stored = groups.toMutableMap()
        if (tamperDg1) stored[1] = dg1.copyOf().also { it[it.size - 1] = 'X'.code.toByte() }
        val app = mutableMapOf(0x011E to com, 0x011D to sod, 0x0101 to stored.getValue(1), 0x010B to stored.getValue(11), 0x010C to stored.getValue(12))
        if (includeDg2) app[0x0102] = stored.getValue(2)
        val mf = if (pace) mapOf(0x011C to cardAccess) else emptyMap()
        return SimulatedEmrtdChip(
            mfFiles = mf,
            appFiles = app,
            mrzInformation = mrzInformation,
            can = can,
            supportsBac = bac,
            paceOid = if (pace) PACE_OID else null,
            hasApplication = hasApplication,
            removeAfterCommands = removeAfterCommands,
        )
    }

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun cert(subject: String, key: PublicKey, issuer: String, signer: PrivateKey, ca: Boolean): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer), BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 10L * 365 * 86_400_000L), X500Name(subject), key,
        )
        if (ca) {
            builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        }
        val signerOp = JcaContentSignerBuilder("SHA256withRSA").setProvider(BouncyCastleProvider()).build(signer)
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(builder.build(signerOp))
    }
}
