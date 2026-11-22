package ia.assistant.core

import com.sun.net.httpserver.HttpServer
import ia.assistant.core.network.InMemorySettings
import ia.assistant.core.network.LocalNetwork
import ia.assistant.core.network.OrangeDecoderChannelTool
import ia.assistant.core.network.OrangeDecoderClient
import ia.assistant.core.network.OrangeDecoderFindTool
import ia.assistant.core.network.OrangeDecoderKeyTool
import ia.assistant.core.network.OrangeDecoderStatusTool
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faux décodeur Orange sur la machine, qui répond comme l'API locale du vrai. */
class OrangeDecoderTest {
    private val pressedKeys = Collections.synchronizedList(mutableListOf<Int>())
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/remoteControl/cmd") { exchange ->
            val query = exchange.requestURI.query.orEmpty()
            val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val body = when (params["operation"]) {
                "10" -> """{"result":{"responseCode":"0","message":"ok","data":{"activeStandbyState":"0",
                    "osdContext":"LIVE","playedMediaType":"LIVE","playedMediaId":"192","friendlyName":"Décodeur TV UHD"}}}"""
                "01" -> {
                    pressedKeys += params.getValue("key").toInt()
                    """{"result":{"responseCode":"0","message":"ok","data":{}}}"""
                }
                else -> """{"result":{"responseCode":"-1","message":"unknown","data":{}}}"""
            }.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val client = OrangeDecoderClient(port = server.address.port)
    private val settings = InMemorySettings()
    private val context = ToolContext(Principal("moi", "Propriétaire", Role.OWNER))

    @AfterTest
    fun stop() = server.stop(0)

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
    )

    @Test
    fun `recherche sur le reseau puis touches et chaines`() {
        val find = OrangeDecoderFindTool(settings, client, candidates = { listOf("127.0.0.2", "127.0.0.1") })
        val found = find.execute(args(), context)
        assertFalse(found.isError, found.text)
        assertTrue(found.text.contains("127.0.0.1"))

        OrangeDecoderKeyTool(settings, client, pauseMillis = 0).execute(args("touche" to "volume_plus", "repetitions" to 2), context)
        OrangeDecoderChannelTool(settings, client, pauseMillis = 0).execute(args("numero" to 12), context)
        assertEquals(listOf(115, 115, 513, 514), pressedKeys.toList())

        val status = OrangeDecoderStatusTool(settings, client).execute(args(), context)
        assertTrue(status.text.contains("allumé") && status.text.contains("Décodeur TV UHD"), status.text)
    }

    @Test
    fun `erreurs claires`() {
        assertFailsWith<ToolArgumentException> {
            OrangeDecoderKeyTool(settings, client).execute(args("touche" to "ok"), context) // adresse pas encore connue
        }
        settings.set("decodeur_orange.adresse", "127.0.0.1")
        assertFailsWith<ToolArgumentException> {
            OrangeDecoderKeyTool(settings, client).execute(args("touche" to "autodestruction"), context)
        }
        assertFailsWith<ToolArgumentException> {
            OrangeDecoderFindTool(settings, client).execute(args("adresse" to "8.8.8.8"), context)
        }
        assertTrue(pressedKeys.isEmpty())
    }

    @Test
    fun `seules les adresses locales sont acceptees`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "127.0.0.1").forEach { assertTrue(LocalNetwork.isLocalIpv4(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "192.169.1.1", "exemple.fr", "192.168.1.300", "100.64.0.1").forEach {
            assertFalse(LocalNetwork.isLocalIpv4(it), it)
        }
    }
}
