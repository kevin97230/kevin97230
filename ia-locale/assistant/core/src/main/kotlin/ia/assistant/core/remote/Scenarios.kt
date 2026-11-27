package ia.assistant.core.remote

import ia.assistant.core.network.SettingsStore
import ia.assistant.core.tools.ToolArgumentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Une étape : un outil de la télécommande et ses arguments, ou une pause (`pause`, `secondes`). */
data class ScenarioStep(val tool: String, val arguments: JsonObject) {
    fun toJson(): JsonObject = buildJsonObject {
        put("outil", tool)
        put("arguments", arguments)
    }

    override fun toString(): String =
        tool + if (arguments.isEmpty()) "" else arguments.entries.joinToString(", ", " (", ")") { (k, v) -> "$k=${v.jsonPrimitiveOrSelf()}" }
}

private fun JsonElement.jsonPrimitiveOrSelf(): String = (this as? JsonPrimitive)?.content ?: toString()

/** Une suite d'actions lancée par une seule phrase (« mode film »). */
data class Scenario(val name: String, val description: String, val steps: List<ScenarioStep>, val builtIn: Boolean = false)

/**
 * Les scénarios : ceux fournis avec l'appli, et ceux que Claude crée à la demande du propriétaire
 * (enregistrés sur le téléphone). Un scénario créé avec le nom d'un scénario fourni le remplace.
 */
class ScenarioBook(private val settings: SettingsStore) {

    fun all(): List<Scenario> {
        val custom = custom()
        return BUILT_IN.filter { it.name !in custom } + custom.values
    }

    fun get(name: String): Scenario? = all().firstOrNull { it.name == normalizeName(name) }

    fun save(scenario: Scenario) {
        validate(scenario)
        write(custom() + (scenario.name to scenario.copy(builtIn = false)))
    }

    /** Supprime un scénario créé. @return false s'il n'existait pas. */
    fun delete(name: String): Boolean {
        val custom = custom()
        val key = normalizeName(name)
        if (key !in custom) return false
        write(custom - key)
        return true
    }

    private fun custom(): Map<String, Scenario> = runCatching {
        Json.parseToJsonElement(settings.get(KEY) ?: return emptyMap()).jsonObject.mapValues { (name, value) ->
            val o = value.jsonObject
            Scenario(
                name = name,
                description = o["description"]?.jsonPrimitive?.content.orEmpty(),
                steps = parseSteps(o.getValue("etapes")),
            )
        }
    }.getOrDefault(emptyMap())

    private fun write(scenarios: Map<String, Scenario>) {
        val json = buildJsonObject {
            scenarios.forEach { (name, s) ->
                put(
                    name,
                    buildJsonObject {
                        put("description", s.description)
                        put("etapes", buildJsonArray { s.steps.forEach { add(it.toJson()) } })
                    },
                )
            }
        }
        settings.set(KEY, json.toString())
    }

    companion object {
        private const val KEY = "telecommande.scenarios"
        const val PAUSE = "pause"
        const val MAX_STEPS = 30
        const val MAX_PAUSE_SECONDS = 30

        /** Outils qu'un scénario a le droit d'enchaîner : uniquement la télécommande (jamais un autre scénario). */
        val ALLOWED_TOOLS = setOf("touche", "allumer", "eteindre", "chaine", "volume", "source", "tv_ouvrir_app", PAUSE)

        /** « Mode film » → `mode_film`. */
        fun normalizeName(name: String): String =
            java.text.Normalizer.normalize(name.trim().lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

        fun parseSteps(element: JsonElement): List<ScenarioStep> {
            val array = element as? JsonArray ?: throw ToolArgumentException("« etapes » doit être une liste.")
            return array.mapIndexed { i, item ->
                val o = item as? JsonObject ?: throw ToolArgumentException("Étape ${i + 1} : objet attendu.")
                val tool = (o["outil"] as? JsonPrimitive)?.content
                    ?: throw ToolArgumentException("Étape ${i + 1} : champ « outil » manquant.")
                val arguments = when (val a = o["arguments"]) {
                    null -> JsonObject(emptyMap())
                    is JsonObject -> a
                    else -> throw ToolArgumentException("Étape ${i + 1} : « arguments » doit être un objet.")
                }
                ScenarioStep(tool, arguments)
            }
        }

        fun validate(scenario: Scenario) {
            if (!scenario.name.matches(Regex("[a-z0-9_]{1,40}"))) {
                throw ToolArgumentException("Nom de scénario invalide « ${scenario.name} ».")
            }
            if (scenario.steps.isEmpty() || scenario.steps.size > MAX_STEPS) {
                throw ToolArgumentException("Un scénario a de 1 à $MAX_STEPS étapes.")
            }
            scenario.steps.forEachIndexed { i, step ->
                if (step.tool !in ALLOWED_TOOLS) {
                    throw ToolArgumentException("Étape ${i + 1} : outil « ${step.tool} » non autorisé. Outils possibles : ${ALLOWED_TOOLS.joinToString()}.")
                }
                if (step.tool == PAUSE) {
                    val s = (step.arguments["secondes"] as? JsonPrimitive)?.intOrNull
                    if (s == null || s !in 1..MAX_PAUSE_SECONDS) {
                        throw ToolArgumentException("Étape ${i + 1} : une pause a besoin de « secondes » (1 à $MAX_PAUSE_SECONDS).")
                    }
                }
            }
        }

        private fun step(tool: String, vararg args: Pair<String, Any>) = ScenarioStep(
            tool,
            JsonObject(args.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
        )

        /** La TV met quelques secondes à démarrer avant d'accepter un changement de source ou une appli. */
        private const val TV_BOOT_SECONDS = 8

        val BUILT_IN: List<Scenario> = listOf(
            Scenario(
                "regarder_tele",
                "Allume la TV et le décodeur, puis met la TV sur l'entrée HDMI du décodeur.",
                listOf(
                    step("allumer", "appareil" to "tv"),
                    step("allumer", "appareil" to "decodeur"),
                    step(PAUSE, "secondes" to TV_BOOT_SECONDS),
                    step("source", "entree" to "decodeur"),
                ),
                builtIn = true,
            ),
            Scenario(
                "mode_film",
                "Allume la TV, met le décodeur en veille, ouvre Netflix et règle le volume à 20.",
                listOf(
                    step("allumer", "appareil" to "tv"),
                    step("eteindre", "appareil" to "decodeur"),
                    step(PAUSE, "secondes" to TV_BOOT_SECONDS),
                    step("tv_ouvrir_app", "app" to "netflix"),
                    step("volume", "action" to "regler", "valeur" to 20),
                ),
                builtIn = true,
            ),
            Scenario(
                "tout_eteindre",
                "Éteint la TV et met le décodeur en veille.",
                listOf(
                    step("eteindre", "appareil" to "tv"),
                    step("eteindre", "appareil" to "decodeur"),
                ),
                builtIn = true,
            ),
        )
    }
}
