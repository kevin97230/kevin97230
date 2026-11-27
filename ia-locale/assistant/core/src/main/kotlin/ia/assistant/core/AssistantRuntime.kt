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
import ia.assistant.core.infrared.IrSetVolumeTool
import ia.assistant.core.infrared.IrTransmitter
import ia.assistant.core.network.InMemorySettings
import ia.assistant.core.network.LocalNetwork
import ia.assistant.core.network.OrangeDecoderChannelTool
import ia.assistant.core.network.OrangeDecoderClient
import ia.assistant.core.network.OrangeDecoderFindTool
import ia.assistant.core.network.OrangeDecoderKeyTool
import ia.assistant.core.network.OrangeDecoderStatusTool
import ia.assistant.core.network.SettingsStore
import ia.assistant.core.remote.AndroidTvRoute
import ia.assistant.core.remote.ChannelBook
import ia.assistant.core.remote.ChannelSaveTool
import ia.assistant.core.remote.DecoderPower
import ia.assistant.core.remote.HomeInventoryTool
import ia.assistant.core.remote.IrRoute
import ia.assistant.core.remote.OrangeWifiRoute
import ia.assistant.core.remote.RemoteChannelTool
import ia.assistant.core.remote.RemoteDevice
import ia.assistant.core.remote.RemoteKeyTool
import ia.assistant.core.remote.RemotePowerTool
import ia.assistant.core.remote.RemoteSettings
import ia.assistant.core.remote.RemoteSettingsTool
import ia.assistant.core.remote.RemoteSourceTool
import ia.assistant.core.remote.RemoteVolumeTool
import ia.assistant.core.remote.ScenarioBook
import ia.assistant.core.remote.ScenarioCreateTool
import ia.assistant.core.remote.ScenarioDeleteTool
import ia.assistant.core.remote.ScenarioRunTool
import ia.assistant.core.remote.TvPower
import ia.assistant.core.remote.UniversalRemote
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

    /** Télécommande universelle : chaque appareil essaie le Wi-Fi, puis l'infrarouge. */
    val remote: UniversalRemote = run {
        val tvWifi = AndroidTvRoute(androidTv)
        val tvIr = IrRoute(platform.irLibrary, platform.irTransmitter, "tv_salon")
        val decoderWifi = OrangeWifiRoute(platform.settings, platform.orangeDecoder)
        val decoderIr = IrRoute(platform.irLibrary, platform.irTransmitter, "decodeur_orange")
        UniversalRemote(
            listOf(
                RemoteDevice(
                    id = "tv",
                    name = "TV Panasonic du salon",
                    aliases = setOf("télé", "tele", "television", "tv_salon", "panasonic"),
                    routes = listOf(tvWifi, tvIr),
                    power = TvPower(tvWifi, tvIr),
                ),
                RemoteDevice(
                    id = "decodeur",
                    name = "Décodeur Orange",
                    aliases = setOf("box", "orange", "decodeur_orange"),
                    routes = listOf(decoderWifi, decoderIr),
                    power = DecoderPower(decoderWifi, decoderIr, decoderWifi::standby),
                    stateReader = {
                        when (decoderWifi.standby()) {
                            true -> "en veille"
                            false -> "allumé"
                            null -> if (decoderWifi.ready) "injoignable en Wi-Fi (veille profonde ?)" else null
                        }
                    },
                ),
            ),
        )
    }
    val channels = ChannelBook(platform.settings)
    val scenarios = ScenarioBook(platform.settings)
    val remoteSettings = RemoteSettings(platform.settings)

    val registry: ToolRegistry = ToolRegistry()
        // La télécommande universelle d'abord : ce sont les outils que Claude doit préférer.
        .register(HomeInventoryTool(remote, channels, scenarios, remoteSettings))
        .register(RemoteKeyTool(remote))
        .register(RemotePowerTool(remote, on = true))
        .register(RemotePowerTool(remote, on = false))
        .register(RemoteChannelTool(remote, channels))
        .register(RemoteVolumeTool(remote))
        .register(RemoteSourceTool(remote, remoteSettings))
        .register(ScenarioRunTool(scenarios, { executor }))
        .register(ScenarioCreateTool(scenarios))
        .register(ScenarioDeleteTool(scenarios))
        .register(ChannelSaveTool(channels))
        .register(RemoteSettingsTool(remoteSettings))
        // Outils détaillés par technique, gardés en secours.
        .register(TvFindTool(androidTv, platform.neighbourHosts))
        .register(TvPairTool(androidTv))
        .register(TvCodeTool(androidTv))
        .register(TvKeyTool(androidTv))
        .register(TvAppTool(androidTv))
        .register(IrListDevicesTool(platform.irLibrary))
        .register(IrSendButtonTool(platform.irLibrary, platform.irTransmitter))
        .register(IrSetVolumeTool(platform.irLibrary, platform.irTransmitter))
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
