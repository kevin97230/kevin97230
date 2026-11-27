package ia.assistant.core.remote

import ia.assistant.core.network.SettingsStore
import ia.assistant.core.security.RiskLevel
import ia.assistant.core.tools.Tool
import ia.assistant.core.tools.ToolArgumentException
import ia.assistant.core.tools.ToolContext
import ia.assistant.core.tools.ToolExecutor
import ia.assistant.core.tools.ToolResult
import ia.assistant.core.tools.intProperty
import ia.assistant.core.tools.objectSchema
import ia.assistant.core.tools.optionalInt
import ia.assistant.core.tools.requireString
import ia.assistant.core.tools.stringProperty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * La télécommande universelle : des outils simples que Claude enchaîne, sans se soucier
 * de savoir si l'ordre passe par le Wi-Fi ou par l'infrarouge.
 */

private const val DEVICE_HELP = "Appareil : « tv » ou « decodeur » (on peut aussi dire télé, box…)."

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun routesText(used: List<String>) = used.joinToString(" puis ")

/** Réglages de la télécommande, enregistrés sur le téléphone. */
class RemoteSettings(private val settings: SettingsStore) {
    var decoderHdmi: Int
        get() = settings.get(HDMI_KEY)?.toIntOrNull()?.takeIf { it in 1..4 } ?: 1
        set(value) = settings.set(HDMI_KEY, value.toString())

    companion object {
        private const val HDMI_KEY = "telecommande.hdmi_decodeur"
    }
}

/** Inventaire : ce qu'il y a dans la maison, ce qui répond, et ce qu'on peut faire. */
class HomeInventoryTool(
    private val remote: UniversalRemote,
    private val channels: ChannelBook,
    private val scenarios: ScenarioBook,
    private val remoteSettings: RemoteSettings,
) : Tool {
    override val name = "maison_appareils"
    override val description =
        "À appeler en premier : liste les appareils de la maison (TV, décodeur), par où ils répondent " +
            "(Wi-Fi, infrarouge), leur état, leurs touches, les chaînes connues par leur nom et les scénarios disponibles."
    override val inputSchema = objectSchema()
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.status"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val text = buildString {
            appendLine("Appareils :")
            remote.devices.forEach { d ->
                append("• ${d.id} — ${d.name}")
                d.state()?.let { append(" — $it") }
                appendLine()
                d.routes.forEach { r ->
                    appendLine("   ${r.label} : " + if (r.ready) "prêt" else "indisponible (${r.notReadyReason})")
                }
                appendLine("   Touches : ${d.keys.joinToString()}")
            }
            appendLine("Le décodeur est branché sur l'entrée HDMI ${remoteSettings.decoderHdmi} de la TV.")
            appendLine("Chaînes connues : ${channels.all().entries.joinToString { "${it.key} (${it.value})" }}")
            appendLine("Scénarios (scenario_lancer) :")
            scenarios.all().forEach { appendLine("• ${it.name} — ${it.description}") }
        }
        return ToolResult.ok(text.trimEnd())
    }
}

/** Touche universelle : même nom de touche pour la TV et le décodeur, Wi-Fi puis infrarouge. */
class RemoteKeyTool(private val remote: UniversalRemote, private val pauseMillis: Long = 150) : Tool {
    override val name = "touche"
    override val description =
        "Appuie sur une touche de la TV ou du décodeur (ok, haut, retour, accueil, chaine_suivante, chiffre_5…). " +
            "Passe par le Wi-Fi si possible, sinon par l'infrarouge, automatiquement. Liste des touches : maison_appareils."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "appareil" to stringProperty(DEVICE_HELP),
            "touche" to stringProperty("Nom de la touche, ex. ok, haut, retour."),
            "repetitions" to intProperty("Nombre d'appuis (1 à 20).", 1, 20),
        ),
        required = listOf("appareil", "touche"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) =
        "remote.send.${remote.require(arguments.requireString("appareil")).id}"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = remote.require(arguments.requireString("appareil"))
        val key = arguments.requireString("touche")
        val repeats = arguments.optionalInt("repetitions", default = 1, range = 1..20)
        val used = device.press(key, repeats, pauseMillis)
        val times = if (repeats > 1) " ($repeats fois)" else ""
        return ToolResult.ok("Touche « $key » envoyée à ${device.name}$times, par ${routesText(used)}.")
    }
}

