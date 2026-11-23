package ia.assistant.core.androidtv

import ia.assistant.core.network.SettingsStore
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Clé + certificat qui identifient ce téléphone auprès de la TV. */
class TlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    fun keyStore(password: CharArray): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null, null)
        setKeyEntry("assistant", privateKey, password, arrayOf(certificate))
    }

    companion object {
        private const val KEY = "android_tv.cle"
        private const val CERT = "android_tv.certificat"

        /** Charge l'identité enregistrée, ou en crée une (une seule fois). */
        fun loadOrCreate(settings: SettingsStore, commonName: String = "Assistant"): TlsIdentity {
            val key = settings.get(KEY)
            val cert = settings.get(CERT)
            if (key != null && cert != null) {
                val decoder = Base64.getDecoder()
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(key)))
                return TlsIdentity(privateKey, SelfSignedCertificate.parse(decoder.decode(cert)))
            }
            val identity = SelfSignedCertificate.generate(commonName)
            val encoder = Base64.getEncoder()
            settings.set(KEY, encoder.encodeToString(identity.privateKey.encoded))
            settings.set(CERT, encoder.encodeToString(identity.certificate.encoded))
            return identity
        }
    }
}

/**
 * Création d'un certificat X.509 auto-signé (RSA 2048, SHA-256) sans bibliothèque externe :
 * le format DER est assemblé à la main, puis signé avec l'API Java standard.
 */
object SelfSignedCertificate {
    private const val SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME = "2.5.4.3"

    fun generate(commonName: String, validityYears: Int = 10): TlsIdentity {
        val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = Der.seq(Der.set(Der.seq(Der.oid(COMMON_NAME), Der.utf8(commonName))))
        val algorithm = Der.seq(Der.oid(SHA256_WITH_RSA), Der.NULL)
        val serial = BigInteger(64, SecureRandom()).setBit(62)
        val tbs = Der.seq(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(serial),
            algorithm,
            name,
            Der.seq(Der.utcTime(Date(now - 86_400_000L)), Der.utcTime(Date(now + validityYears * 365L * 86_400_000L))),
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbs)
            sign()
        }
        val der = Der.seq(tbs, algorithm, Der.bitString(signature))
        return TlsIdentity(keyPair.private, parse(der))
    }

    fun parse(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    fun fingerprint(certificate: java.security.cert.Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    /** Module et exposant RSA en octets non signés, comme les attend l'appairage Android TV. */
    fun rsaParts(certificate: java.security.cert.Certificate): Pair<ByteArray, ByteArray> {
        val key = certificate.publicKey as? RSAPublicKey ?: error("La TV n'utilise pas une clé RSA.")
        return unsigned(key.modulus) to unsigned(key.publicExponent)
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}

/** Encodage DER minimal (ASN.1). */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = content.size
        if (n < 0x80) {
            out.write(n)
        } else {
            val lenBytes = BigInteger.valueOf(n.toLong()).toByteArray().dropWhile { it == 0.toByte() }
            out.write(0x80 or lenBytes.size)
            lenBytes.forEach { out.write(it.toInt()) }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { acc, p -> acc + p })
    fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())
    fun utf8(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))
    fun bitString(value: ByteArray) = tlv(0x03, byteArrayOf(0) + value)
    fun explicit(number: Int, content: ByteArray) = tlv(0xA0 + number, content)

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (part in parts.drop(2)) {
            val chunks = mutableListOf<Int>()
            var v = part
            do {
                chunks += (v and 0x7F).toInt()
                v = v ushr 7
            } while (v > 0)
            chunks.reversed().forEachIndexed { i, c -> out.write(if (i < chunks.size - 1) c or 0x80 else c) }
        }
        return tlv(0x06, out.toByteArray())
    }
}
