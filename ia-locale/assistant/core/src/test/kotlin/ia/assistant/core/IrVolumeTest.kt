package ia.assistant.core

import ia.assistant.core.infrared.IrEncoder
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.infrared.IrProtocol
import ia.assistant.core.infrared.IrSetVolumeTool
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrVolumeTest {
    private val sent = mutableListOf<IrSignal>()
    private var available = true
    private val transmitter = object : IrTransmitter {
        override val isAvailable get() = available
        override fun transmit(signal: IrSignal) {
            sent += signal
        }
    }
    private val context = ToolContext(Principal("moi", "Propriétaire", Role.OWNER))

    // NEC : le signal ne dépend que du code, on peut donc reconnaître chaque touche.
    private val library = IrLibrary.parse(
        """{"appareils":[
             {"id":"tv","nom":"TV test","protocole":"NEC","adresse":4,
              "touches":{"volume_plus":16,"volume_moins":17}},
             {"id":"ventilo","nom":"Ventilateur","protocole":"NEC","adresse":4,"touches":{"marche":8}}]}""",
    )
    private val tool = IrSetVolumeTool({ library }, transmitter, pauseBetweenPressesMillis = 0)

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun pressed(): List<String> {
        val up = IrEncoder.encode(IrProtocol.NEC, 4, 16).pattern
        val down = IrEncoder.encode(IrProtocol.NEC, 4, 17).pattern
        return sent.map {
            when {
                it.pattern.contentEquals(up) -> "+"
                it.pattern.contentEquals(down) -> "-"
                else -> "?"
            }
        }
    }

    @Test
    fun `descend a zero puis remonte a la valeur voulue`() {
        val result = tool.execute(args("""{"appareil":"tv","volume":17}"""), context)
        assertTrue(!result.isError, result.text)
        assertEquals(List(50) { "-" } + List(17) { "+" }, pressed())
        assertTrue("17" in result.text, result.text)
    }

    @Test
    fun `descente reglable et volume zero`() {
        tool.execute(args("""{"appareil":"tv","volume":0,"descente":30}"""), context)
        assertEquals(List(30) { "-" }, pressed())
    }

    @Test
    fun `erreurs claires`() {
        assertFailsWith<ToolArgumentException> { tool.execute(args("""{"appareil":"tv"}"""), context) }
        assertFailsWith<ToolArgumentException> { tool.execute(args("""{"appareil":"tv","volume":101}"""), context) }
        assertFailsWith<ToolArgumentException> { tool.execute(args("""{"appareil":"tv","volume":5,"descente":0}"""), context) }
        assertFailsWith<ToolArgumentException> { tool.execute(args("""{"appareil":"inconnu","volume":5}"""), context) }
        assertFailsWith<ToolArgumentException> { tool.execute(args("""{"appareil":"ventilo","volume":5}"""), context) }
        assertTrue(sent.isEmpty())

        available = false
        assertTrue(tool.execute(args("""{"appareil":"tv","volume":5}"""), context).isError)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `TV du salon par le pont MCP`() {
        val runtime = AssistantRuntime(
            PlatformServices(
                irTransmitter = transmitter,
                notifier = { _, _ -> },
                deviceStatus = { emptyMap() },
                confirmations = ia.assistant.core.tools.ConfirmationProvider.DENY_ALL,
                audit = ia.assistant.core.audit.InMemoryAuditLog(),
            ),
        )
        val response = runtime.mcp.handle(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ir_regler_volume","arguments":{"appareil":"tv_salon","volume":17,"descente":20}}}""",
            Principal("moi", "Propriétaire", Role.OWNER),
        )!!
        val result = Json.parseToJsonElement(response).jsonObject.getValue("result") as JsonObject
        assertTrue("isError\":true" !in result.toString(), result.toString())
        assertEquals(37, sent.size) // 20 vers le bas + 17 vers le haut
    }
}