/** Allumer ou éteindre un appareil, ou tous. */
class RemotePowerTool(private val remote: UniversalRemote, private val on: Boolean) : Tool {
    override val name = if (on) "allumer" else "eteindre"
    override val description =
        (if (on) "Allume" else "Éteint (met en veille)") + " la TV, le décodeur, ou « tout ». " +
            "Sait éviter d'appuyer pour rien quand l'appareil dit déjà son état (Wi-Fi)."
    override val inputSchema = objectSchema(
        properties = mapOf("appareil" to stringProperty("« tv », « decodeur » ou « tout ».")),
        required = listOf("appareil"),
    )
    override val risk = RiskLevel.LOW

    private fun targets(arguments: JsonObject): List<RemoteDevice> {
        val name = arguments.requireString("appareil")
        return if (UniversalRemote.normalize(name) in setOf("tout", "tous", "all")) remote.devices else listOf(remote.require(name))
    }

    override fun requiredPermission(arguments: JsonObject): String {
        val devices = targets(arguments)
        return if (devices.size == 1) "remote.send.${devices.single().id}" else "remote.send.tout"
    }

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val results = targets(arguments).map { d ->
            runCatching { d.setPower(on) }.fold({ "✅ $it" }, { "❌ ${d.name} : ${it.message}" })
        }
        val text = results.joinToString("\n")
        return if (results.all { it.startsWith("❌") }) ToolResult.error(text) else ToolResult.ok(text)
    }
}

/** Chaîne par numéro ou par nom (« TF1 », « France 5 »). */
class RemoteChannelTool(
    private val remote: UniversalRemote,
    private val channels: ChannelBook,
    private val pauseMillis: Long = 250,
) : Tool {
    override val name = "chaine"
    override val description =
        "Met une chaîne sur le décodeur, par son numéro (« numero ») ou par son nom (« nom », ex. TF1, France 5, M6). " +
            "Si un nom est inconnu, demandez le numéro au propriétaire puis retenez-le avec chaine_enregistrer."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "numero" to intProperty("Numéro de la chaîne (1 à 999).", 1, 999),
            "nom" to stringProperty("Nom de la chaîne, ex. TF1."),
        ),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.send.decodeur"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.optionalString("nom")
        val number = when {
            arguments["numero"] != null -> arguments.optionalInt("numero", default = 1, range = 1..999)
            name != null -> channels.numberOf(name) ?: throw ToolArgumentException(
                "Chaîne « $name » inconnue. Chaînes connues : ${channels.all().keys.joinToString()}. " +
                    "Demandez son numéro, puis chaine_enregistrer.",
            )
            else -> throw ToolArgumentException("Indiquez « numero » ou « nom ».")
        }
        val decoder = remote.require("decodeur")
        val used = linkedSetOf<String>()
        number.toString().forEachIndexed { i, digit ->
            if (i > 0) Thread.sleep(pauseMillis)
            used += decoder.press("chiffre_$digit", 1)
        }
        val label = name?.let { "$it ($number)" } ?: "Chaîne $number"
        return ToolResult.ok("$label demandée au décodeur, par ${routesText(used.toList())}.")
    }
}

