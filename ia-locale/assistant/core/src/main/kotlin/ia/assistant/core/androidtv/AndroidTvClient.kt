package ia.assistant.core.androidtv

import ia.assistant.core.network.LocalNetwork
import ia.assistant.core.network.SettingsStore
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Protocole de télécommande Android TV (« Android TV Remote v2 », celui de l'appli Google TV) :
 * - port 6467 : appairage une seule fois, avec un code à 6 caractères affiché sur la TV ;
 * - port 6466 : télécommande (touches, ouverture d'applications), en TLS avec le certificat appairé.
 */
object AndroidTvProtocol {
    const val PAIRING_PORT = 6467
    const val REMOTE_PORT = 6466
    const val STATUS_OK = 200L

    // Appairage (polo) : champs du message principal.
    const val F_PROTOCOL_VERSION = 1
    const val F_STATUS = 2
    const val F_PAIRING_REQUEST = 10
    const val F_PAIRING_REQUEST_ACK = 11
    const val F_OPTIONS = 20
    const val F_CONFIGURATION = 30
    const val F_CONFIGURATION_ACK = 31
    const val F_SECRET = 40
    const val F_SECRET_ACK = 41
    const val ENCODING_HEXADECIMAL = 3L
    const val ROLE_INPUT = 1L

    // Télécommande : champs du message principal.
    const val R_CONFIGURE = 1
    const val R_SET_ACTIVE = 2
    const val R_ERROR = 3
    const val R_PING_REQUEST = 8
    const val R_PING_RESPONSE = 9
    const val R_KEY_INJECT = 10
    const val R_START = 40
    const val R_APP_LINK = 90
    const val DIRECTION_SHORT = 3L
    const val ACTIVE_FEATURES = 622L

    fun pairingMessage(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().varint(F_PROTOCOL_VERSION, 2).varint(F_STATUS, STATUS_OK).apply(build).toByteArray()

    /**
     * Secret d'appairage : SHA-256(module client, exposant client, module TV, exposant TV, 2 derniers octets du code).
     * Le premier octet du résultat doit être égal aux 2 premiers caractères du code : cela vérifie la saisie.
     */
    fun pairingSecret(client: X509Certificate, server: java.security.cert.Certificate, code: String): ByteArray {
        val clean = code.trim().uppercase()
        require(clean.matches(Regex("[0-9A-F]{6}"))) { "Le code doit faire 6 caractères (chiffres 0-9 et lettres A-F)." }
        val (clientModulus, clientExponent) = SelfSignedCertificate.rsaParts(client)
        val (serverModulus, serverExponent) = SelfSignedCertificate.rsaParts(server)
        val digest = MessageDigest.getInstance("SHA-256").run {
            update(clientModulus)
            update(clientExponent)
            update(serverModulus)
            update(serverExponent)
            update(clean.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            digest()
        }
        require(digest[0] == clean.substring(0, 2).toInt(16).toByte()) { "Code incorrect : vérifiez les caractères affichés sur la TV." }
        return digest
    }
}

/** Connexion TLS vers la TV, avec notre certificat. La TV est authentifiée par son empreinte une fois appairée. */
internal fun openTlsSocket(host: String, port: Int, identity: TlsIdentity, timeoutMs: Int = 5000): SSLSocket {
    LocalNetwork.requireLocal(host)
    val password = CharArray(0)
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(identity.keyStore(password), password) }.keyManagers
    // La TV présente un certificat auto-signé : on l'accepte ici, puis on le compare à l'empreinte retenue à l'appairage.
    val acceptAny = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(acceptAny), null) }
    val plain = Socket().apply {
        connect(InetSocketAddress(host, port), timeoutMs)
        soTimeout = timeoutMs
    }
    return (context.socketFactory.createSocket(plain, host, port, true) as SSLSocket).apply { startHandshake() }
}

/** Appairage en deux temps : [start] fait afficher le code sur la TV, [finish] l'envoie. */
class AndroidTvPairing private constructor(
    private val socket: SSLSocket,
    private val identity: TlsIdentity,
) : Closeable {
    private val serverCertificate = socket.session.peerCertificates.first()

    val serverFingerprint: String get() = SelfSignedCertificate.fingerprint(serverCertificate)

    private fun exchange(message: ByteArray): ProtoMessage {
        writeFrame(socket.outputStream, message)
        val reply = ProtoMessage.parse(readFrame(socket.inputStream))
        val status = reply.long(AndroidTvProtocol.F_STATUS)
        check(status == AndroidTvProtocol.STATUS_OK) { "La TV a refusé l'appairage (statut $status)." }
        return reply
    }

    private fun begin(clientName: String) {
        exchange(
            AndroidTvProtocol.pairingMessage {
                message(AndroidTvProtocol.F_PAIRING_REQUEST) {
                    string(1, "atvremote")
                    string(2, clientName)
                }
            },
        )
        val encoding: ProtoWriter.() -> Unit = {
            varint(1, AndroidTvProtocol.ENCODING_HEXADECIMAL)
            varint(2, 6)
        }
        exchange(
            AndroidTvProtocol.pairingMessage {
                message(AndroidTvProtocol.F_OPTIONS) {
                    message(1, encoding)
                    varint(3, AndroidTvProtocol.ROLE_INPUT)
                }
            },
        )
        exchange(
            AndroidTvProtocol.pairingMessage {
                message(AndroidTvProtocol.F_CONFIGURATION) {
                    message(1, encoding)
                    varint(2, AndroidTvProtocol.ROLE_INPUT)
                }
            },
        )
        // La TV affiche maintenant le code. On laisse le temps à l'utilisateur de le lire.
        socket.soTimeout = 0
    }

    /** Envoie le code affiché sur la TV. Retourne l'empreinte du certificat de la TV, à retenir. */
    fun finish(code: String): String {
        val secret = AndroidTvProtocol.pairingSecret(identity.certificate, serverCertificate, code)
        socket.soTimeout = 10_000
        exchange(AndroidTvProtocol.pairingMessage { message(AndroidTvProtocol.F_SECRET) { bytes(1, secret) } })
        return serverFingerprint
    }

    override fun close() = runCatching { socket.close() }.let { }

    companion object {
        fun start(host: String, identity: TlsIdentity, clientName: String, port: Int = AndroidTvProtocol.PAIRING_PORT): AndroidTvPairing {
            val socket = openTlsSocket(host, port, identity)
            return AndroidTvPairing(socket, identity).also {
                try {
                    it.begin(clientName)
                } catch (e: Exception) {
                    it.close()
                    throw e
                }
            }
        }
    }
}

/**
 * Connexion de télécommande, gardée ouverte : la TV envoie sa configuration, des « ping »
 * réguliers auxquels on répond, et l'état allumé/éteint.
 */
class AndroidTvRemote private constructor(
    private val socket: SSLSocket,
    private val deviceModel: String,
    private val deviceVendor: String,
) : Closeable {
    private val ready = CountDownLatch(1)
    private val writeLock = Any()

    @Volatile
    var isOn: Boolean? = null
        private set

    @Volatile
    var closed = false
        private set

    private val reader = Thread({ readLoop() }, "android-tv-remote").apply { isDaemon = true }

    private fun send(message: ByteArray) = synchronized(writeLock) { writeFrame(socket.outputStream, message) }

    private fun readLoop() {
        try {
            socket.soTimeout = 0
            while (!closed) {
                val message = ProtoMessage.parse(readFrame(socket.inputStream))
                when {
                    message.has(AndroidTvProtocol.R_CONFIGURE) -> send(
                        ProtoWriter().message(AndroidTvProtocol.R_CONFIGURE) {
                            varint(1, AndroidTvProtocol.ACTIVE_FEATURES)
                            message(2) {
                                string(1, deviceModel)
                                string(2, deviceVendor)
                                varint(3, 1)
                                string(4, "1")
                                string(5, "atvremote")
                                string(6, "1.0.0")
                            }
                        }.toByteArray(),
                    )
                    message.has(AndroidTvProtocol.R_SET_ACTIVE) -> {
                        send(ProtoWriter().message(AndroidTvProtocol.R_SET_ACTIVE) { varint(1, AndroidTvProtocol.ACTIVE_FEATURES) }.toByteArray())
                        ready.countDown()
                    }
                    message.has(AndroidTvProtocol.R_PING_REQUEST) -> {
                        val value = message.message(AndroidTvProtocol.R_PING_REQUEST)?.long(1) ?: 0L
                        send(ProtoWriter().message(AndroidTvProtocol.R_PING_RESPONSE) { varint(1, value) }.toByteArray())
                    }
                    message.has(AndroidTvProtocol.R_START) -> {
                        isOn = (message.message(AndroidTvProtocol.R_START)?.long(1) ?: 0L) != 0L
                    }
                }
            }
        } catch (_: Exception) {
            // Connexion perdue : elle sera rouverte au prochain appui.
        } finally {
            closed = true
            ready.countDown()
            runCatching { socket.close() }
        }
    }

    fun awaitReady(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS) && !closed

    /** Appui court sur une touche (codes Android KeyEvent). */
    fun pressKey(keyCode: Int) {
        check(!closed) { "Connexion à la TV perdue." }
        send(
            ProtoWriter().message(AndroidTvProtocol.R_KEY_INJECT) {
                varint(1, keyCode.toLong())
                varint(2, AndroidTvProtocol.DIRECTION_SHORT)
            }.toByteArray(),
        )
    }

    /** Ouvre une application par son lien (ex. https://www.netflix.com/title). */
    fun launchApp(link: String) {
        check(!closed) { "Connexion à la TV perdue." }
        send(ProtoWriter().message(AndroidTvProtocol.R_APP_LINK) { string(1, link) }.toByteArray())
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }

    companion object {
        fun connect(
            host: String,
            identity: TlsIdentity,
            expectedFingerprint: String?,
            deviceModel: String = "Assistant",
            deviceVendor: String = "IA personnelle",
            port: Int = AndroidTvProtocol.REMOTE_PORT,
        ): AndroidTvRemote {
            val socket = openTlsSocket(host, port, identity)
            val fingerprint = SelfSignedCertificate.fingerprint(socket.session.peerCertificates.first())
            if (expectedFingerprint != null && fingerprint != expectedFingerprint) {
                socket.close()
                error("Le certificat de la TV a changé : par sécurité, refaites l'appairage (tv_appairer).")
            }
            val remote = AndroidTvRemote(socket, deviceModel, deviceVendor)
            remote.reader.start()
            if (!remote.awaitReady(8000)) {
                remote.close()
                error("La TV n'a pas accepté la connexion : l'appairage est peut-être à refaire (tv_appairer).")
            }
            return remote
        }
    }
}

/** Touches de la télécommande Android TV (codes KeyEvent d'Android) et applications. */
object AndroidTvKeys {
    val CODES: Map<String, Int> = linkedMapOf(
        "allumer" to 26,
        "volume_plus" to 24,
        "volume_moins" to 25,
        "muet" to 164,
        "chaine_suivante" to 166,
        "chaine_precedente" to 167,
        "haut" to 19,
        "bas" to 20,
        "gauche" to 21,
        "droite" to 22,
        "ok" to 23,
        "retour" to 4,
        "accueil" to 3,
        "menu" to 82,
        "source" to 178,
        "infos" to 165,
        "guide" to 172,
        "parametres" to 176,
        "lecture_pause" to 85,
        "lecture" to 126,
        "pause" to 127,
        "stop" to 86,
        "avance_rapide" to 90,
        "retour_rapide" to 89,
    ) + (0..9).associate { "chiffre_$it" to 7 + it }

    val APPS: Map<String, String> = linkedMapOf(
        "netflix" to "https://www.netflix.com/title",
        "youtube" to "https://www.youtube.com",
        "prime_video" to "https://app.primevideo.com",
        "disney_plus" to "https://www.disneyplus.com",
    )
}

/**
 * Gère l'adresse de la TV, l'appairage en cours et la connexion de télécommande (réutilisée entre les appuis).
 */
class AndroidTvService(
    private val settings: SettingsStore,
    private val clientName: String = "Assistant (téléphone)",
    private val pairingPort: Int = AndroidTvProtocol.PAIRING_PORT,
    private val remotePort: Int = AndroidTvProtocol.REMOTE_PORT,
) {
    private var pendingPairing: AndroidTvPairing? = null
    private var remote: AndroidTvRemote? = null

    val identity: TlsIdentity by lazy { TlsIdentity.loadOrCreate(settings) }

    var address: String?
        get() = settings.get(ADDRESS)
        set(value) {
            if (value != null) settings.set(ADDRESS, value)
        }

    val isPaired: Boolean get() = settings.get(FINGERPRINT) != null

    @Synchronized
    fun startPairing(): String {
        val host = address ?: error("Adresse de la TV inconnue : utilisez d'abord tv_rechercher.")
        pendingPairing?.close()
        pendingPairing = AndroidTvPairing.start(host, identity, clientName, pairingPort)
        return host
    }

    @Synchronized
    fun finishPairing(code: String) {
        val pairing = pendingPairing ?: error("Aucun appairage en cours : lancez d'abord tv_appairer.")
        try {
            settings.set(FINGERPRINT, pairing.finish(code))
        } finally {
            pairing.close()
            pendingPairing = null
        }
        remote?.close()
        remote = null
    }

    @Synchronized
    fun <T> withRemote(action: (AndroidTvRemote) -> T): T {
        val host = address ?: error("Adresse de la TV inconnue : utilisez d'abord tv_rechercher.")
        check(isPaired) { "Le téléphone n'est pas encore appairé avec la TV : utilisez tv_appairer." }
        val current = remote?.takeIf { !it.closed }
            ?: AndroidTvRemote.connect(host, identity, settings.get(FINGERPRINT), port = remotePort).also { remote = it }
        return try {
            action(current)
        } catch (e: Exception) {
            // Une nouvelle tentative avec une connexion neuve (la TV a pu fermer l'ancienne).
            current.close()
            val fresh = AndroidTvRemote.connect(host, identity, settings.get(FINGERPRINT), port = remotePort)
            remote = fresh
            action(fresh)
        }
    }

    /** Vrai si le service de télécommande Android TV répond à cette adresse. */
    fun probe(host: String, timeoutMs: Int = 500): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, pairingPort), timeoutMs) }
        true
    }.getOrDefault(false)

    companion object {
        const val ADDRESS = "android_tv.adresse"
        const val FINGERPRINT = "android_tv.empreinte"
    }
}
