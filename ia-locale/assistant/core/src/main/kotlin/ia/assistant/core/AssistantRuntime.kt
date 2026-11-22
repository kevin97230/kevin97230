package ia.assistant.core

import ia.assistant.core.audit.AuditLog
import ia.assistant.core.bridge.McpHttpServer
import ia.assistant.core.bridge.McpServer
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.infrared.IrListDevicesTool
import ia.assistant.core.infrared.IrSendButtonTool
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.security.EmergencyStop
import ia.assistant.core.security.Principal
import ia.assistant.core.security.Role
import ia.assistant.core.security.SecurityPolicy
import ia.assistant.core.tools.AuditReadTool
import ia.assistant.core.tools.ConfirmationProvider
import ia.assistant.core.tools.DeviceStatusTool
import ia.assistant.core.tools.EmergencyStopTool
import ia.assistant.core.tools.NotifyOwnerTool
import ia.assistant.core.tools.ToolExecutor
import ia.assistant.core.tools.ToolRegistry

/** Ce que la plateforme (Android, ordinateur…) fournit à l'IA. */
class PlatformServices(
    val irTransmitter: IrTransmitter,
    val notifier: (title: String, message: String) -> Unit,
    val deviceStatus: () -> Map<String, String>,
    val confirmations: ConfirmationProvider,
    val audit: AuditLog,
    val irLibrary: () -> IrLibrary = { IrLibrary.loadDefault() },
)

/** Assemble toutes les pièces de l'IA : outils, sécurité, journal et pont MCP. */
class AssistantRuntime(private val platform: PlatformServices) {
    val emergencyStop = EmergencyStop()
    val policy = SecurityPolicy(emergencyStop)
    val audit: AuditLog get() = platform.audit

    val registry: ToolRegistry = ToolRegistry()
        .register(IrListDevicesTool(platform.irLibrary))
        .register(IrSendButtonTool(platform.irLibrary, platform.irTransmitter))
        .register(NotifyOwnerTool(platform.notifier))
        .register(DeviceStatusTool(platform.deviceStatus))
        .register(AuditReadTool(platform.audit))
        .register(EmergencyStopTool(emergencyStop))

    val executor = ToolExecutor(registry, policy, platform.confirmations, platform.audit)
    val mcp = McpServer(registry, executor)
    val authenticator = TokenAuthenticator()

    private var http: McpHttpServer? = null

    /** Enregistre l'ordinateur du propriétaire comme appareil appairé. */
    fun pairOwnerComputer(token: String, name: String = "Ordinateur du propriétaire (Claude)") {
        authenticator.register(token, Principal(id = "pc-proprietaire", displayName = name, role = Role.OWNER))
    }

    @Synchronized
    fun startBridge(port: Int = McpHttpServer.DEFAULT_PORT, host: String = "0.0.0.0"): Int {
        http?.let { return it.listeningPort }
        val server = McpHttpServer(host, port, mcp, authenticator)
        server.start(SOCKET_READ_TIMEOUT_MS, true)
        http = server
        return server.listeningPort
    }

    @Synchronized
    fun stopBridge() {
        http?.stop()
        http = null
    }

    val isBridgeRunning: Boolean @Synchronized get() = http?.isAlive == true

    companion object {
        private const val SOCKET_READ_TIMEOUT_MS = 120_000
    }
}
