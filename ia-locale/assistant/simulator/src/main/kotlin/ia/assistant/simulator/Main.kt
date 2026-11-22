package ia.assistant.simulator

import ia.assistant.core.AssistantRuntime
import ia.assistant.core.PlatformServices
import ia.assistant.core.audit.InMemoryAuditLog
import ia.assistant.core.bridge.McpHttpServer
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.network.FileSettings
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.tools.ConfirmationProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Simulateur du téléphone sur ordinateur : même cœur, même pont MCP, mais l'infrarouge
 * et les notifications s'affichent dans la console. Permet de tester avec Claude tout de suite.
 *
 * Variables d'environnement : ASSISTANT_TOKEN (jeton fixe), ASSISTANT_PORT (8765 par défaut).
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "tv") return tvCommand(args.drop(1))

    val transmitter = object : IrTransmitter {
        override val isAvailable = true
        override fun transmit(signal: IrSignal) {
            println("📡 [IR simulé] ${signal.carrierFrequencyHz} Hz, ${signal.pattern.size} impulsions, ${signal.totalDurationMicros / 1000} ms")
        }
    }

    val confirmations = ConfirmationProvider { request ->
        synchronized(System.`in`) {
            println("\n⚠️  Validation demandée : ${request.principal.displayName} → ${request.toolName} ${request.arguments}")
            println("   ${request.reason}")
            print("   Valider ? (o/n) : ")
            readlnOrNull()?.trim()?.lowercase() in setOf("o", "oui", "y", "yes")
        }
    }

    val runtime = AssistantRuntime(
        PlatformServices(
            irTransmitter = transmitter,
            notifier = { title, message -> println("🔔 [Notification] $title — $message") },
            deviceStatus = {
                linkedMapOf(
                    "Appareil" to "Simulateur (ordinateur)",
                    "Système" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
                    "Infrarouge" to "simulé",
                    "Arrêt d'urgence" to "voir systeme_journal",
                )
            },
            confirmations = confirmations,
            audit = InMemoryAuditLog(),
        ),
    )

    val token = System.getenv("ASSISTANT_TOKEN")?.takeIf { it.length >= 32 } ?: TokenAuthenticator.generateToken()
    runtime.pairOwnerComputer(token)
    val port = runtime.startBridge(
        port = System.getenv("ASSISTANT_PORT")?.toIntOrNull() ?: McpHttpServer.DEFAULT_PORT,
        host = "127.0.0.1",
    )

    println(
        """
        |🤖 Simulateur de l'assistant démarré sur http://127.0.0.1:$port${McpHttpServer.PATH}
        |
        |Pour le connecter à Claude Code, dans un autre terminal :
        |  claude mcp add --transport http assistant-telephone http://127.0.0.1:$port${McpHttpServer.PATH} --header "Authorization: Bearer $token"
        |
        |Outils : ${runtime.registry.all().joinToString { it.name }}
        |Ctrl+C pour arrêter.
        """.trimMargin(),
    )

    Runtime.getRuntime().addShutdownHook(Thread { runtime.stopBridge() })
    Thread.currentThread().join()
}

/**
 * Pilotage de la TV Android depuis l'ordinateur, en ligne de commande :
 *   simulator tv rechercher [adresse]
 *   simulator tv appairer            (demande le code affiché sur la TV)
 *   simulator tv touche <nom> [n]
 *   simulator tv app <netflix|youtube|prime_video|disney_plus>
 * L'appairage est conservé dans ~/.assistant/reglages.properties.
 */
private fun tvCommand(args: List<String>) {
    val runtime = AssistantRuntime(
        PlatformServices(
            irTransmitter = object : IrTransmitter {
                override val isAvailable = false
                override fun transmit(signal: IrSignal) = Unit
            },
            notifier = { _, _ -> },
            deviceStatus = { emptyMap() },
            confirmations = ConfirmationProvider { true },
            audit = InMemoryAuditLog(),
            settings = FileSettings(File(System.getProperty("user.home"), ".assistant/reglages.properties")),
        ),
    )
    val me = Principal("proprietaire-pc", "Propriétaire (ordinateur)", Role.OWNER)
    fun call(tool: String, vararg pairs: Pair<String, Any>): Boolean {
        val arguments = JsonObject(pairs.associate { (k, v) -> k to if (v is Int) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
        val result = runtime.executor.call(me, tool, arguments)
        println((if (result.isError) "❌ " else "✅ ") + result.text)
        return !result.isError
    }
    when (args.firstOrNull()) {
        "rechercher" -> args.getOrNull(1)?.let { call("tv_rechercher", "adresse" to it) } ?: call("tv_rechercher")
        "appairer" -> if (call("tv_appairer")) {
            print("Code affiché sur la TV : ")
            val code = readlnOrNull()?.trim().orEmpty()
            call("tv_code", "code" to code)
        }
        "touche" -> call("tv_touche", "touche" to (args.getOrNull(1) ?: "ok"), "repetitions" to (args.getOrNull(2)?.toIntOrNull() ?: 1))
        "app" -> call("tv_ouvrir_app", "app" to (args.getOrNull(1) ?: "youtube"))
        else -> println("Commandes : tv rechercher [adresse] | tv appairer | tv touche <nom> [n] | tv app <nom>")
    }
    System.exit(0)
}
