package ia.assistant.core.tools

import ia.assistant.core.audit.AuditLog
import ia.assistant.core.security.EmergencyStop
import ia.assistant.core.security.RiskLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun objectSchema(
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties))
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
}

internal fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun intProperty(description: String, min: Int, max: Int): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
    put("minimum", min)
    put("maximum", max)
}

/** Engage l'arrêt d'urgence. Seul le propriétaire, sur le téléphone, peut le lever. */
class EmergencyStopTool(private val emergencyStop: EmergencyStop) : Tool {
    override val name = "systeme_arret_urgence"
    override val description =
        "Engage l'arrêt d'urgence : fige immédiatement toutes les actions de l'IA. " +
            "Il ne peut être levé que par le propriétaire, directement sur son téléphone."
    override val inputSchema = objectSchema()
    override val risk = RiskLevel.LOW
    override val allowedDuringEmergency = true

    override fun requiredPermission(arguments: JsonObject) = "system.emergency_stop"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        emergencyStop.engage()
        return ToolResult.ok("Arrêt d'urgence engagé par ${context.principal.displayName}. Toutes les actions sont figées.")
    }
}

/** Lecture du journal de bord. */
class AuditReadTool(
    private val audit: AuditLog,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : Tool {
    override val name = "systeme_journal"
    override val description = "Affiche les dernières actions du journal de bord (qui, quoi, quand, résultat)."
    override val inputSchema = objectSchema(
        properties = mapOf("nombre" to intProperty("Nombre d'entrées à afficher (1 à 100).", 1, 100)),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "system.audit"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val count = arguments.optionalInt("nombre", default = 20, range = 1..100)
        val entries = audit.recent(count)
        if (entries.isEmpty()) return ToolResult.ok("Le journal est vide.")
        val format = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(zone)
        return ToolResult.ok(
            entries.joinToString("\n") {
                "${format.format(Instant.ofEpochMilli(it.timestampMillis))} · ${it.principalName} · ${it.tool} · ${it.outcome} · ${it.detail}"
            },
        )
    }
}

/** Affiche un message au propriétaire (notification sur le téléphone). */
class NotifyOwnerTool(private val notifier: (title: String, message: String) -> Unit) : Tool {
    override val name = "telephone_notifier"
    override val description = "Affiche une notification sur le téléphone du propriétaire (titre + message)."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "titre" to stringProperty("Titre court de la notification."),
            "message" to stringProperty("Texte du message."),
        ),
        required = listOf("message"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "phone.notify"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val message = arguments.requireString("message").take(1000)
        val title = (arguments["titre"] as? JsonPrimitive)?.content?.take(100)
            ?.takeIf { it.isNotBlank() } ?: "Message de ${context.principal.displayName}"
        notifier(title, message)
        return ToolResult.ok("Notification affichée : « $title ».")
    }
}

/** État de l'appareil (batterie, modèle, réseau…), fourni par la plateforme. */
class DeviceStatusTool(private val provider: () -> Map<String, String>) : Tool {
    override val name = "telephone_etat"
    override val description = "Donne l'état du téléphone : modèle, version d'Android, batterie, infrarouge, réseau."
    override val inputSchema = objectSchema()
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "phone.status"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        ToolResult.ok(provider().entries.joinToString("\n") { (k, v) -> "$k : $v" })
}
