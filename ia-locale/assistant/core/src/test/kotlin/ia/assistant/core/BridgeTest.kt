package ia.assistant.core

import ia.assistant.core.audit.AuditOutcome
import ia.assistant.core.audit.InMemoryAuditLog
import ia.assistant.core.bridge.McpServer
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ConfirmationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeTest {
    private val sent = mutableListOf<IrSignal>()
    private val notifications = mutableListOf<String>()
    private val audit = InMemoryAuditLog()

    private val runtime = AssistantRuntime(
        PlatformServices(
            irTransmitter = object : IrTransmitter {
                override val isAvailable = true
                override fun transmit(signal: IrSignal) {
                    sent += signal
                }
            },
            notifier = { title, message -> notifications += "$title|$message" },
            deviceStatus = { mapOf("Modèle" to "Test") },
            confirmations = ConfirmationProvider.DENY_ALL,
            audit = audit,
        ),
    )
    private val owner = Principal("moi", "Propriétaire", Role.OWNER)

    @AfterTest
    fun tearDown() = runtime.stopBridge()

    private fun rpc(body: String, principal: Principal = owner): JsonObject =
        Json.parseToJsonElement(runtime.mcp.handle(body, principal)!!).jsonObject

    private fun call(name: String, args: String, principal: Principal = owner): JsonObject =
        rpc("""{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"$name","arguments":$args}}""", principal)
            .getValue("result").jsonObject

    @Test
    fun `initialisation et liste des outils`() {
        val init = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""")
        assertEquals("2025-06-18", init["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)

        assertNull(runtime.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", owner))

        val tools = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")["result"]!!.jsonObject["tools"]!!.jsonArray
        val names = tools.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue("ir_envoyer_touche" in names && "systeme_arret_urgence" in names, names.toString())
    }

    @Test
    fun `envoi d'une touche infrarouge par Claude`() {
        val result = call("ir_envoyer_touche", """{"appareil":"tv_salon","touche":"volume_plus","repetitions":3}""")
        assertEquals(false, result["isError"]!!.jsonPrimitive.boolean)
        assertEquals(3, sent.size)
        assertEquals(AuditOutcome.EXECUTED, audit.recent(1).single().outcome)
    }

    @Test
    fun `erreurs claires et methodes inconnues`() {
        val bad = call("ir_envoyer_touche", """{"appareil":"frigo","touche":"allumer"}""")
        assertTrue(bad["isError"]!!.jsonPrimitive.boolean)
        assertTrue(sent.isEmpty())

        val unknown = rpc("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""")
        assertEquals(McpServer.METHOD_NOT_FOUND, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)

        val parse = Json.parseToJsonElement(runtime.mcp.handle("pas du json", owner)!!).jsonObject
        assertEquals(McpServer.PARSE_ERROR, parse["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `l'arret d'urgence fige les actions et ne se leve pas a distance`() {
        call("systeme_arret_urgence", "{}")
        val blocked = call("ir_envoyer_touche", """{"appareil":"tv_salon","touche":"allumer"}""")
        assertTrue(blocked["isError"]!!.jsonPrimitive.boolean)
        assertTrue(sent.isEmpty())
        assertTrue(runtime.registry.all().none { it.name.contains("lever") || it.name.contains("reprendre") })
    }

    @Test
    fun `un enfant ne peut piloter que l'appareil autorise`() {
        val child = Principal("enfant", "Enfant", Role.CHILD, grants = setOf("ir.send.tv_salon"))
        assertEquals(false, call("ir_envoyer_touche", """{"appareil":"tv_salon","touche":"allumer"}""", child)["isError"]!!.jsonPrimitive.boolean)
        assertEquals(true, call("ir_envoyer_touche", """{"appareil":"tv_parents","touche":"allumer"}""", child)["isError"]!!.jsonPrimitive.boolean)
        assertEquals(AuditOutcome.DENIED, audit.recent(1).single().outcome)
    }

    @Test
    fun `transport HTTP avec jeton`() {
        val token = TokenAuthenticator.generateToken()
        runtime.pairOwnerComputer(token)
        val port = runtime.startBridge(port = 0, host = "127.0.0.1")
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"telephone_notifier","arguments":{"titre":"Salut","message":"Bonjour depuis l'ordinateur 👋"}}}"""

        val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

        fun post(auth: String?, origin: String? = null): Pair<Int, String> {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .apply { auth?.let { header("Authorization", it) } }
                .apply { origin?.let { header("Origin", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            return response.statusCode() to response.body()
        }

        assertEquals(401, post(null).first)
        assertEquals(401, post("Bearer ${TokenAuthenticator.generateToken()}").first)
        assertEquals(403, post("Bearer $token", origin = "http://site-malveillant.example").first)

        val (code, text) = post("Bearer $token")
        assertEquals(200, code, text)
        assertTrue(text.contains("Notification affichée"), text)
        assertEquals(listOf("Salut|Bonjour depuis l'ordinateur 👋"), notifications)
    }
}
