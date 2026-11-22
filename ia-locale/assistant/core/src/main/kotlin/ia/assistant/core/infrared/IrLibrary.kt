package ia.assistant.core.infrared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Un appareil pilotable en infrarouge et ses touches (nom → code commande). */
data class IrDevice(
    val id: String,
    val name: String,
    val brand: String,
    val protocol: IrProtocol,
    val address: Int,
    val buttons: Map<String, Int>,
) {
    fun signalFor(button: String): IrSignal {
        val command = buttons[button]
            ?: throw IllegalArgumentException("Touche « $button » inconnue pour $name. Touches : ${buttons.keys.joinToString()}")
        return IrEncoder.encode(protocol, address, command)
    }
}

/**
 * Bibliothèque des appareils infrarouges de la maison, décrite en JSON :
 *
 * ```json
 * { "appareils": [ { "id": "tv_salon", "nom": "TV du salon", "marque": "LG",
 *     "protocole": "NEC", "adresse": "0x04",
 *     "touches": { "allumer": "0x08", "volume_plus": "0x02" } } ] }
 * ```
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
                IrDevice(
                    id = id,
                    name = o.string("nom"),
                    brand = o["marque"]?.jsonPrimitive?.content ?: "",
                    protocol = IrProtocol.valueOf(o.string("protocole").uppercase()),
                    address = parseNumber(o.getValue("adresse") as JsonPrimitive),
                    buttons = o.getValue("touches").jsonObject.mapValues { (_, v) -> parseNumber(v as JsonPrimitive) },
                )
            }
            return IrLibrary(devices)
        }

        /** Bibliothèque d'exemple fournie avec l'application (codes à vérifier sur vos appareils). */
        fun loadDefault(): IrLibrary {
            val text = IrLibrary::class.java.getResourceAsStream(DEFAULT_RESOURCE)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("Bibliothèque infrarouge par défaut introuvable.")
            return parse(text)
        }

        private fun JsonObject.string(key: String): String =
            this[key]?.jsonPrimitive?.content ?: throw IllegalArgumentException("Champ « $key » manquant.")

        private fun parseNumber(p: JsonPrimitive): Int {
            val s = p.content.trim().lowercase()
            return if (s.startsWith("0x")) s.removePrefix("0x").toInt(16) else s.toInt()
        }
    }
}
