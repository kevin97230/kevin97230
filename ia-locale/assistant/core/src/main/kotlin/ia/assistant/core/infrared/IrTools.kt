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

/**
 * Règle le volume à une valeur précise (« mets le volume à 17 »).
 *
 * L'infrarouge ne marche que dans un sens : la TV ne dit jamais à quel volume elle est.
 * On appuie donc d'abord sur `volume_moins` assez de fois pour être sûr d'arriver à 0,
 * puis exactement N fois sur `volume_plus`. C'est plus lent qu'un appui direct, mais
 * le résultat est juste même si quelqu'un a utilisé la vraie télécommande juste avant.
 */
class IrSetVolumeTool(
    private val library: () -> IrLibrary,
    private val transmitter: IrTransmitter,
    private val pauseBetweenPressesMillis: Long = 120,
    private val defaultDescent: Int = DEFAULT_DESCENT,
) : Tool {
    override val name = "ir_regler_volume"
    override val description =
        "Règle le volume d'un appareil à une valeur précise (ex. « mets le volume à 17 »), via l'émetteur " +
            "infrarouge du téléphone. Descend d'abord à 0 avec volume_moins, puis remonte avec volume_plus : " +
            "cela prend environ un quart de seconde par cran (≈ 12 s pour descendre de 50). " +
            "Le téléphone doit rester orienté vers l'appareil pendant tout ce temps."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "appareil" to stringProperty("Identifiant de l'appareil, ex. tv_salon."),
            "volume" to intProperty("Volume voulu, ex. 17.", 0, MAX_VOLUME),
            "descente" to intProperty(
                "Nombre d'appuis sur volume_moins pour être sûr d'arriver à 0 (par défaut $DEFAULT_DESCENT). " +
                    "À baisser si le son n'est jamais réglé très fort, pour aller plus vite.",
                1,
                MAX_VOLUME,
            ),
        ),
        required = listOf("appareil", "volume"),
    )
    override val risk = RiskLevel.LOW

    /** Même permission que pour une touche : qui peut monter le son peut le régler. */
    override fun requiredPermission(arguments: JsonObject) = "ir.send.${arguments.requireString("appareil")}"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val deviceId = arguments.requireString("appareil")
        val target = arguments.optionalInt("volume", default = -1, range = -1..MAX_VOLUME)
        if (target < 0) throw ToolArgumentException("Argument « volume » manquant (nombre entier de 0 à $MAX_VOLUME).")
        val descent = arguments.optionalInt("descente", default = defaultDescent, range = 1..MAX_VOLUME)

        val device = library().get(deviceId)
            ?: throw ToolArgumentException("Appareil « $deviceId » inconnu. Utilisez ir_lister_appareils.")
        val missing = listOf(VOLUME_DOWN, VOLUME_UP).filter { it !in device.buttons }
        if (missing.isNotEmpty()) {
            throw ToolArgumentException("${device.name} n'a pas de touche ${missing.joinToString(" ni ")} : impossible de régler le volume.")
        }
        if (!transmitter.isAvailable) {
            return ToolResult.error("Aucun émetteur infrarouge disponible sur cet appareil.")
        }

        var presses = 0
        fun press(button: String) {
            if (presses > 0) Thread.sleep(pauseBetweenPressesMillis)
            // Signal recalculé à chaque appui (bit de bascule RC5 : chaque appui compte pour un cran).
            transmitter.transmit(device.signalFor(button))
            presses++
        }
        repeat(descent) { press(VOLUME_DOWN) }
        repeat(target) { press(VOLUME_UP) }

        return ToolResult.ok("Volume de ${device.name} réglé à $target ($descent crans vers le bas, puis $target vers le haut).")
    }

    companion object {
        const val MAX_VOLUME = 100
        const val DEFAULT_DESCENT = 50
        private const val VOLUME_UP = "volume_plus"
        private const val VOLUME_DOWN = "volume_moins"
    }
}
