package ia.assistant.core.tools

import ia.assistant.core.audit.AuditEntry
import ia.assistant.core.audit.AuditLog
import ia.assistant.core.audit.AuditOutcome
import ia.assistant.core.security.Decision
import ia.assistant.core.security.Principal
import ia.assistant.core.security.SecurityPolicy
import kotlinx.serialization.json.JsonObject

data class ConfirmationRequest(
    val principal: Principal,
    val toolName: String,
    val arguments: JsonObject,
    val reason: String,
)

/** Demande au propriétaire de valider une action (notification sur le téléphone, console…). */
fun interface ConfirmationProvider {
    /** Bloque jusqu'à la réponse ; `true` si le propriétaire valide. */
    fun requestConfirmation(request: ConfirmationRequest): Boolean

    companion object {
        /** Par défaut, sans moyen de demander : on refuse. */
        val DENY_ALL = ConfirmationProvider { false }
    }
}

/**
 * Point de passage unique de toutes les actions :
 * sécurité → validation éventuelle du propriétaire → exécution → journal de bord.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val confirmations: ConfirmationProvider,
    private val audit: AuditLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun call(principal: Principal, toolName: String, arguments: JsonObject): ToolResult {
        val tool = registry.get(toolName)
            ?: return log(principal, toolName, arguments, AuditOutcome.FAILED, ToolResult.error("Outil inconnu : $toolName"))

        val permission = try {
            tool.requiredPermission(arguments)
        } catch (e: ToolArgumentException) {
            return log(principal, toolName, arguments, AuditOutcome.FAILED, ToolResult.error(e.message ?: "Arguments invalides."))
        }

        when (val decision = policy.authorize(principal, permission, tool.risk, tool.allowedDuringEmergency)) {
            is Decision.Deny ->
                return log(principal, toolName, arguments, AuditOutcome.DENIED, ToolResult.error("Refusé : ${decision.reason}"))

            is Decision.RequireConfirmation -> {
                val approved = runCatching {
                    confirmations.requestConfirmation(ConfirmationRequest(principal, toolName, arguments, decision.reason))
                }.getOrDefault(false)
                if (!approved) {
                    return log(
                        principal, toolName, arguments, AuditOutcome.REJECTED_BY_OWNER,
                        ToolResult.error("Non validé par le propriétaire : action annulée."),
                    )
                }
            }

            Decision.Allow -> Unit
        }

        val result = try {
            tool.execute(arguments, ToolContext(principal))
        } catch (e: ToolArgumentException) {
            ToolResult.error(e.message ?: "Arguments invalides.")
        } catch (e: Exception) {
            ToolResult.error("Échec de « $toolName » : ${e.message ?: e::class.simpleName}")
        }
        val outcome = if (result.isError) AuditOutcome.FAILED else AuditOutcome.EXECUTED
        return log(principal, toolName, arguments, outcome, result)
    }

    private fun log(
        principal: Principal,
        toolName: String,
        arguments: JsonObject,
        outcome: AuditOutcome,
        result: ToolResult,
    ): ToolResult {
        audit.record(
            AuditEntry(
                timestampMillis = clock(),
                principalId = principal.id,
                principalName = principal.displayName,
                tool = toolName,
                arguments = arguments.toString(),
                outcome = outcome,
                detail = result.text.take(500),
            ),
        )
        return result
    }
}
