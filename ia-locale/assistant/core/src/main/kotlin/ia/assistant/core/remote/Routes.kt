package ia.assistant.core.remote

import ia.assistant.core.androidtv.AndroidTvKeys
import ia.assistant.core.androidtv.AndroidTvService
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.network.OrangeDecoderClient
import ia.assistant.core.network.OrangeKeys
import ia.assistant.core.network.SettingsStore

/**
 * Un chemin pour appuyer sur une touche d'un appareil : le Wi-Fi (TV Android, décodeur Orange)
 * ou l'infrarouge du téléphone. La télécommande universelle essaie les chemins dans l'ordre.
 */
interface KeyRoute {
    /** « Wi-Fi » ou « infrarouge », pour dire à Claude par où c'est passé. */
    val label: String

    /** Vrai si le chemin est configuré (TV appairée, adresse du décodeur connue, émetteur présent…). */
    val ready: Boolean

    /** Pourquoi le chemin n'est pas prêt (affiché dans l'inventaire). */
    val notReadyReason: String

    fun supports(key: String): Boolean

    /** Un seul appui. Lève une exception si l'appareil ne répond pas. */
    fun press(key: String)

    val keys: Set<String>
}

/** TV Android par le Wi-Fi (protocole Android TV Remote v2). */
class AndroidTvRoute(private val tv: AndroidTvService) : KeyRoute {
    override val label = "Wi-Fi"
    override val ready get() = tv.address != null && tv.isPaired
    override val notReadyReason
        get() = when {
            tv.address == null -> "TV pas encore trouvée sur le Wi-Fi (tv_rechercher)"
            else -> "TV pas encore appairée (tv_appairer puis tv_code)"
        }
    override val keys: Set<String> get() = CODES.keys

    override fun supports(key: String) = key in CODES
    override fun press(key: String) {
        val code = CODES[key] ?: error("Touche « $key » inconnue en Wi-Fi.")
        tv.withRemote { it.pressKey(code) }
    }

    /**
     * La TV dit elle-même si l'écran est allumé (message « start » du protocole), juste après la connexion.
     * @return true / false, ou null si elle ne l'a pas dit (ou ne répond pas).
     */
    fun isOn(waitMillis: Long = 1500): Boolean? = runCatching {
        tv.withRemote { remote ->
            val deadline = System.currentTimeMillis() + waitMillis
            while (remote.isOn == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            remote.isOn
        }
    }.getOrNull()

    companion object {
        /**
         * Touches Android (KeyEvent) : celles de [AndroidTvKeys] et des touches directes en plus,
         * qui n'existent pas sur la télécommande infrarouge (réveil, veille, entrées HDMI).
         */
        val CODES: Map<String, Int> = AndroidTvKeys.CODES + linkedMapOf(
            "reveil" to 224, // KEYCODE_WAKEUP : allume, sans jamais éteindre
            "veille" to 223, // KEYCODE_SLEEP : met en veille, sans jamais allumer
            "entree_tv" to 242, // KEYCODE_TV_ANTENNA_CABLE
            "entree_hdmi1" to 243,
            "entree_hdmi2" to 244,
            "entree_hdmi3" to 245,
            "entree_hdmi4" to 246,
        )
    }
}

/** Décodeur Orange par le Wi-Fi (API locale, port 8080). */
class OrangeWifiRoute(
    private val settings: SettingsStore,
    private val client: OrangeDecoderClient,
) : KeyRoute {
    override val label = "Wi-Fi"
    val address: String? get() = settings.get(ADDRESS_KEY)
    override val ready get() = address != null
    override val notReadyReason get() = "décodeur pas encore trouvé sur le Wi-Fi (decodeur_rechercher)"
    override val keys: Set<String> get() = OrangeKeys.CODES.keys

    override fun supports(key: String) = key in OrangeKeys.CODES
    override fun press(key: String) {
        val host = address ?: error(notReadyReason)
        client.pressKey(host, OrangeKeys.CODES[key] ?: error("Touche « $key » inconnue en Wi-Fi."))
    }

    /** true = en veille, false = allumé, null = injoignable (veille profonde ou Wi-Fi coupé). */
    fun standby(): Boolean? {
        val host = address ?: return null
        val data = runCatching { client.status(host) }.getOrNull() ?: return null
        return when ((data["activeStandbyState"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    companion object {
        /** Même clé que les outils `decodeur_…` : l'adresse trouvée par decodeur_rechercher sert ici. */
        const val ADDRESS_KEY = "decodeur_orange.adresse"
    }
}

/** Émetteur infrarouge du téléphone, pour un appareil de la bibliothèque. */
class IrRoute(
    private val library: () -> IrLibrary,
    private val transmitter: IrTransmitter,
    private val deviceId: String,
) : KeyRoute {
    override val label = "infrarouge"
    override val ready get() = transmitter.isAvailable && library().get(deviceId) != null
    override val notReadyReason
        get() = if (!transmitter.isAvailable) "pas d'émetteur infrarouge sur ce téléphone" else "« $deviceId » absent de la bibliothèque infrarouge"
    override val keys: Set<String> get() = library().get(deviceId)?.buttons?.keys ?: emptySet()

    override fun supports(key: String) = key in keys
    override fun press(key: String) {
        val device = library().get(deviceId) ?: error(notReadyReason)
        // Signal recalculé à chaque appui (bit de bascule RC5 : chaque appui compte).
        transmitter.transmit(device.signalFor(key))
    }
}
