package ia.assistant.core

import ia.assistant.core.androidtv.AndroidTvService
import ia.assistant.core.androidtv.TvAppTool
import ia.assistant.core.androidtv.TvCodeTool
import ia.assistant.core.androidtv.TvFindTool
import ia.assistant.core.androidtv.TvKeyTool
import ia.assistant.core.androidtv.TvPairTool
import ia.assistant.core.audit.AuditLog
import ia.assistant.core.bridge.McpHttpServer
import ia.assistant.core.bridge.McpServer
import ia.assistant.core.bridge.TokenAuthenticator
import ia.assistant.core.infrared.IrLibrary
import ia.assistant.core.infrared.IrListDevicesTool
import ia.assistant.core.infrared.IrSendButtonTool
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.network.InMemorySettings
import ia.assistant.core.network.LocalNetwork
import ia.assistant.core.network.OrangeDecoderChannelTool
import ia.assistant.core.network.OrangeDecoderClient
import ia.assistant.core.network.OrangeDecoderFindTool
import ia.assistant.core.network.OrangeDecoderKeyTool
import ia.assistant.core.network.OrangeDecoderStatusTool
import ia.assistant.core.network.SettingsStore
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
    val settings: SettingsStore = InMemorySettings(),
    /** Adresses du réseau local à tester pour trouver les appareils (décodeur…). */
    val neighbourHosts: () -> List<String> = LocalNetwork::neighbourHosts,
    val orangeDecoder: OrangeDecoderClient = OrangeDecoderClient(),
)

/** Assemble toutes les pièces de l'IA : outils, sécurité, journal et pont MCP. */
class AssistantRuntime(private val platform: PlatformServices) {
    val emergencyStop = EmergencyStop()
    val policy = SecurityPolicy(emergencyStop)
    val audit: AuditLog get() = platform.audit

    /** TV Android pilotée par le Wi-Fi (appairage, touches, applications). */
    val androidTv = AndroidTvService(platform.settings)

    val registry: ToolRegistry = ToolRegistry()
        .register(TvFindTool(androidTv, platform.neighbourHosts))
        .register(TvPairTool(androidTv))
        .register(TvCodeTool(androidTv))
        .register(TvKeyTool(androidTv))
        .register(TvAppTool(androidTv))
        .register(IrListDevicesTool(platform.irLibrary))
        .register(IrSendButtonTool(platform.irLibrary, platform.irTransmitter))
        .register(OrangeDecoderFindTool(platform.settings, platform.orangeDecoder, platform.neighbourHosts))
        .register(OrangeDecoderKeyTool(platform.settings, platform.orangeDecoder))
        .register(OrangeDecoderChannelTool(platform.settings, platform.orangeDecoder))
        .register(OrangeDecoderStatusTool(platform.settings, platform.orangeDecoder))
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
