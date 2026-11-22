package ia.assistant.core.androidtv

import ia.assistant.core.network.LocalNetwork
import ia.assistant.core.security.RiskLevel
import ia.assistant.core.tools.Tool
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import ia.assistant.core.tools.ToolResult
import ia.assistant.core.tools.intProperty
import ia.assistant.core.tools.objectSchema
import ia.assistant.core.tools.optionalInt
import ia.assistant.core.tools.requireString
import ia.assistant.core.tools.stringProperty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Cherche la TV Android sur le Wi-Fi (service de télécommande, port 6467). */
class TvFindTool(
    private val tv: AndroidTvService,
    private val candidates: () -> List<String> = LocalNetwork::neighbourHosts,
) : Tool {
    override val name = "tv_rechercher"
    override val description =
        "Cherche la TV Android (Panasonic) sur le Wi-Fi de la maison et retient son adresse. " +
            "On peut aussi donner directement son adresse IP (« adresse »)."
    override val inputSchema = objectSchema(
        properties = mapOf("adresse" to stringProperty("Adresse IP connue de la TV, ex. 192.168.1.11 (facultatif).")),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "tv.config"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val given = (arguments["adresse"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null && !LocalNetwork.isLocalIpv4(given)) throw ToolArgumentException("Adresse « $given » refusée : réseau local uniquement.")
        val hosts = given?.let(::listOf) ?: candidates()
        if (hosts.isEmpty()) return ToolResult.error("Aucun réseau Wi-Fi local détecté : connectez le téléphone au Wi-Fi.")
        val found = LocalNetwork.scan(hosts) { tv.probe(it, timeoutMs = if (given != null) 2000 else 500) }
        if (found.isEmpty()) {
            return ToolResult.error("Aucune TV Android trouvée (${hosts.size} adresses testées). Vérifiez qu'elle est allumée et sur le même Wi-Fi.")
        }
        tv.address = found.first()
        val next = if (tv.isPaired) "Déjà appairée." else "Étape suivante : tv_appairer."
        return ToolResult.ok("TV Android trouvée à l'adresse ${found.first()}. $next")
    }
}

/** Étape 1 de l'appairage : la TV affiche un code. */
class TvPairTool(private val tv: AndroidTvService) : Tool {
    override val name = "tv_appairer"
    override val description =
        "Lance l'appairage avec la TV Android : un code à 6 caractères s'affiche sur la TV. " +
            "Demandez ce code au propriétaire, puis envoyez-le avec tv_code. À ne faire qu'une fois."
    override val inputSchema = objectSchema()

    // Appairer donne le contrôle de la TV : réservé au propriétaire, ou validé par lui.
    override val risk = RiskLevel.SENSITIVE

    override fun requiredPermission(arguments: JsonObject) = "tv.pair"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val host = tv.startPairing()
        return ToolResult.ok("Appairage lancé avec la TV ($host) : un code à 6 caractères s'affiche sur l'écran. Envoyez-le avec tv_code.")
    }
}

/** Étape 2 de l'appairage : envoi du code affiché sur la TV. */
class TvCodeTool(private val tv: AndroidTvService) : Tool {
    override val name = "tv_code"
    override val description = "Termine l'appairage avec la TV en envoyant le code à 6 caractères affiché sur son écran."
    override val inputSchema = objectSchema(
        properties = mapOf("code" to stringProperty("Code affiché sur la TV, 6 caractères (chiffres et lettres A à F).")),
        required = listOf("code"),
    )
    override val risk = RiskLevel.SENSITIVE

    override fun requiredPermission(arguments: JsonObject) = "tv.pair"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val code = arguments.requireString("code").replace(" ", "")
        try {
            tv.finishPairing(code)
        } catch (e: IllegalArgumentException) {
            throw ToolArgumentException(e.message ?: "Code invalide.")
        }
        return ToolResult.ok("TV appairée ✅. Elle peut maintenant être pilotée par le Wi-Fi (tv_touche, tv_ouvrir_app).")
    }
}

/** Appui sur une touche de la TV, par le Wi-Fi. */
class TvKeyTool(private val tv: AndroidTvService, private val pauseMillis: Long = 150) : Tool {
    override val name = "tv_touche"
    override val description =
        "Appuie sur une touche de la TV Android par le Wi-Fi (pas besoin de viser). Touches : ${AndroidTvKeys.CODES.keys.joinToString()}."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "touche" to stringProperty("Nom de la touche, ex. volume_plus, ok, accueil."),
            "repetitions" to intProperty("Nombre d'appuis (1 à 20).", 1, 20),
        ),
        required = listOf("touche"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "tv.send"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val key = arguments.requireString("touche")
        val code = AndroidTvKeys.CODES[key]
            ?: throw ToolArgumentException("Touche « $key » inconnue. Touches : ${AndroidTvKeys.CODES.keys.joinToString()}")
        val repeats = arguments.optionalInt("repetitions", default = 1, range = 1..20)
        tv.withRemote { remote ->
            repeat(repeats) { i ->
                if (i > 0) Thread.sleep(pauseMillis)
                remote.pressKey(code)
            }
        }
        return ToolResult.ok("Touche « $key » envoyée à la TV" + if (repeats > 1) " ($repeats fois)." else ".")
    }
}

/** Ouvre une application sur la TV. */
class TvAppTool(private val tv: AndroidTvService) : Tool {
    override val name = "tv_ouvrir_app"
    override val description = "Ouvre une application sur la TV Android : ${AndroidTvKeys.APPS.keys.joinToString()}."
    override val inputSchema = objectSchema(
        properties = mapOf("app" to stringProperty("Application : ${AndroidTvKeys.APPS.keys.joinToString()}.")),
        required = listOf("app"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "tv.send"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val app = arguments.requireString("app")
        val link = AndroidTvKeys.APPS[app]
            ?: throw ToolArgumentException("Application « $app » inconnue. Applications : ${AndroidTvKeys.APPS.keys.joinToString()}")
        tv.withRemote { it.launchApp(link) }
        return ToolResult.ok("Ouverture de « $app » sur la TV.")
    }
}
