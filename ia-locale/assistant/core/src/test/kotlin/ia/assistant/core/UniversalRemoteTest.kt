package ia.assistant.core

import ia.assistant.core.audit.InMemoryAuditLog
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.network.InMemorySettings
import ia.assistant.core.remote.ChannelBook
import ia.assistant.core.remote.DecoderPower
import ia.assistant.core.remote.KeyRoute
import ia.assistant.core.remote.RemoteChannelTool
import ia.assistant.core.remote.RemoteDevice
import ia.assistant.core.remote.RemoteKeyTool
import ia.assistant.core.remote.RemotePowerTool
import ia.assistant.core.remote.RemoteSettings
import ia.assistant.core.remote.RemoteSourceTool
import ia.assistant.core.remote.RemoteVolumeTool
import ia.assistant.core.remote.ScenarioBook
import ia.assistant.core.remote.TvPower
import ia.assistant.core.remote.UniversalRemote
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ConfirmationProvider
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Un faux chemin (Wi-Fi ou infrarouge) qui note les touches et peut tomber en panne après N appuis. */
private class FakeRoute(
    override val label: String,
    override val keys: Set<String>,
    var isReady: Boolean = true,
    var failAfter: Int = Int.MAX_VALUE,
) : KeyRoute {
    val pressed = mutableListOf<String>()
    override val ready get() = isReady
    override val notReadyReason = "pas configuré"
    override fun supports(key: String) = key in keys
    override fun press(key: String) {
        if (pressed.size >= failAfter) error("ne répond pas")
        pressed += key
    }
}

private val TV_WIFI_KEYS = setOf(
    "reveil", "veille", "allumer", "volume_plus", "volume_moins", "muet", "ok", "source",
    "entree_hdmi1", "entree_hdmi2", "entree_hdmi3", "entree_hdmi4", "entree_tv",
)
private val TV_IR_KEYS = setOf("allumer", "volume_plus", "volume_moins", "muet", "ok", "source")
private val DECODER_KEYS = setOf("allumer", "volume_plus", "volume_moins", "ok") + (0..9).map { "chiffre_$it" }

class UniversalRemoteTest {
    private val context = ToolContext(Principal("moi", "Propriétaire", Role.OWNER))
    private val tvWifi = FakeRoute("Wi-Fi", TV_WIFI_KEYS)
    private val tvIr = FakeRoute("infrarouge", TV_IR_KEYS)
    private val decWifi = FakeRoute("Wi-Fi", DECODER_KEYS)
    private val decIr = FakeRoute("infrarouge", setOf("allumer", "ok"))
    private var decoderStandby: Boolean? = true

    private val remote = UniversalRemote(
        listOf(
            RemoteDevice("tv", "TV", setOf("télé"), listOf(tvWifi, tvIr), TvPower(tvWifi, tvIr)),
            RemoteDevice("decodeur", "Décodeur", setOf("box"), listOf(decWifi, decIr), DecoderPower(decWifi, decIr) { decoderStandby }),
        ),
    )
    private val settings = InMemorySettings()

