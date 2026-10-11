package com.samtex.eidreader.icao.pa

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Parses DER or PEM X.509 certificates (one or several) for the trust-anchor store. */
object CertificateParser {
    private val factory: CertificateFactory by lazy { CertificateFactory.getInstance("X.509", BouncyCastleProvider()) }

    /** @throws CertificateException when the data is not a certificate. */
    fun parse(bytes: ByteArray): List<X509Certificate> {
        val certs = factory.generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        if (certs.isEmpty()) throw CertificateException("No X.509 certificate found")
        return certs
    }

    /** CSCA certificates are CA certificates (basicConstraints present). */
    fun isCaCertificate(cert: X509Certificate): Boolean = cert.basicConstraints >= 0
}
