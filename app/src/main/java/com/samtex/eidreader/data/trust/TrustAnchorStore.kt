package com.samtex.eidreader.data.trust

import android.content.Context
import com.samtex.eidreader.icao.pa.CertificateParser
import com.samtex.eidreader.icao.pa.TrustAnchorProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

data class TrustAnchorInfo(val fingerprint: String, val subject: String, val notAfter: Date)

sealed interface ImportResult {
    data class Imported(val added: Int, val rejectedNonCa: Int) : ImportResult
    data object NotACertificate : ImportResult
}

/**
 * Country Signing CA certificates that the user imported from a source they trust (e.g. the
 * ICAO PKD or an official national publication). SAMTEX ships **no** built-in trust anchors and
 * never downloads any: without an imported CSCA, document authenticity is reported as
 * "not established". Certificates are public data and stored unencrypted in app-private storage.
 */
@Singleton
class TrustAnchorStore @Inject constructor(@ApplicationContext context: Context) : TrustAnchorProvider {

    private val dir = File(context.filesDir, "trust_anchors")

    @Volatile
    private var cache: List<X509Certificate>? = null

    private val _infos = MutableStateFlow<List<TrustAnchorInfo>>(emptyList())
    val infos: StateFlow<List<TrustAnchorInfo>> = _infos.asStateFlow()

    init {
        refresh()
    }

    @Synchronized
    override fun anchors(): List<X509Certificate> = cache ?: load().also { cache = it }

    private fun load(): List<X509Certificate> = dir.listFiles { f -> f.extension == "der" }.orEmpty().mapNotNull { f ->
        try {
            CertificateParser.parse(f.readBytes()).firstOrNull()
        } catch (_: CertificateException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    private fun refresh() {
        cache = null
        _infos.value = anchors().map { TrustAnchorInfo(fingerprint(it), it.subjectX500Principal.name, it.notAfter) }
            .sortedBy { it.subject }
    }

    @Synchronized
    fun import(bytes: ByteArray): ImportResult {
        val certs = try {
            CertificateParser.parse(bytes)
        } catch (_: CertificateException) {
            return ImportResult.NotACertificate
        }
        dir.mkdirs()
        var added = 0
        var rejected = 0
        for (cert in certs) {
            if (!CertificateParser.isCaCertificate(cert)) {
                rejected++
                continue
            }
            File(dir, fingerprint(cert) + ".der").writeBytes(cert.encoded)
            added++
        }
        refresh()
        return ImportResult.Imported(added, rejected)
    }

    @Synchronized
    fun remove(fingerprint: String) {
        File(dir, "$fingerprint.der").delete()
        refresh()
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
}
