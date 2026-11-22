package ia.assistant.core.infrared

import ia.assistant.core.security.RiskLevel
import ia.assistant.core.tools.Tool
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import ia.assistant.core.tools.ToolResult
import ia.assistant.core.tools.intProperty
import ia.assistant.core.tools.objectSchema
import ia.assistant.core.tools.optionalInt
import ia.assistant.core.tools.requireString
import ia.assistant.core.tools.stringProperty
import kotlinx.serialization.json.JsonObject

/** Liste les appareils infrarouges connus et leurs touches. */
class IrListDevicesTool(private val library: () -> IrLibrary) : Tool {
    override val name = "ir_lister_appareils"
    override val description =
        "Liste les appareils de la maison pilotables en infrarouge depuis le téléphone (TV, décodeur, ventilateur…) " +
            "avec leurs touches disponibles."
    override val inputSchema = objectSchema()
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "ir.list"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val devices = library().devices
        if (devices.isEmpty()) return ToolResult.ok("Aucun appareil infrarouge configuré.")
        return ToolResult.ok(
            devices.joinToString("\n") { d ->
                "• ${d.id} — ${d.name} (${d.brand}, ${d.protocol}) : ${d.buttons.keys.joinToString()}"
            },
        )
    }
}

/** Appuie sur une touche de télécommande via l'émetteur infrarouge du téléphone. */
class IrSendButtonTool(
    private val library: () -> IrLibrary,
    private val transmitter: IrTransmitter,
    private val pauseBetweenRepeatsMillis: Long = 120,
) : Tool {
    override val name = "ir_envoyer_touche"
    override val description =
        "Appuie sur une touche de télécommande (allumer, volume_plus, chaine_suivante…) d'un appareil, " +
            "via l'émetteur infrarouge du téléphone. Le téléphone doit être orienté vers l'appareil. " +
            "Utilisez d'abord ir_lister_appareils pour connaître les appareils et touches."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "appareil" to stringProperty("Identifiant de l'appareil, ex. tv_salon."),
            "touche" to stringProperty("Nom de la touche, ex. allumer, volume_plus."),
            "repetitions" to intProperty("Nombre d'appuis successifs (1 à 10), ex. 5 pour monter le volume de 5 crans.", 1, 10),
        ),
        required = listOf("appareil", "touche"),
    )
    override val risk = RiskLevel.LOW

    /** Permission par appareil : on peut autoriser un enfant sur `ir.send.tv_chambre` seulement. */
    override fun requiredPermission(arguments: JsonObject) = "ir.send.${arguments.requireString("appareil")}"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val deviceId = arguments.requireString("appareil")
        val button = arguments.requireString("touche")
        val repeats = arguments.optionalInt("repetitions", default = 1, range = 1..10)

        val device = library().get(deviceId)
            ?: throw ToolArgumentException("Appareil « $deviceId » inconnu. Utilisez ir_lister_appareils.")
        if (button !in device.buttons) {
            throw ToolArgumentException("Touche « $button » inconnue pour ${device.name}. Touches : ${device.buttons.keys.joinToString()}")
        }
        if (!transmitter.isAvailable) {
            return ToolResult.error("Aucun émetteur infrarouge disponible sur cet appareil.")
        }

        repeat(repeats) { i ->
            if (i > 0) Thread.sleep(pauseBetweenRepeatsMillis)
            // Signal recalculé à chaque appui (bit de bascule RC5 : chaque appui est distinct).
            transmitter.transmit(device.signalFor(button))
        }
        val times = if (repeats > 1) " ($repeats fois)" else ""
        return ToolResult.ok("Touche « $button » envoyée à ${device.name}$times.")
    }
}
