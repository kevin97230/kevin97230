package ia.assistant.core.tools

import ia.assistant.core.security.Principal
import ia.assistant.core.security.RiskLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Une capacité de l'IA (une « API ») : envoyer une touche infrarouge, notifier, lire l'état…
 * Chaque outil déclare sa permission et son niveau de risque ; la sécurité est appliquée
 * par [ToolExecutor], jamais par l'outil lui-même.
 */
interface Tool {
    /** Nom technique, utilisé par Claude via MCP (lettres, chiffres, `_`). */
    val name: String
    val description: String

    /** Schéma JSON des arguments attendus. */
    val inputSchema: JsonObject
    val risk: RiskLevel

    /** Outils autorisés même pendant l'arrêt d'urgence (ex. l'arrêt d'urgence lui-même). */
    val allowedDuringEmergency: Boolean get() = false

    /** Permission exigée pour cet appel, qui peut dépendre des arguments (ex. `ir.send.tv_salon`). */
    fun requiredPermission(arguments: JsonObject): String

    fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

data class ToolContext(val principal: Principal)

data class ToolResult(val text: String, val isError: Boolean = false) {
    companion object {
        fun ok(text: String) = ToolResult(text)
        fun error(text: String) = ToolResult(text, isError = true)
    }
}

/** Argument manquant ou invalide : renvoyé à l'appelant comme une erreur claire. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

fun JsonObject.requireString(key: String): String {
    val value = this[key] as? JsonPrimitive
    if (value == null || !value.isString || value.content.isBlank()) {
        throw ToolArgumentException("Argument « $key » manquant (texte attendu).")
    }
    return value.content
}

fun JsonObject.optionalInt(key: String, default: Int, range: IntRange): Int {
    val element = this[key] ?: return default
    val value = (element as? JsonPrimitive)?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
        ?: throw ToolArgumentException("Argument « $key » invalide (nombre entier attendu).")
    if (value !in range) throw ToolArgumentException("Argument « $key » hors limites ($range).")
    return value
}

class ToolRegistry {
    private val tools = linkedMapOf<String, Tool>()

    @Synchronized
    fun register(tool: Tool): ToolRegistry {
        require(tool.name.matches(Regex("[a-zA-Z0-9_-]{1,64}"))) { "Nom d'outil invalide : ${tool.name}" }
        require(tool.name !in tools) { "Outil déjà enregistré : ${tool.name}" }
        tools[tool.name] = tool
        return this
    }

    @Synchronized
    fun get(name: String): Tool? = tools[name]

    @Synchronized
    fun all(): List<Tool> = tools.values.toList()
}