/** Retenir le numéro d'une chaîne pour pouvoir l'appeler par son nom. */
class ChannelSaveTool(private val channels: ChannelBook) : Tool {
    override val name = "chaine_enregistrer"
    override val description = "Retient le numéro d'une chaîne sur le décodeur Orange, pour pouvoir ensuite la mettre par son nom."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "nom" to stringProperty("Nom de la chaîne, ex. BFMTV."),
            "numero" to intProperty("Son numéro sur le décodeur (1 à 999).", 1, 999),
        ),
        required = listOf("nom", "numero"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.config"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.requireString("nom").take(40)
        if (arguments["numero"] == null) throw ToolArgumentException("Argument « numero » manquant.")
        val number = arguments.optionalInt("numero", default = 1, range = 1..999)
        channels.save(name, number)
        return ToolResult.ok("Chaîne « $name » retenue sur le numéro $number.")
    }
}

/** Volume : plus, moins, muet, ou une valeur précise. */
class RemoteVolumeTool(
    private val remote: UniversalRemote,
    private val pauseMillis: Long = 150,
    private val defaultDescent: Int = DEFAULT_DESCENT,
) : Tool {
    override val name = "volume"
    override val description =
        "Règle le son (de la TV par défaut) : action « plus » ou « moins » (avec « crans »), « muet », " +
            "ou « regler » à une valeur précise (« valeur »). Pour une valeur précise, le son descend d'abord à 0 " +
            "puis remonte : quelques secondes."
    override val inputSchema = objectSchema(
        properties = mapOf(
            "action" to stringProperty("« plus », « moins », « muet » ou « regler »."),
            "crans" to intProperty("Pour plus/moins : nombre de crans (1 à 20, 1 par défaut).", 1, 20),
            "valeur" to intProperty("Pour regler : volume voulu (0 à 100).", 0, 100),
            "descente" to intProperty("Pour regler : crans de descente pour être sûr d'arriver à 0 (par défaut $DEFAULT_DESCENT).", 1, 100),
            "appareil" to stringProperty("« tv » (par défaut) ou « decodeur »."),
        ),
        required = listOf("action"),
    )
    override val risk = RiskLevel.LOW

    private fun device(arguments: JsonObject) = remote.require(arguments.optionalString("appareil") ?: "tv")

    override fun requiredPermission(arguments: JsonObject) = "remote.send.${device(arguments).id}"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val device = device(arguments)
        return when (val action = UniversalRemote.normalize(arguments.requireString("action"))) {
            "plus", "moins" -> {
                val steps = arguments.optionalInt("crans", default = 1, range = 1..20)
                val used = device.press("volume_$action", steps, pauseMillis)
                ToolResult.ok("Volume de ${device.name} : $steps cran(s) en ${if (action == "plus") "plus" else "moins"}, par ${routesText(used)}.")
            }
            "muet" -> ToolResult.ok("Son de ${device.name} coupé/rétabli, par ${routesText(device.press("muet"))}.")
            "regler" -> {
                if (arguments["valeur"] == null) throw ToolArgumentException("Pour « regler », indiquez « valeur » (0 à 100).")
                val target = arguments.optionalInt("valeur", default = 0, range = 0..100)
                val descent = arguments.optionalInt("descente", default = defaultDescent, range = 1..100)
                val used = linkedSetOf<String>()
                used += device.press("volume_moins", descent, pauseMillis)
                if (target > 0) used += device.press("volume_plus", target, pauseMillis)
                ToolResult.ok("Volume de ${device.name} réglé à $target, par ${routesText(used.toList())}.")
            }
            else -> throw ToolArgumentException("Action « $action » inconnue : plus, moins, muet ou regler.")
        }
    }

    companion object {
        const val DEFAULT_DESCENT = 50
    }
}

