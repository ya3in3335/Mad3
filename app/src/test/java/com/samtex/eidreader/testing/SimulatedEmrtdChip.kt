package com.samtex.eidreader.testing

import com.samtex.eidreader.core.util.Hex
import com.samtex.eidreader.nfc.apdu.BerTlv
import com.samtex.eidreader.nfc.apdu.CommandApdu
import com.samtex.eidreader.nfc.error.ErrorKind
import com.samtex.eidreader.nfc.error.NfcEngineException
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.DESEngine
import org.bouncycastle.crypto.engines.DESedeEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.macs.ISO9797Alg3Mac
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.math.ec.ECPoint
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SIMULATED ICAO Doc 9303 chip (test-only). Implements the *chip side* of:
 * - BAC (3DES key derivation, mutual authentication, 3DES secure messaging),
 * - PACE ECDH Generic Mapping with brainpoolP256r1 and AES-128 (MRZ or CAN password),
 * - AES secure messaging after PACE,
 * - SELECT by AID / FID and READ BINARY over a small file system.
 *
 * It exists to exercise JMRTD's real client implementation end to end in JVM tests. It is NOT a
 * model of the Algerian CNIBE (whose internals are not publicly documented) and proves nothing
 * about compatibility with physical cards.
 */
class SimulatedEmrtdChip(
    private val mfFiles: Map<Int, ByteArray>,
    private val appFiles: Map<Int, ByteArray>,
    private val mrzInformation: String,
    private val can: String? = null,
    private val supportsBac: Boolean = true,
    private val paceOid: String? = null,
    private val hasApplication: Boolean = true,
    private val removeAfterCommands: Int? = null,
) {
    private val random = SecureRandom()
    private val mrzHash: ByteArray = sha1(mrzInformation.toByteArray(Charsets.US_ASCII))

    var commandCount = 0
        private set
    val instructions = mutableListOf<Int>()
    var secureMessagingActive = false
        private set

    private var appSelected = false
    private var selected: ByteArray? = null
    private var sm: SmSession? = null

    // BAC
    private var rndIc: ByteArray? = null

    // PACE
    private val curve = ECNamedCurveTable.getParameterSpec("brainpoolP256r1")
    private var paceKeyRef = 0
    private var paceStep = 0
    private var nonce: BigInteger? = null
    private var mappedG: ECPoint? = null
    private var picEphemeralSk: BigInteger? = null
    private var picEphemeralPk: ECPoint? = null
    private var pcdEphemeralPk: ECPoint? = null
    private var paceKsEnc: ByteArray? = null
    private var paceKsMac: ByteArray? = null

    fun transport(maxTransceive: Int = 261) = SimulatedTransport(maxTransceiveLength = maxTransceive) { respond(it) }

    fun respond(command: ByteArray): ByteArray {
        commandCount++
        removeAfterCommands?.let { if (commandCount > it) throw NfcEngineException(ErrorKind.TAG_LOST, "simulated card removal") }
        val cla = command[0].toInt() and 0xFF
        instructions += command[1].toInt() and 0xFF
        val session = sm
        if (session != null) {
            if ((cla and 0x0C) != 0x0C) {
                endSession()
                return sw(0x6987)
            }
            val plain = session.unwrap(command)
            if (plain == null) {
                endSession()
                return sw(0x6988)
            }
            return session.wrap(process(plain, secure = true))
        }
        if ((cla and 0x0C) == 0x0C) return sw(0x6988)
        return process(command, secure = false)
    }

    private fun endSession() {
        sm = null
        secureMessagingActive = false
    }

    private fun process(raw: ByteArray, secure: Boolean): ByteArray {
        val c = try {
            CommandApdu.decode(raw)
        } catch (_: IllegalArgumentException) {
            return sw(0x6700)
        }
        return when (c.ins) {
            0xA4 -> select(c, secure)
            0xB0 -> readBinary(c)
            0x84 -> getChallenge(c)
            0x82 -> externalAuthenticate(c)
            0x22 -> mseSetAt(c)
            0x86 -> generalAuthenticate(c)
            else -> sw(0x6D00)
        }
    }

    // ---- File system -------------------------------------------------------------------------

    private fun select(c: CommandApdu, secure: Boolean): ByteArray {
        val data = c.data
        return when (c.p1) {
            0x04 -> {
                if (hasApplication && Hex.encode(data) == "A0000002471001") {
                    appSelected = true
                    selected = null
                    sw(0x9000)
                } else {
                    sw(0x6A82)
                }
            }
            0x00, 0x02 -> {
                if (data.isEmpty()) {
                    appSelected = false
                    selected = null
                    return sw(0x9000)
                }
                val fid = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                when {
                    fid == 0x3F00 -> {
                        appSelected = false
                        selected = null
                        sw(0x9000)
                    }
                    appSelected && fid in appFiles -> {
                        if (!secure) return sw(0x6982)
                        selected = appFiles.getValue(fid)
                        sw(0x9000)
                    }
                    !appSelected && fid in mfFiles -> {
                        selected = mfFiles.getValue(fid)
                        sw(0x9000)
                    }
                    else -> sw(0x6A82)
                }
            }
            else -> sw(0x6A86)
        }
    }

    private fun readBinary(c: CommandApdu): ByteArray {
        val file = selected ?: return sw(0x6986)
        if ((c.p1 and 0x80) != 0) return sw(0x6A81) // SFI not supported by this simulation
        val offset = (c.p1 shl 8) or c.p2
        val le = c.ne ?: 256
        if (offset > file.size) return sw(0x6B00)
        val end = minOf(file.size, offset + le)
        val chunk = file.copyOfRange(offset, end)
        return chunk + if (end - offset < le) sw(0x6282) else sw(0x9000)
    }

    // ---- BAC ---------------------------------------------------------------------------------

    private fun getChallenge(c: CommandApdu): ByteArray {
        if (!supportsBac) return sw(0x6D00)
        if (c.ne != 8) return sw(0x6700)
        val rnd = ByteArray(8).also { random.nextBytes(it) }
        rndIc = rnd
        return rnd + sw(0x9000)
    }

    private fun externalAuthenticate(c: CommandApdu): ByteArray {
        if (!supportsBac) return sw(0x6D00)
        val challenge = rndIc ?: return sw(0x6985)
        rndIc = null
        val data = c.data
        if (data.size != 40) return sw(0x6700)
        val seed = mrzHash.copyOf(16)
        val kEnc = kdf(seed, 1)
        val kMac = kdf(seed, 2)
        val eIfd = data.copyOfRange(0, 32)
        val mIfd = data.copyOfRange(32, 40)
        if (!MessageDigest.isEqual(retailMac(kMac, pad(eIfd, 8)), mIfd)) return sw(0x6300)
        val s = des3(kEnc, eIfd, encrypt = false)
        val rndIfd = s.copyOfRange(0, 8)
        if (!MessageDigest.isEqual(s.copyOfRange(8, 16), challenge)) return sw(0x6300)
        val kIfd = s.copyOfRange(16, 32)
        val kIc = ByteArray(16).also { random.nextBytes(it) }
        val r = challenge + rndIfd + kIc
        val eIc = des3(kEnc, r, encrypt = true)
        val mIc = retailMac(kMac, pad(eIc, 8))
        val sessionSeed = ByteArray(16) { (kIfd[it].toInt() xor kIc[it].toInt()).toByte() }
        val ssc = challenge.copyOfRange(4, 8) + rndIfd.copyOfRange(4, 8)
        sm = SmSession(aes = false, kEnc = kdf(sessionSeed, 1), kMac = kdf(sessionSeed, 2), ssc = ssc)
        secureMessagingActive = true
        return eIc + mIc + sw(0x9000)
    }

    // ---- PACE (ECDH-GM, brainpoolP256r1, AES-128) ---------------------------------------------

    private fun mseSetAt(c: CommandApdu): ByteArray {
        if (paceOid == null) return sw(0x6D00)
        if (c.p1 != 0xC1 || c.p2 != 0xA4) return sw(0x6A86)
        val tlvs = BerTlv.parse(c.data)
        val oid = tlvs.firstOrNull { it.tag == 0x80 }?.value ?: return sw(0x6A80)
        val expectedOid = ASN1ObjectIdentifier(paceOid).encoded.let { it.copyOfRange(2, it.size) }
        if (!MessageDigest.isEqual(oid, expectedOid)) return sw(0x6A80)
        paceKeyRef = tlvs.firstOrNull { it.tag == 0x83 }?.value?.firstOrNull()?.toInt() ?: return sw(0x6A80)
        if (paceKeyRef == 2 && can == null) return sw(0x6A88)
        paceStep = 1
        return sw(0x9000)
    }

    private fun generalAuthenticate(c: CommandApdu): ByteArray {
        if (paceOid == null || paceStep == 0) return sw(0x6985)
        val outer = BerTlv.parse(c.data).firstOrNull { it.tag == 0x7C } ?: return sw(0x6A80)
        return when (paceStep) {
            1 -> {
                val password = if (paceKeyRef == 2) can!!.toByteArray(Charsets.ISO_8859_1) else sha1(mrzInfoHashSource())
                val kPi = kdfAes(password, 3)
                val s = ByteArray(16).also { random.nextBytes(it) }
                nonce = BigInteger(1, s)
                val z = aesCbc(kPi, ByteArray(16), s, encrypt = true)
                paceStep = 2
                tlv(0x7C, tlv(0x80, z)) + sw(0x9000)
            }
            2 -> {
                val pkMapPcd = curve.curve.decodePoint(outer.children.first { it.tag == 0x81 }.value)
                val skMap = randomScalar()
                val pkMap = curve.g.multiply(skMap).normalize()
                val h = pkMapPcd.multiply(skMap)
                mappedG = curve.g.multiply(nonce!!).add(h).normalize()
                paceStep = 3
                tlv(0x7C, tlv(0x82, pkMap.getEncoded(false))) + sw(0x9000)
            }
            3 -> {
                val pkPcd = curve.curve.decodePoint(outer.children.first { it.tag == 0x83 }.value).normalize()
                val sk = randomScalar()
                val pk = mappedG!!.multiply(sk).normalize()
                if (pk == pkPcd) return sw(0x6300)
                val shared = pkPcd.multiply(sk).normalize().affineXCoord.encoded
                paceKsEnc = kdfAes(shared, 1)
                paceKsMac = kdfAes(shared, 2)
                picEphemeralSk = sk
                picEphemeralPk = pk
                pcdEphemeralPk = pkPcd
                paceStep = 4
                tlv(0x7C, tlv(0x84, pk.getEncoded(false))) + sw(0x9000)
            }
            4 -> {
                val tPcd = outer.children.first { it.tag == 0x85 }.value
                val expected = token(paceKsMac!!, picEphemeralPk!!)
                paceStep = 0
                if (!MessageDigest.isEqual(tPcd, expected)) return sw(0x6300)
                val tPicc = token(paceKsMac!!, pcdEphemeralPk!!)
                sm = SmSession(aes = true, kEnc = paceKsEnc!!, kMac = paceKsMac!!, ssc = ByteArray(16))
                secureMessagingActive = true
                appSelected = false
                tlv(0x7C, tlv(0x86, tPicc)) + sw(0x9000)
            }
            else -> sw(0x6985)
        }
    }

    private fun mrzInfoHashSource(): ByteArray = mrzInformation.toByteArray(Charsets.US_ASCII)

    private fun token(kMac: ByteArray, pk: ECPoint): ByteArray {
        val oid = ASN1ObjectIdentifier(paceOid).encoded
        val data = tlv(0x7F49, oid + tlv(0x86, pk.getEncoded(false)))
        return cmac(kMac, data)
    }

    private fun randomScalar(): BigInteger {
        var k: BigInteger
        do {
            k = BigInteger(curve.n.bitLength(), random)
        } while (k.signum() == 0 || k >= curve.n)
        return k
    }

    // ---- Secure messaging --------------------------------------------------------------------

    private inner class SmSession(val aes: Boolean, val kEnc: ByteArray, val kMac: ByteArray, ssc: ByteArray) {
        private val ssc = ssc.copyOf()
        private val bs = if (aes) 16 else 8

        private fun increment() {
            for (i in ssc.indices.reversed()) {
                ssc[i] = (ssc[i] + 1).toByte()
                if (ssc[i].toInt() != 0) break
            }
        }

        private fun mac(data: ByteArray): ByteArray = if (aes) cmac(kMac, pad(ssc + data, bs)) else retailMac(kMac, pad(ssc + data, bs))

        private fun crypt(data: ByteArray, encrypt: Boolean): ByteArray = if (aes) {
            val iv = aesEcb(kEnc, ssc)
            aesCbc(kEnc, iv, data, encrypt)
        } else {
            des3(kEnc, data, encrypt)
        }

        fun unwrap(command: ByteArray): ByteArray? {
            increment()
            val c = try {
                CommandApdu.decode(command)
            } catch (_: IllegalArgumentException) {
                return null
            }
            val header = command.copyOfRange(0, 4)
            val objects = try {
                BerTlv.parse(c.data)
            } catch (_: Exception) {
                return null
            }
            val do87 = objects.firstOrNull { it.tag == 0x87 }
            val do97 = objects.firstOrNull { it.tag == 0x97 }
            val do8e = objects.firstOrNull { it.tag == 0x8E } ?: return null
            val macInput = ByteArrayOutputStream().apply {
                write(pad(header, bs))
                do87?.let { write(tlv(0x87, it.value)) }
                do97?.let { write(tlv(0x97, it.value)) }
            }.toByteArray()
            if (!MessageDigest.isEqual(mac(macInput), do8e.value)) return null
            val data = do87?.value?.let { v -> unpad(crypt(v.copyOfRange(1, v.size), encrypt = false)) } ?: ByteArray(0)
            val ne = do97?.value?.let { v ->
                val n = v.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                if (n == 0) (if (v.size == 1) 256 else 65536) else n
            }
            val ins = header[1].toInt() and 0xFF
            return CommandApdu(0x00, ins, header[2].toInt() and 0xFF, header[3].toInt() and 0xFF, data, ne).encode()
        }

        fun wrap(response: ByteArray): ByteArray {
            increment()
            val data = response.copyOfRange(0, response.size - 2)
            val sw = response.copyOfRange(response.size - 2, response.size)
            val do87 = if (data.isNotEmpty()) tlv(0x87, byteArrayOf(0x01) + crypt(pad(data, bs), encrypt = true)) else ByteArray(0)
            val do99 = tlv(0x99, sw)
            val mac = mac(do87 + do99)
            return do87 + do99 + tlv(0x8E, mac) + sw
        }
    }

    // ---- Primitives --------------------------------------------------------------------------

    companion object {
        fun sw(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

        fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

        /** ICAO 3DES key derivation: SHA-1(seed || counter)[0..16]. */
        fun kdf(seed: ByteArray, counter: Int): ByteArray =
            sha1(seed + byteArrayOf(0, 0, 0, counter.toByte())).copyOf(16)

        /** ICAO AES-128 key derivation (SHA-1 based). */
        fun kdfAes(secret: ByteArray, counter: Int): ByteArray =
            sha1(secret + byteArrayOf(0, 0, 0, counter.toByte())).copyOf(16)

        fun pad(data: ByteArray, bs: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(data)
            out.write(0x80)
            while (out.size() % bs != 0) out.write(0)
            return out.toByteArray()
        }

        fun unpad(data: ByteArray): ByteArray {
            var i = data.size - 1
            while (i >= 0 && data[i].toInt() == 0) i--
            require(i >= 0 && (data[i].toInt() and 0xFF) == 0x80) { "bad padding" }
            return data.copyOfRange(0, i)
        }

        fun tlv(tag: Int, value: ByteArray): ByteArray {
            val t = if (tag > 0xFF) byteArrayOf((tag ushr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
            val l = when {
                value.size < 0x80 -> byteArrayOf(value.size.toByte())
                value.size <= 0xFF -> byteArrayOf(0x81.toByte(), value.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (value.size ushr 8).toByte(), value.size.toByte())
            }
            return t + l + value
        }

        fun retailMac(key: ByteArray, padded: ByteArray): ByteArray {
            val mac = ISO9797Alg3Mac(DESEngine())
            mac.init(KeyParameter(key))
            mac.update(padded, 0, padded.size)
            return ByteArray(8).also { mac.doFinal(it, 0) }
        }

        fun cmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = CMac(AESEngine.newInstance(), 64)
            mac.init(KeyParameter(key))
            mac.update(data, 0, data.size)
            return ByteArray(8).also { mac.doFinal(it, 0) }
        }

        fun des3(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
            val cipher = CBCBlockCipher.newInstance(DESedeEngine())
            cipher.init(encrypt, ParametersWithIV(KeyParameter(key), ByteArray(8)))
            val out = ByteArray(data.size)
            var i = 0
            while (i < data.size) {
                cipher.processBlock(data, i, out, i)
                i += 8
            }
            return out
        }

        fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
            val cipher = CBCBlockCipher.newInstance(AESEngine.newInstance())
            cipher.init(encrypt, ParametersWithIV(KeyParameter(key), iv))
            val out = ByteArray(data.size)
            var i = 0
            while (i < data.size) {
                cipher.processBlock(data, i, out, i)
                i += 16
            }
            return out
        }

        fun aesEcb(key: ByteArray, block: ByteArray): ByteArray {
            val engine = AESEngine.newInstance()
            engine.init(true, KeyParameter(key))
            return ByteArray(16).also { engine.processBlock(block, 0, it, 0) }
        }
    }
}
