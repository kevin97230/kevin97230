package ia.assistant.core

import ia.assistant.core.androidtv.AndroidTvProtocol
import ia.assistant.core.androidtv.AndroidTvService
import ia.assistant.core.androidtv.ProtoMessage
import ia.assistant.core.androidtv.ProtoWriter
import ia.assistant.core.androidtv.SelfSignedCertificate
import ia.assistant.core.androidtv.TlsIdentity
import ia.assistant.core.androidtv.TvAppTool
import ia.assistant.core.androidtv.TvKeyTool
import ia.assistant.core.androidtv.readFrame
import ia.assistant.core.androidtv.writeFrame
import ia.assistant.core.network.InMemorySettings
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ToolContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fausse TV Android : suit le protocole d'appairage (port « 6467 ») et de télécommande (port « 6466 »),
 * pour vérifier notre client de bout en bout, chiffrement TLS compris.
 */
class AndroidTvTest {
    private val tvIdentity: TlsIdentity = SelfSignedCertificate.generate("TV de test")
    private val shownCodes = ArrayBlockingQueue<String>(2)
    private val receivedKeys = Collections.synchronizedList(mutableListOf<Int>())
    private val receivedLinks = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var pongValue: Long? = null

    private fun serverSocket(): SSLServerSocket {
        val password = CharArray(0)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(tvIdentity.keyStore(password), password)
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(trustAll), null) }
        return (context.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket)
            .apply { needClientAuth = true }
    }

    private val pairingServer = serverSocket()
    private val remoteServer = serverSocket()

    private fun ok(build: ProtoWriter.() -> Unit) = AndroidTvProtocol.pairingMessage(build)

    private val pairingThread = Thread {
        runCatching {
            pairingServer.accept().use { raw ->
                val s = raw as SSLSocket
                val client = s.session.peerCertificates.first()
                val input = s.inputStream
                val output = s.outputStream
                assertTrue(ProtoMessage.parse(readFrame(input)).has(AndroidTvProtocol.F_PAIRING_REQUEST))
                writeFrame(output, ok { message(AndroidTvProtocol.F_PAIRING_REQUEST_ACK) { string(1, "TV") } })
                assertTrue(ProtoMessage.parse(readFrame(input)).has(AndroidTvProtocol.F_OPTIONS))
                writeFrame(output, ok { message(AndroidTvProtocol.F_OPTIONS) { varint(3, 1) } })
                val config = ProtoMessage.parse(readFrame(input)).message(AndroidTvProtocol.F_CONFIGURATION)!!
                assertEquals(AndroidTvProtocol.ENCODING_HEXADECIMAL, config.message(1)!!.long(1))
                writeFrame(output, ok { message(AndroidTvProtocol.F_CONFIGURATION_ACK) {} })

                // La TV choisit 2 octets au hasard et affiche le code (1er octet du hachage + ces 2 octets).
                val nonce = ByteArray(2).also { SecureRandom().nextBytes(it) }
                val (cm, ce) = SelfSignedCertificate.rsaParts(client)
                val (sm, se) = SelfSignedCertificate.rsaParts(tvIdentity.certificate)
                val expected = MessageDigest.getInstance("SHA-256").run {
                    update(cm); update(ce); update(sm); update(se); update(nonce); digest()
                }
                shownCodes.put("%02X".format(expected[0]) + nonce.joinToString("") { "%02X".format(it) })

                val secret = ProtoMessage.parse(readFrame(input)).message(AndroidTvProtocol.F_SECRET)!!.bytes(1)!!
                val status = if (secret.contentEquals(expected)) 200L else 402L
                writeFrame(output, ProtoWriter().varint(1, 2).varint(2, status).message(AndroidTvProtocol.F_SECRET_ACK) {}.toByteArray())
            }
        }
    }.apply { isDaemon = true; start() }

    private val remoteThread = Thread {
        runCatching {
            remoteServer.accept().use { raw ->
                val input = raw.inputStream
                val output = raw.outputStream
                writeFrame(output, ProtoWriter().message(AndroidTvProtocol.R_CONFIGURE) { varint(1, 622) }.toByteArray())
                val configure = ProtoMessage.parse(readFrame(input)).message(AndroidTvProtocol.R_CONFIGURE)!!
                assertEquals("atvremote", configure.message(2)!!.string(5))
                writeFrame(output, ProtoWriter().message(AndroidTvProtocol.R_SET_ACTIVE) { varint(1, 622) }.toByteArray())
                writeFrame(output, ProtoWriter().message(AndroidTvProtocol.R_PING_REQUEST) { varint(1, 42) }.toByteArray())
                while (true) {
                    val m = ProtoMessage.parse(readFrame(input))
                    when {
                        m.has(AndroidTvProtocol.R_PING_RESPONSE) -> pongValue = m.message(AndroidTvProtocol.R_PING_RESPONSE)!!.long(1)
                        m.has(AndroidTvProtocol.R_KEY_INJECT) -> {
                            val k = m.message(AndroidTvProtocol.R_KEY_INJECT)!!
                            assertEquals(AndroidTvProtocol.DIRECTION_SHORT, k.long(2))
                            receivedKeys += k.long(1)!!.toInt()
                        }
                        m.has(AndroidTvProtocol.R_APP_LINK) -> receivedLinks += m.message(AndroidTvProtocol.R_APP_LINK)!!.string(1)!!
                    }
                }
            }
        }
    }.apply { isDaemon = true; start() }

    private val settings = InMemorySettings()
    private val tv = AndroidTvService(settings, pairingPort = pairingServer.localPort, remotePort = remoteServer.localPort)
        .also { it.address = "127.0.0.1" }
    private val context = ToolContext(Principal("moi", "Propriétaire", Role.OWNER))

    @AfterTest
    fun stop() {
        pairingServer.close()
        remoteServer.close()
    }

    @Test
    fun `appairage avec code puis touches et applications`() {
        assertFalse(tv.isPaired)
        tv.startPairing()
        val code = shownCodes.poll(10, TimeUnit.SECONDS)!!
        tv.finishPairing(code)
        assertTrue(tv.isPaired)

        TvKeyTool(tv, pauseMillis = 0).execute(JsonObject(mapOf("touche" to JsonPrimitive("volume_plus"), "repetitions" to JsonPrimitive(2))), context)
        TvAppTool(tv).execute(JsonObject(mapOf("app" to JsonPrimitive("netflix"))), context)

        val deadline = System.currentTimeMillis() + 5000
        while ((receivedKeys.size < 2 || receivedLinks.isEmpty() || pongValue == null) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf(24, 24), receivedKeys.toList())
        assertEquals(listOf("https://www.netflix.com/title"), receivedLinks.toList())
        assertEquals(42L, pongValue)
    }

    @Test
    fun `un code mal saisi est refuse avant envoi`() {
        tv.startPairing()
        val code = shownCodes.poll(10, TimeUnit.SECONDS)!!
        val wrongFirstByte = "%02X".format((code.substring(0, 2).toInt(16) + 1) and 0xFF) + code.substring(2)
        assertFailsWith<IllegalArgumentException> { tv.finishPairing(wrongFirstByte) }
        assertFailsWith<IllegalArgumentException> { AndroidTvProtocol.pairingSecret(tvIdentity.certificate, tvIdentity.certificate, "XYZ") }
        assertFalse(tv.isPaired)
    }

    @Test
    fun `l'identite du telephone est creee une fois puis rechargee`() {
        val first = TlsIdentity.loadOrCreate(settings)
        val again = TlsIdentity.loadOrCreate(settings)
        assertContentEquals(first.certificate.encoded, again.certificate.encoded)
        first.certificate.checkValidity()
        first.certificate.verify(first.certificate.publicKey)
    }
}
