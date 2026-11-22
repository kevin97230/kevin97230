package ia.assistant.core

import ia.assistant.core.security.Decision
import ia.assistant.core.security.EmergencyStop
import ia.assistant.core.security.Principal
import ia.assistant.core.security.RiskLevel
import ia.assistant.core.security.Role
import ia.assistant.core.security.SecurityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SecurityTest {
    private val stop = EmergencyStop()
    private val policy = SecurityPolicy(stop, clock = { 1_000L })

    private val owner = Principal("moi", "Propriétaire", Role.OWNER)
    private val child = Principal("enfant", "Enfant", Role.CHILD, grants = setOf("ir.send.tv_chambre"))
    private val trusted = Principal("proche", "Proche", Role.TRUSTED, grants = setOf("ir.send.*", "phone.pay"))

    @Test
    fun `un inconnu est toujours refuse`() {
        assertIs<Decision.Deny>(policy.authorize(Principal.UNKNOWN, "ir.list", RiskLevel.LOW))
    }

    @Test
    fun `le proprietaire agit librement mais confirme le critique`() {
        assertEquals(Decision.Allow, policy.authorize(owner, "n.importe.quoi", RiskLevel.SENSITIVE))
        assertIs<Decision.RequireConfirmation>(policy.authorize(owner, "trading.order", RiskLevel.CRITICAL))
    }

    @Test
    fun `un enfant est limite a ses autorisations, appareil par appareil`() {
        assertEquals(Decision.Allow, policy.authorize(child, "ir.send.tv_chambre", RiskLevel.LOW))
        assertIs<Decision.Deny>(policy.authorize(child, "ir.send.tv_salon", RiskLevel.LOW))
    }

    @Test
    fun `un enfant ne fait jamais d'action critique meme autorisee`() {
        val c = child.copy(grants = setOf("trading.order"))
        assertIs<Decision.Deny>(policy.authorize(c, "trading.order", RiskLevel.CRITICAL))
    }

    @Test
    fun `les jokers couvrent un prefixe mais pas tout`() {
        assertEquals(Decision.Allow, policy.authorize(trusted, "ir.send.tv_salon", RiskLevel.LOW))
        assertIs<Decision.Deny>(policy.authorize(trusted.copy(grants = setOf("*")), "ir.list", RiskLevel.LOW))
        assertIs<Decision.RequireConfirmation>(policy.authorize(trusted, "phone.pay", RiskLevel.CRITICAL))
    }

    @Test
    fun `un acces expire est refuse`() {
        val guest = Principal("invite", "Invité", Role.GUEST, grants = setOf("ir.list"), expiresAtMillis = 500L)
        assertIs<Decision.Deny>(policy.authorize(guest, "ir.list", RiskLevel.LOW))
    }

    @Test
    fun `l'arret d'urgence fige tout, sauf ce qui est prevu pour`() {
        stop.engage()
        assertIs<Decision.Deny>(policy.authorize(owner, "ir.list", RiskLevel.LOW))
        assertEquals(Decision.Allow, policy.authorize(owner, "system.emergency_stop", RiskLevel.LOW, allowedDuringEmergency = true))
        stop.release()
        assertEquals(Decision.Allow, policy.authorize(owner, "ir.list", RiskLevel.LOW))
    }
}
