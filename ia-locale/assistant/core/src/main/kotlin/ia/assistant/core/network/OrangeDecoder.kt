package ia.assistant.core.network

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * Client de l'API locale des décodeurs TV Orange (port 8080, sur le Wi-Fi de la maison) :
 * `remoteControl/cmd?operation=10` (état) et `operation=01&key=…&mode=0` (appui sur une touche).
 */
class OrangeDecoderClient(val port: Int = DEFAULT_PORT) {

    /** Données d'état du décodeur (champ `result.data`). */
    fun status(host: String, timeoutMs: Int = 3000): JsonObject {
        val body = get(host, "operation=10", timeoutMs)
        val result = Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject
            ?: error("Réponse inattendue du décodeur.")
        return result["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    fun pressKey(host: String, keyCode: Int) {
        val body = get(host, "operation=01&key=$keyCode&mode=0", 3000)
        val code = runCatching {
            Json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("responseCode")?.jsonPrimitive?.content
        }.getOrNull()
        check(code == null || code == "0") { "Le décodeur a refusé la touche (code $code)." }
    }

    /** Vrai si un décodeur Orange répond à cette adresse. */
    fun probe(host: String, timeoutMs: Int = 600): Boolean = runCatching {
        Json.parseToJsonElement(get(host, "operation=10", timeoutMs)).jsonObject.containsKey("result")
    }.getOrDefault(false)

    private fun get(host: String, query: String, timeoutMs: Int): String {
        LocalNetwork.requireLocal(host)
        val url = URI("http://$host:$port/remoteControl/cmd?$query").toURL()
        val connection = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        try {
            check(connection.responseCode == 200) { "Le décodeur a répondu ${connection.responseCode}." }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
    }
}

/** Touches de la télécommande Orange et leurs codes. */
object OrangeKeys {
    val CODES: Map<String, Int> = linkedMapOf(
        "allumer" to 116,
        "chaine_suivante" to 402,
        "chaine_precedente" to 403,
        "volume_plus" to 115,
        "volume_moins" to 114,
        "muet" to 113,
        "haut" to 103,
        "bas" to 108,
        "gauche" to 105,
        "droite" to 106,
        "ok" to 352,
        "retour" to 158,
        "menu" to 139,
        "lecture_pause" to 164,
        "retour_rapide" to 168,
        "avance_rapide" to 159,
        "enregistrer" to 167,
        "vod" to 393,
    ) + (0..9).associate { "chiffre_$it" to 512 + it }
}

private const val DECODER_ADDRESS_KEY = "decodeur_orange.adresse"

private fun decoderAddress(settings: SettingsStore): String =
    settings.get(DECODER_ADDRESS_KEY)
        ?: throw ToolArgumentException("Adresse du décodeur inconnue : utilisez d'abord decodeur_rechercher.")

/** Cherche le décodeur Orange sur le Wi-Fi et retient son adresse. */
class OrangeDecoderFindTool(
    private val settings: SettingsStore,
    private val client: OrangeDecoderClient,
    private val candidates: () -> List<String> = LocalNetwork::neighbourHosts,
) : Tool {
    override val name = "decodeur_rechercher"
    override val description =
        "Cherche le décodeur TV Orange sur le Wi-Fi de la maison (environ 10 secondes) et retient son adresse. " +
            "On peut aussi indiquer directement son adresse IP (« adresse »)."
    override val inputSchema = objectSchema(
        properties = mapOf("adresse" to stringProperty("Adresse IP connue du décodeur, ex. 192.168.1.20 (facultatif).")),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "decoder.config"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val given = (arguments["adresse"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        val hosts = if (given != null) {
            if (!LocalNetwork.isLocalIpv4(given)) throw ToolArgumentException("Adresse « $given » refusée : réseau local uniquement.")
            listOf(given)
        } else {
            candidates()
        }
        if (hosts.isEmpty()) {
            return ToolResult.error("Aucun réseau Wi-Fi local détecté : connectez le téléphone au Wi-Fi de la Livebox.")
        }
        val found = LocalNetwork.scan(hosts) { client.probe(it, timeoutMs = if (given != null) 2000 else 800) }
        if (found.isEmpty()) {
            val range = hosts.map { it.substringBeforeLast('.') }.distinct().joinToString { "$it.x" }
            return ToolResult.error(
                "Aucun décodeur Orange trouvé (${hosts.size} adresses testées sur $range, port ${client.port}). " +
                    "Vérifiez qu'il est allumé et sur le même réseau que le téléphone.",
            )
        }
        settings.set(DECODER_ADDRESS_KEY, found.first())
        val others = if (found.size > 1) " (autres : ${found.drop(1).joinToString()})" else ""
        return ToolResult.ok("Décodeur Orange trouvé à l'adresse ${found.first()}$others. Adresse enregistrée.")
    }
}

/** Appuie sur une touche de la télécommande Orange, par le Wi-Fi (pas besoin de viser). */
class OrangeDecoderKeyTool(
    private val settings: SettingsStore,
    private val client: OrangeDecoderClient,
    private val pauseMillis: Long = 250,
) : Tool {
    override val name = "decodeur_touche"
    override val description =
        "Appuie sur une touche du décodeur TV Orange par le Wi-Fi. Touches : ${OrangeKeys.CODES.keys.joinToString()}."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "touche" to stringProperty("Nom de la touche, ex. chaine_suivante, volume_plus, ok."),
            "repetitions" to intProperty("Nombre d'appuis (1 à 10).", 1, 10),
        ),
        required = listOf("touche"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "decoder.send"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val key = arguments.requireString("touche")
        val code = OrangeKeys.CODES[key]
            ?: throw ToolArgumentException("Touche « $key » inconnue. Touches : ${OrangeKeys.CODES.keys.joinToString()}")
        val repeats = arguments.optionalInt("repetitions", default = 1, range = 1..10)
        val host = decoderAddress(settings)
        repeat(repeats) { i ->
            if (i > 0) Thread.sleep(pauseMillis)
            client.pressKey(host, code)
        }
        return ToolResult.ok("Touche « $key » envoyée au décodeur Orange" + if (repeats > 1) " ($repeats fois)." else ".")
    }
}

/** Met une chaîne par son numéro (tape les chiffres). */
class OrangeDecoderChannelTool(
    private val settings: SettingsStore,
    private val client: OrangeDecoderClient,
    private val pauseMillis: Long = 250,
) : Tool {
    override val name = "decodeur_chaine"
    override val description = "Met une chaîne sur le décodeur TV Orange par son numéro (ex. 1 pour TF1, 2 pour France 2)."
    override val inputSchema = objectSchema(
        properties = mapOf("numero" to intProperty("Numéro de la chaîne (1 à 999).", 1, 999)),
        required = listOf("numero"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "decoder.send"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments["numero"] == null) throw ToolArgumentException("Argument « numero » manquant.")
        val number = arguments.optionalInt("numero", default = 1, range = 1..999)
        val host = decoderAddress(settings)
        number.toString().forEachIndexed { i, digit ->
            if (i > 0) Thread.sleep(pauseMillis)
            client.pressKey(host, OrangeKeys.CODES.getValue("chiffre_$digit"))
        }
        return ToolResult.ok("Chaîne $number demandée au décodeur Orange.")
    }
}

/** État du décodeur (allumé / veille, ce qui est regardé). */
class OrangeDecoderStatusTool(
    private val settings: SettingsStore,
    private val client: OrangeDecoderClient,
) : Tool {
    override val name = "decodeur_etat"
    override val description = "Indique si le décodeur TV Orange est allumé ou en veille, et ce qu'il diffuse."
    override val inputSchema = objectSchema()
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "decoder.status"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val host = decoderAddress(settings)
        val data = client.status(host)
        fun field(name: String) = (data[name] as? JsonPrimitive)?.content
        val standby = when (field("activeStandbyState")) {
            "1" -> "en veille"
            "0" -> "allumé"
            else -> "état inconnu"
        }
        val details = listOfNotNull(
            field("friendlyName")?.let { "nom : $it" },
            field("osdContext")?.let { "écran : $it" },
            field("playedMediaType")?.let { "type : $it" },
            field("playedMediaId")?.let { "chaîne/programme : $it" },
        )
        return ToolResult.ok("Décodeur Orange ($host) $standby." + if (details.isEmpty()) "" else " " + details.joinToString(", ") + ".")
    }
}
