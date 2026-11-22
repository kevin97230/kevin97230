package ia.assistant.core.infrared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Code d'une touche : adresse (appareil) + commande. */
data class IrCode(val address: Int, val command: Int)

/** Un appareil pilotable en infrarouge et ses touches. */
data class IrDevice(
    val id: String,
    val name: String,
    val brand: String,
    val protocol: IrProtocol,
    val buttons: Map<String, IrCode>,
) {
    fun signalFor(button: String): IrSignal {
        val code = buttons[button]
            ?: throw IllegalArgumentException("Touche « $button » inconnue pour $name. Touches : ${buttons.keys.joinToString()}")
        return IrEncoder.encode(protocol, code.address, code.command)
    }
}

/**
 * Bibliothèque des appareils infrarouges de la maison, décrite en JSON :
 *
 * ```json
 * { "appareils": [ { "id": "tv_salon", "nom": "TV du salon", "marque": "Panasonic",
 *     "protocole": "KASEIKYO", "adresse": "80 02 20 00",
 *     "touches": {
 *       "allumer": "D0 03 00 00",
 *       "ok": { "adresse": "80 02 20 01", "commande": "90 00 00 00" } } } ] }
 * ```
 *
 * Les nombres s'écrivent en décimal (`8`), en hexadécimal (`"0x08"`) ou en octets séparés
 * par des espaces, comme dans la base libre Flipper-IRDB (`"80 02 20 00"`), qu'on peut donc copier telle quelle.
 * Une touche peut préciser sa propre adresse si elle diffère de celle de l'appareil.
 */
class IrLibrary(devices: List<IrDevice>) {
    private val byId = devices.associateBy { it.id }

    init {
        require(byId.size == devices.size) { "Identifiants d'appareils en double." }
    }

    val devices: List<IrDevice> get() = byId.values.toList()

    fun get(id: String): IrDevice? = byId[id]

    companion object {
        private const val DEFAULT_RESOURCE = "/ia/assistant/core/infrared/appareils_exemple.json"

        fun parse(json: String): IrLibrary {
            val root = Json.parseToJsonElement(json).jsonObject
            val devices = root.getValue("appareils").jsonArray.map { element ->
                val o = element.jsonObject
                val id = o.string("id")
                require(id.matches(Regex("[a-z0-9_]{1,40}"))) { "Identifiant invalide « $id » (minuscules, chiffres, _)." }
                val deviceAddress = o["adresse"]?.let(::parseNumber)
                IrDevice(
                    id = id,
                    name = o.string("nom"),
                    brand = o["marque"]?.jsonPrimitive?.content ?: "",
                    protocol = IrProtocol.valueOf(o.string("protocole").uppercase()),
                    buttons = o.getValue("touches").jsonObject.mapValues { (button, value) ->
                        parseCode(id, button, value, deviceAddress)
                    },
                )
            }
            return IrLibrary(devices)
        }

        /** Bibliothèque fournie avec l'application. */
        fun loadDefault(): IrLibrary {
            val text = IrLibrary::class.java.getResourceAsStream(DEFAULT_RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Bibliothèque infrarouge par défaut introuvable.")
            return parse(text)
        }

        private fun parseCode(deviceId: String, button: String, value: JsonElement, deviceAddress: Int?): IrCode {
            val (address, command) = when (value) {
                is JsonObject -> (value["adresse"]?.let(::parseNumber) ?: deviceAddress) to
                    parseNumber(value["commande"] ?: throw IllegalArgumentException("$deviceId/$button : « commande » manquante."))
                else -> deviceAddress to parseNumber(value)
            }
            requireNotNull(address) { "$deviceId/$button : aucune adresse (ni pour l'appareil, ni pour la touche)." }
            return IrCode(address, command)
        }

        private fun JsonObject.string(key: String): String =
            this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Champ « $key » manquant.")

        /** `8`, `"0x08"` ou `"80 02 20 00"` (octets, poids faible en premier, format Flipper-IRDB). */
        internal fun parseNumber(element: JsonElement): Int {
            val s = (element as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: throw IllegalArgumentException("Nombre attendu, reçu : $element")
            return when {
                ' ' in s -> s.split(Regex("\\s+"))
                    .also { require(it.size <= 4) { "Au plus 4 octets : $s" } }
                    .foldIndexed(0L) { i, acc, byte -> acc or (byte.toLong(16) shl (8 * i)) }
                    .toInt()
                s.startsWith("0x") -> s.removePrefix("0x").toLong(16).toInt()
                else -> s.toInt()
            }
        }
    }
}
