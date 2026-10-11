package com.samtex.eidreader.core.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Crypto/runtime setup for ICAO secure messaging.
 *
 * Android ships a stripped-down provider registered under the name "BC" that lacks algorithms
 * JMRTD needs (e.g. Brainpool curves for PACE). It is replaced by the full BouncyCastle
 * provider, appended *last* so that Conscrypt and AndroidKeyStore keep priority for everything
 * else (including SAMTEX's Keystore-backed history encryption).
 *
 * JMRTD/SCUBA java.util.logging output is disabled entirely so that no protocol detail can leak
 * into system logs.
 */
object CryptoProviders {
    @Volatile
    private var installed = false

    // Strong references: java.util.logging keeps loggers weakly.
    private val silenced = listOf("org.jmrtd", "net.sf.scuba").map { Logger.getLogger(it) }

    @Synchronized
    fun install() {
        if (installed) return
        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current == null || current.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        silenced.forEach { it.level = Level.OFF }
        installed = true
    }
}