/** Entrée de la TV : décodeur, HDMI 1 à 4, ou TV (antenne). */
class RemoteSourceTool(
    private val remote: UniversalRemote,
    private val remoteSettings: RemoteSettings,
) : Tool {
    override val name = "source"
    override val description =
        "Change l'entrée de la TV : « decodeur », « hdmi1 » à « hdmi4 » ou « tv » (antenne). " +
            "Direct par le Wi-Fi ; en infrarouge, ouvre seulement le menu des sources."
    override val inputSchema = objectSchema(
        properties = mapOf("entree" to stringProperty("« decodeur », « hdmi1 »… « hdmi4 », ou « tv ».")),
        required = listOf("entree"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.send.tv"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val wanted = UniversalRemote.normalize(arguments.requireString("entree"))
        val key = when {
            wanted in setOf("decodeur", "box", "orange") -> "entree_hdmi${remoteSettings.decoderHdmi}"
            wanted.matches(Regex("hdmi[1-4]")) -> "entree_$wanted"
            wanted in setOf("tv", "antenne", "tnt") -> "entree_tv"
            else -> throw ToolArgumentException("Entrée « $wanted » inconnue : decodeur, hdmi1 à hdmi4, ou tv.")
        }
        val tv = remote.require("tv")
        val direct = tv.routes.firstOrNull { it.ready && it.supports(key) }
        if (direct != null) {
            try {
                direct.press(key)
                return ToolResult.ok("TV mise sur l'entrée ${key.removePrefix("entree_")} (${direct.label}).")
            } catch (_: Exception) {
                // On tente le menu des sources ci-dessous.
            }
        }
        tv.press("source")
        return ToolResult.ok(
            "Pas d'accès direct à l'entrée (TV pas joignable en Wi-Fi) : le menu des sources est ouvert. " +
                "Choisissez avec touche(tv, bas/haut) puis touche(tv, ok).",
        )
    }
}

/** Réglages de la télécommande (entrée HDMI du décodeur). */
class RemoteSettingsTool(private val remoteSettings: RemoteSettings) : Tool {
    override val name = "telecommande_regler"
    override val description = "Indique sur quelle entrée HDMI de la TV est branché le décodeur (1 à 4)."
    override val inputSchema = objectSchema(
        properties = mapOf("hdmi_decodeur" to intProperty("Entrée HDMI du décodeur (1 à 4).", 1, 4)),
        required = listOf("hdmi_decodeur"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.config"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (arguments["hdmi_decodeur"] == null) throw ToolArgumentException("Argument « hdmi_decodeur » manquant.")
        remoteSettings.decoderHdmi = arguments.optionalInt("hdmi_decodeur", default = 1, range = 1..4)
        return ToolResult.ok("Décodeur enregistré sur l'entrée HDMI ${remoteSettings.decoderHdmi}.")
    }
}

/**
 * Lance un scénario. Chaque étape repasse par [ToolExecutor] avec la même personne :
 * mêmes autorisations, arrêt d'urgence respecté, et chaque étape inscrite au journal.
 */
class ScenarioRunTool(
    private val scenarios: ScenarioBook,
    private val executor: () -> ToolExecutor,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : Tool {
    override val name = "scenario_lancer"
    override val description =
        "Lance un scénario (plusieurs actions d'un coup) : regarder_tele, mode_film, tout_eteindre, ou un scénario créé. " +
            "Liste complète : maison_appareils."
    override val inputSchema = objectSchema(
        properties = mapOf("nom" to stringProperty("Nom du scénario, ex. mode_film.")),
        required = listOf("nom"),
    )
    override val risk = RiskLevel.LOW

    override fun requiredPermission(arguments: JsonObject) = "remote.scenario.run"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = arguments.requireString("nom")
        val scenario = scenarios.get(name) ?: throw ToolArgumentException(
            "Scénario « $name » inconnu. Scénarios : ${scenarios.all().joinToString { it.name }}.",
        )
        val lines = mutableListOf<String>()
        var failures = 0
        scenario.steps.forEachIndexed { i, step ->
            if (step.tool == ScenarioBook.PAUSE) {
                val seconds = (step.arguments["secondes"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?.coerceIn(1, ScenarioBook.MAX_PAUSE_SECONDS) ?: 1
                sleeper(seconds * 1000L)
                lines += "${i + 1}. ⏸ pause de $seconds s"
                return@forEachIndexed
            }
            val result = executor().call(context.principal, step.tool, step.arguments)
            if (result.isError) failures++
            lines += "${i + 1}. ${if (result.isError) "❌" else "✅"} ${result.text}"
        }
        val head = "Scénario « ${scenario.name} » : " +
            if (failures == 0) "terminé." else "terminé avec $failures étape(s) en échec."
        val text = head + "\n" + lines.joinToString("\n")
        return if (failures == scenario.steps.count { it.tool != ScenarioBook.PAUSE }) ToolResult.error(text) else ToolResult.ok(text)
    }
}

/** Crée (ou remplace) un scénario, retenu sur le téléphone. */
class ScenarioCreateTool(private val scenarios: ScenarioBook) : Tool {
    override val name = "scenario_creer"
    override val description =
        "Crée ou remplace un scénario à la demande du propriétaire (ex. « soirée foot » : allumer tout, chaîne 1, volume 25). " +
            "Chaque étape est un outil de la télécommande avec ses arguments : " +
            "${ScenarioBook.ALLOWED_TOOLS.joinToString()}. Pour attendre que la TV démarre : {\"outil\":\"pause\",\"arguments\":{\"secondes\":8}}."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("nom", stringProperty("Nom court, ex. soiree_foot."))
            put("description", stringProperty("Ce que fait le scénario, en une phrase."))
            putJsonObject("etapes") {
                put("type", "array")
                put("description", "Étapes dans l'ordre (1 à ${ScenarioBook.MAX_STEPS}).")
                put("minItems", 1)
                put("maxItems", ScenarioBook.MAX_STEPS)
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("outil") {
                            put("type", "string")
                            putJsonArray("enum") { ScenarioBook.ALLOWED_TOOLS.forEach { add(JsonPrimitive(it)) } }
                        }
                        putJsonObject("arguments") { put("type", "object") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("outil")) }
                }
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("nom"))
            add(JsonPrimitive("etapes"))
        }
        put("additionalProperties", false)
    }

    // Une automatisation enregistrée : un autre que le propriétaire doit la faire valider.
    override val risk = RiskLevel.SENSITIVE

    override fun requiredPermission(arguments: JsonObject) = "remote.scenario.edit"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = ScenarioBook.normalizeName(arguments.requireString("nom"))
        val steps = ScenarioBook.parseSteps(arguments["etapes"] ?: throw ToolArgumentException("Argument « etapes » manquant."))
        val description = arguments.optionalString("description")?.take(200) ?: "Scénario créé par ${context.principal.displayName}."
        val replaced = scenarios.get(name) != null
        scenarios.save(Scenario(name, description, steps))
        return ToolResult.ok(
            "Scénario « $name » ${if (replaced) "remplacé" else "créé"} (${steps.size} étapes) : " +
                steps.joinToString(" → ") + ". Lancez-le avec scenario_lancer.",
        )
    }
}

/** Supprime un scénario créé (les scénarios fournis ne se suppriment pas, mais peuvent être remplacés). */
class ScenarioDeleteTool(private val scenarios: ScenarioBook) : Tool {
    override val name = "scenario_supprimer"
    override val description = "Supprime un scénario créé. Si un scénario fourni avait été remplacé, il revient."
    override val inputSchema = objectSchema(
        properties = mapOf("nom" to stringProperty("Nom du scénario.")),
        required = listOf("nom"),
    )
    override val risk = RiskLevel.SENSITIVE

    override fun requiredPermission(arguments: JsonObject) = "remote.scenario.edit"

    override fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val name = ScenarioBook.normalizeName(arguments.requireString("nom"))
        if (scenarios.delete(name)) {
            val restored = ScenarioBook.BUILT_IN.any { it.name == name }
            return ToolResult.ok("Scénario « $name » supprimé." + if (restored) " Le scénario fourni du même nom est de retour." else "")
        }
        if (ScenarioBook.BUILT_IN.any { it.name == name }) {
            throw ToolArgumentException("« $name » est un scénario fourni : il ne se supprime pas, mais scenario_creer peut le remplacer.")
        }
        throw ToolArgumentException("Scénario « $name » inconnu.")
    }
}
