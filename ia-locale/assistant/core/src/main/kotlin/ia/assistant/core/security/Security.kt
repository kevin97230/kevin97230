package ia.assistant.core.security

/**
 * Rôles reconnus par l'IA. Le propriétaire est toujours prioritaire.
 */
enum class Role {
    /** Le propriétaire : tous les droits, seul à pouvoir lever l'arrêt d'urgence. */
    OWNER,

    /** Proche de confiance : uniquement les autorisations accordées. */
    TRUSTED,

    /** Enfant : autorisations accordées, jamais d'action critique. */
    CHILD,

    /** Invité : accès limité et temporaire, jamais d'action critique. */
    GUEST,

    /** Toute personne non identifiée : aucun ordre accepté. */
    UNKNOWN,
}

/** Niveau de risque d'une action. */
enum class RiskLevel {
    /** Sans conséquence (changer de chaîne, lire un état). */
    LOW,

    /** Notable (envoyer un message…) : validation du propriétaire si un autre que lui le demande. */
    SENSITIVE,

    /** Irréversible ou coûteux (supprimer, payer, ordre de bourse) : toujours validé par le propriétaire. */
    CRITICAL,
}

/**
 * Une personne (ou un appareil agissant pour elle) qui donne un ordre à l'IA.
 *
 * @param grants autorisations accordées par le propriétaire, ex. `ir.send.tv_salon`
 *               ou `ir.send.*` (toutes les télécommandes). Ignorées pour le propriétaire.
 * @param expiresAtMillis fin d'un accès temporaire (invités), ou null.
 */
data class Principal(
    val id: String,
    val displayName: String,
    val role: Role,
    val grants: Set<String> = emptySet(),
    val expiresAtMillis: Long? = null,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis >= expiresAtMillis

    fun hasGrant(permission: String): Boolean = grants.any { grantMatches(it, permission) }

    companion object {
        val UNKNOWN = Principal(id = "inconnu", displayName = "Inconnu", role = Role.UNKNOWN)

        /** `ir.send.*` couvre `ir.send.tv_salon` ; `*` seul n'est jamais accepté pour un non-propriétaire. */
        internal fun grantMatches(grant: String, permission: String): Boolean = when {
            grant == permission -> true
            grant.endsWith(".*") -> permission.startsWith(grant.removeSuffix("*"))
            else -> false
        }
    }
}

/** Décision de sécurité pour une action demandée. */
sealed interface Decision {
    data object Allow : Decision
    data class RequireConfirmation(val reason: String) : Decision
    data class Deny(val reason: String) : Decision
}

/**
 * Arrêt d'urgence : fige toutes les actions. N'importe quel appareil autorisé peut l'engager,
 * mais seul le propriétaire, physiquement sur son téléphone, peut le lever.
 */
class EmergencyStop {
    @Volatile
    var engaged: Boolean = false
        private set

    fun engage() {
        engaged = true
    }

    fun release() {
        engaged = false
    }
}

/**
 * Politique de sécurité centrale : toute action passe par ici avant d'être exécutée.
 */
class SecurityPolicy(
    private val emergencyStop: EmergencyStop,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun authorize(
        principal: Principal,
        permission: String,
        risk: RiskLevel,
        allowedDuringEmergency: Boolean = false,
    ): Decision {
        if (principal.role == Role.UNKNOWN) {
            return Decision.Deny("Identité non reconnue : aucun ordre accepté.")
        }
        if (principal.isExpired(clock())) {
            return Decision.Deny("L'accès de ${principal.displayName} a expiré.")
        }
        if (emergencyStop.engaged && !allowedDuringEmergency) {
            return Decision.Deny("Arrêt d'urgence actif : toutes les actions sont figées jusqu'à ce que le propriétaire le lève.")
        }

        if (principal.role == Role.OWNER) {
            return when (risk) {
                RiskLevel.LOW, RiskLevel.SENSITIVE -> Decision.Allow
                RiskLevel.CRITICAL -> Decision.RequireConfirmation(
                    "Action irréversible : confirmation du propriétaire requise.",
                )
            }
        }

        if (!principal.hasGrant(permission)) {
            return Decision.Deny("Le propriétaire n'a pas accordé l'autorisation « $permission » à ${principal.displayName}.")
        }

        return when (risk) {
            RiskLevel.LOW -> Decision.Allow
            RiskLevel.SENSITIVE -> Decision.RequireConfirmation(
                "Action sensible demandée par ${principal.displayName} : le propriétaire doit valider.",
            )
            RiskLevel.CRITICAL -> when (principal.role) {
                Role.CHILD, Role.GUEST -> Decision.Deny("Action critique interdite pour le rôle ${principal.role}.")
                else -> Decision.RequireConfirmation(
                    "Action critique demandée par ${principal.displayName} : le propriétaire doit valider.",
                )
            }
        }
    }
}
