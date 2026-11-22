package ia.assistant.simulator

import ia.assistant.core.AssistantRuntime
import ia.assistant.core.PlatformServices
import ia.assistant.core.audit.InMemoryAuditLog
import ia.assistant.core.bridge.McpHttpServer
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrSignal
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.tools.ConfirmationProvider

/**
 * Simulateur du téléphone sur ordinateur : même cœur, même pont MCP, mais l'infrarouge
 * et les notifications s'affichent dans la console. Permet de tester avec Claude tout de suite.
 *
 * Variables d'environnement : ASSISTANT_TOKEN (jeton fixe), ASSISTANT_PORT (8765 par défaut).
 */
fun main() {
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