    private fun args(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) },
    )

    @Test
    fun `les noms courants retrouvent l'appareil`() {
        assertEquals("tv", remote.require("Télé").id)
        assertEquals("decodeur", remote.require("Décodeur").id)
        assertEquals("decodeur", remote.require("box").id)
        assertFailsWith<ToolArgumentException> { remote.require("frigo") }
    }

    @Test
    fun `touche par le Wi-Fi quand il est pret`() {
        val result = RemoteKeyTool(remote, pauseMillis = 0).execute(args("appareil" to "tv", "touche" to "ok", "repetitions" to 2), context)
        assertFalse(result.isError)
        assertEquals(listOf("ok", "ok"), tvWifi.pressed)
        assertTrue(tvIr.pressed.isEmpty())
        assertTrue("Wi-Fi" in result.text)
    }

    @Test
    fun `bascule sur l'infrarouge sans appui en double`() {
        tvWifi.failAfter = 2
        val result = RemoteKeyTool(remote, pauseMillis = 0).execute(args("appareil" to "tv", "touche" to "volume_plus", "repetitions" to 5), context)
        assertEquals(2, tvWifi.pressed.size)
        assertEquals(3, tvIr.pressed.size, "seulement les 3 appuis restants par l'infrarouge")
        assertTrue("Wi-Fi puis infrarouge" in result.text)
    }

    @Test
    fun `infrarouge directement si le Wi-Fi n'est pas configure`() {
        tvWifi.isReady = false
        RemoteKeyTool(remote, pauseMillis = 0).execute(args("appareil" to "tv", "touche" to "muet"), context)
        assertEquals(listOf("muet"), tvIr.pressed)
    }

    @Test
    fun `erreur claire si aucun chemin ne marche ou touche inconnue`() {
        tvWifi.isReady = false
        tvIr.isReady = false
        val e = assertFailsWith<IllegalStateException> { remote.require("tv").press("ok") }
        assertTrue("pas configuré" in e.message!!)
        assertFailsWith<ToolArgumentException> { remote.require("tv").press("cafe") }
    }

    @Test
    fun `TV en Wi-Fi - marche-arret seulement si son etat le demande`() {
        var screen: Boolean? = true
        val power = TvPower(tvWifi, tvIr) { screen }
        assertTrue("déjà allumée" in power.set(true))
        assertTrue(tvWifi.pressed.isEmpty())
        power.set(false)
        assertEquals(listOf("allumer"), tvWifi.pressed, "la touche veille est ignorée par la Panasonic : marche/arrêt")
        screen = false
        assertTrue("déjà éteinte" in power.set(false))
        power.set(true)
        assertEquals(listOf("allumer", "allumer"), tvWifi.pressed)
    }

    @Test
    fun `TV en Wi-Fi sans etat connu - le reveil pour allumer, jamais une bascule`() {
        TvPower(tvWifi, tvIr).set(true)
        assertEquals(listOf("reveil"), tvWifi.pressed)
    }

    @Test
    fun `TV injoignable en Wi-Fi - allumer par l'infrarouge, mais ne rien envoyer pour eteindre`() {
        tvWifi.failAfter = 0
        assertTrue("infrarouge" in TvPower(tvWifi, tvIr).set(true))
        assertEquals(listOf("allumer"), tvIr.pressed)
        assertTrue("déjà éteinte" in TvPower(tvWifi, tvIr).set(false))
        assertEquals(1, tvIr.pressed.size)
    }

    @Test
    fun `le decodeur n'appuie que si c'est utile`() {
        val power = DecoderPower(decWifi, decIr) { decoderStandby }
        decoderStandby = false
        assertTrue("déjà allumé" in power.set(true))
        assertTrue(decWifi.pressed.isEmpty())
        decoderStandby = true
        power.set(true)
        assertEquals(listOf("allumer"), decWifi.pressed)
        assertTrue("déjà en veille" in power.set(false))
        decoderStandby = null // veille profonde
        assertTrue("veille profonde" in power.set(false))
        power.set(true)
        assertEquals(listOf("allumer"), decIr.pressed)
    }

    @Test
    fun `allumer tout`() {
        val result = RemotePowerTool(remote, on = true).execute(args("appareil" to "tout"), context)
        assertFalse(result.isError)
        assertEquals(listOf("reveil"), tvWifi.pressed)
        assertEquals(listOf("allumer"), decWifi.pressed)
    }

    @Test
    fun `chaine par nom, par numero, et chaines apprises`() {
        val channels = ChannelBook(settings)
        val tool = RemoteChannelTool(remote, channels, pauseMillis = 0)
        tool.execute(args("nom" to "france 5"), context)
        assertEquals(listOf("chiffre_5"), decWifi.pressed)
        decWifi.pressed.clear()
        tool.execute(args("numero" to 27), context)
        assertEquals(listOf("chiffre_2", "chiffre_7"), decWifi.pressed)
        assertFailsWith<ToolArgumentException> { tool.execute(args("nom" to "BFM TV"), context) }
        channels.save("BFMTV", 15)
        decWifi.pressed.clear()
        tool.execute(args("nom" to "bfm tv"), context)
        assertEquals(listOf("chiffre_1", "chiffre_5"), decWifi.pressed)
    }

    @Test
    fun `volume plus, muet et valeur precise`() {
        val tool = RemoteVolumeTool(remote, pauseMillis = 0, defaultDescent = 10)
        tool.execute(args("action" to "plus", "crans" to 3), context)
        assertEquals(List(3) { "volume_plus" }, tvWifi.pressed)
        tvWifi.pressed.clear()
        tool.execute(args("action" to "muet"), context)
        assertEquals(listOf("muet"), tvWifi.pressed)
        tvWifi.pressed.clear()
        tool.execute(args("action" to "regler", "valeur" to 4), context)
        assertEquals(List(10) { "volume_moins" } + List(4) { "volume_plus" }, tvWifi.pressed)
        assertFailsWith<ToolArgumentException> { tool.execute(args("action" to "regler"), context) }
    }

    @Test
    fun `source du decodeur par le Wi-Fi, menu des sources en infrarouge`() {
        val remoteSettings = RemoteSettings(settings)
        remoteSettings.decoderHdmi = 2
        val tool = RemoteSourceTool(remote, remoteSettings)
        tool.execute(args("entree" to "decodeur"), context)
        assertEquals(listOf("entree_hdmi2"), tvWifi.pressed)
        tvWifi.isReady = false
        val result = tool.execute(args("entree" to "hdmi1"), context)
        assertEquals(listOf("source"), tvIr.pressed)
        assertTrue("menu des sources" in result.text)
    }

    @Test
    fun `les scenarios crees sont verifies et retenus`() {
        val book = ScenarioBook(settings)
        assertEquals(setOf("regarder_tele", "mode_film", "tout_eteindre"), book.all().map { it.name }.toSet())
        val steps = Json.parseToJsonElement(
            """[{"outil":"allumer","arguments":{"appareil":"tout"}},{"outil":"pause","arguments":{"secondes":5}},
                {"outil":"chaine","arguments":{"nom":"TF1"}}]""",
        )
        book.save(ia.assistant.core.remote.Scenario("soiree_foot", "Foot", ScenarioBook.parseSteps(steps)))
        assertEquals(3, ScenarioBook(settings).get("Soirée foot")!!.steps.size, "retrouvé avec un nom écrit autrement")

        val forbidden = Json.parseToJsonElement("""[{"outil":"systeme_arret_urgence"}]""")
        assertFailsWith<ToolArgumentException> {
            book.save(ia.assistant.core.remote.Scenario("piege", "", ScenarioBook.parseSteps(forbidden)))
        }
        val recursion = Json.parseToJsonElement("""[{"outil":"scenario_lancer","arguments":{"nom":"piege"}}]""")
        assertFailsWith<ToolArgumentException> {
            book.save(ia.assistant.core.remote.Scenario("boucle", "", ScenarioBook.parseSteps(recursion)))
        }
    }
}

