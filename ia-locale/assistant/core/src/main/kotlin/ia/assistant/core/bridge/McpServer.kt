package ia.assistant.core.bridge

import ia.assistant.core.security.Principal
import ia.assistant.core.tools.ToolExecutor
import ia.assistant.core.tools.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serveur MCP (Model Context Protocol, JSON-RPC 2.0) indépendant du transport.
 * Il expose les outils de l'IA du téléphone à Claude. Chaque appel passe par
 * [ToolExecutor], donc par la sécurité et le journal de bord.
 */
class McpServer(
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val serverName: String = "assistant-telephone",
    private val serverVersion: String = "0.1.0",
) {
    /**
     * Traite un message JSON-RPC reçu de la part de [principal] (déjà authentifié).
     * Retourne la réponse JSON, ou null pour une notification (pas de réponse).
     */
    fun handle(body: String, principal: Principal): String? {
        val message = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "JSON invalide.").toString()
        }
        if (message !is JsonObject) {
            return error(JsonNull, INVALID_REQUEST, "Un seul objet JSON-RPC est attendu par requête.").toString()
        }

        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        if (method == null) {
            // Réponse d'un client (non utilisée ici) ou message invalide.
            return if (id == null) null else error(id, INVALID_REQUEST, "Champ « method » manquant.").toString()
        }
        if (id == null) return null // Notification (ex. notifications/initialized).

        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
        val response = try {
            when (method) {
                "initialize" -> result(id, initialize(params))
                "ping" -> result(id, JsonObject(emptyMap()))
                "tools/list" -> result(id, listTools())
                "tools/call" -> callTool(id, params, principal)
                else -> error(id, METHOD_NOT_FOUND, "Méthode inconnue : $method")
            }
        } catch (e: Exception) {
            error(id, INTERNAL_ERROR, "Erreur interne : ${e.message}")
        }
        return response.toString()
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        val version = if (requested in SUPPORTED_VERSIONS) requested!! else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", serverName)
                    put("version", serverVersion)
                },
            )
            put("instructions", INSTRUCTIONS)
        }
    }

    private fun listTools(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                registry.all().forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            },
        )
    }

    private fun callTool(id: JsonElement, params: JsonObject, principal: Principal): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return error(id, INVALID_PARAMS, "Paramètre « name » manquant.")
        if (registry.get(name) == null) return error(id, INVALID_PARAMS, "Outil inconnu : $name")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        val outcome = executor.call(principal, name, arguments)
        return result(
            id,
            buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", outcome.text)
                            },
                        )
                    },
                )
                put("isError", outcome.isError)
            },
        )
    }

    private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

    companion object {
        /** Versions du protocole MCP acceptées, la plus récente en premier. */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        private const val INSTRUCTIONS =
            "Ces outils agissent sur le téléphone Android du propriétaire et sur les appareils de sa maison. " +
                "Les actions sont filtrées par la sécurité du téléphone : certaines peuvent être refusées " +
                "ou demander une validation du propriétaire sur son téléphone. En cas de doute ou de problème, " +
                "utilisez systeme_arret_urgence."
    }
}