/** Le tout assemblé dans l'IA : les scénarios repassent par la sécurité et le journal. */
class RemoteRuntimeTest {
    private val sent = mutableListOf<IrSignal>()
    private val audit = InMemoryAuditLog()
    private val runtime = AssistantRuntime(
        PlatformServices(
            irTransmitter = object : IrTransmitter {
                override val isAvailable = true
                override fun transmit(signal: IrSignal) {
                    sent += signal
                }
            },
            notifier = { _, _ -> },
            deviceStatus = { emptyMap() },
            confirmations = ConfirmationProvider.DENY_ALL,
            audit = audit,
            settings = InMemorySettings(),
            neighbourHosts = { emptyList() },
        ),
    )
    private val owner = Principal("moi", "Propriétaire", Role.OWNER)

    private fun call(principal: Principal, tool: String, json: String = "{}") =
        runtime.executor.call(principal, tool, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `les outils de la telecommande sont proposes a Claude`() {
        val names = runtime.registry.all().map { it.name }
        listOf("maison_appareils", "touche", "allumer", "eteindre", "chaine", "volume", "source", "scenario_lancer", "scenario_creer")
            .forEach { assertTrue(it in names, "$it manquant") }
        val inventory = call(owner, "maison_appareils")
        assertFalse(inventory.isError, inventory.text)
        assertTrue("infrarouge : configuré" in inventory.text)
        assertTrue("mode_film" in inventory.text)
    }

    @Test
    fun `sans Wi-Fi configure, la touche part par l'infrarouge`() {
        val result = call(owner, "touche", """{"appareil":"télé","touche":"volume_plus","repetitions":2}""")
        assertFalse(result.isError, result.text)
        assertEquals(2, sent.size)
        assertTrue("infrarouge" in result.text)
    }

    @Test
    fun `creer puis lancer un scenario, chaque etape au journal`() {
        val created = call(
            owner, "scenario_creer",
            """{"nom":"Son doux","description":"Volume bas","etapes":[
                 {"outil":"volume","arguments":{"action":"moins","crans":3}},
                 {"outil":"touche","arguments":{"appareil":"tv","touche":"ok"}}]}""",
        )
        assertFalse(created.isError, created.text)
        val run = call(owner, "scenario_lancer", """{"nom":"son_doux"}""")
        assertFalse(run.isError, run.text)
        assertEquals(4, sent.size)
        val tools = audit.recent(10).map { it.tool }
        assertTrue("volume" in tools && "touche" in tools && "scenario_lancer" in tools, tools.toString())
    }

    @Test
    fun `un enfant ne lance pas les etapes qu'on ne lui a pas accordees`() {
        call(owner, "scenario_creer", """{"nom":"tele","etapes":[{"outil":"touche","arguments":{"appareil":"tv","touche":"ok"}}]}""")
        val child = Principal("enfant", "Enfant", Role.CHILD, grants = setOf("remote.scenario.run"))
        val run = call(child, "scenario_lancer", """{"nom":"tele"}""")
        assertTrue(run.isError)
        assertTrue("remote.send.tv" in run.text, run.text)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `l'arret d'urgence fige aussi les scenarios`() {
        runtime.emergencyStop.engage()
        assertTrue(call(owner, "scenario_lancer", """{"nom":"tout_eteindre"}""").isError)
        assertTrue(sent.isEmpty())
    }
}
