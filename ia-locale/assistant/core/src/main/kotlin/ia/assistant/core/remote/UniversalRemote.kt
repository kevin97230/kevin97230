package ia.assistant.core.remote

import ia.assistant.core.network.SettingsStore
import ia.assistant.core.tools.ToolArgumentException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/** Allumer / éteindre un appareil, en sachant si possible dans quel état il est. */
fun interface PowerControl {
    /** Renvoie une phrase qui dit ce qui a été fait. */
    fun set(on: Boolean): String
}

/** Un appareil de la maison, joignable par un ou plusieurs chemins (Wi-Fi d'abord, infrarouge ensuite). */
class RemoteDevice(
    val id: String,
    val name: String,
    val aliases: Set<String>,
    val routes: List<KeyRoute>,
    private val power: PowerControl,
    /** État lisible (allumé, en veille…), ou null si l'appareil ne sait pas le dire. */
    private val stateReader: () -> String? = { null },
) {
    val keys: Set<String> get() = routes.flatMapTo(linkedSetOf()) { it.keys }

    fun setPower(on: Boolean): String = power.set(on)

    fun state(): String? = runCatching { stateReader() }.getOrNull()

    /**
     * Appuie [repeats] fois sur [key]. Passe par le premier chemin prêt ; s'il ne répond plus en cours
     * de route, termine les appuis restants par le chemin suivant (aucun appui en double).
     * @return le ou les chemins utilisés (« Wi-Fi », « infrarouge »).
     */
    fun press(key: String, repeats: Int = 1, pauseMillis: Long = 150): List<String> {
        if (routes.none { it.supports(key) }) {
            throw ToolArgumentException("Touche « $key » inconnue pour $name. Touches : ${keys.joinToString()}")
        }
        var done = 0
        val used = mutableListOf<String>()
        val problems = mutableListOf<String>()
        for (route in routes) {
            if (done >= repeats) break
            if (!route.supports(key)) {
                problems += "${route.label} : pas de touche « $key »"
                continue
            }
            if (!route.ready) {
                problems += "${route.label} : ${route.notReadyReason}"
                continue
            }
            val before = done
            try {
                while (done < repeats) {
                    if (done > 0) Thread.sleep(pauseMillis)
                    route.press(key)
                    done++
                }
            } catch (e: Exception) {
                problems += "${route.label} : ${e.message ?: e::class.simpleName}"
            }
            if (done > before) used += route.label
        }
        if (done < repeats) {
            val partial = if (done > 0) " ($done appui(s) sur $repeats envoyés)" else ""
            throw IllegalStateException("Impossible d'appuyer sur « $key » pour $name$partial. ${problems.joinToString(" ; ")}.")
        }
        return used
    }
}

/** Les appareils de la maison, retrouvés par leur nom courant (« télé », « box »…). */
class UniversalRemote(val devices: List<RemoteDevice>) {
    fun find(name: String): RemoteDevice? {
        val n = normalize(name)
        return devices.firstOrNull { d -> normalize(d.id) == n || d.aliases.any { normalize(it) == n } }
    }

    fun require(name: String): RemoteDevice = find(name)
        ?: throw ToolArgumentException("Appareil « $name » inconnu. Appareils : ${devices.joinToString { it.id }}.")

    companion object {
        /** Minuscules, sans accents ni espaces : « Télé » = « tele », « France 2 » = « france2 ». */
        fun normalize(text: String): String =
            Normalizer.normalize(text.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("+", "plus")
                .replace(Regex("[^a-z0-9]"), "")
    }
}

/** TV Android : en Wi-Fi on sait allumer *ou* éteindre ; en infrarouge, une seule touche qui bascule. */
class TvPower(private val wifi: KeyRoute, private val ir: KeyRoute) : PowerControl {
    override fun set(on: Boolean): String {
        if (wifi.ready) {
            try {
                wifi.press(if (on) "reveil" else "veille")
                return if (on) "TV allumée (Wi-Fi)." else "TV mise en veille (Wi-Fi)."
            } catch (e: Exception) {
                // La TV appairée ne répond plus en Wi-Fi : elle est en veille profonde, donc éteinte.
                if (!on) return "La TV ne répond pas en Wi-Fi : elle est sans doute déjà éteinte. Rien n'a été envoyé."
                if (ir.ready) {
                    ir.press("allumer")
                    return "TV allumée par l'infrarouge (elle ne répondait pas en Wi-Fi, donc elle était éteinte)."
                }
                throw IllegalStateException("La TV ne répond pas en Wi-Fi et l'infrarouge n'est pas disponible.")
            }
        }
        check(ir.ready) { "Impossible de piloter la TV : Wi-Fi (${wifi.notReadyReason}) ; infrarouge (${ir.notReadyReason})." }
        ir.press("allumer")
        return "Touche marche/arrêt envoyée à la TV par l'infrarouge. Elle bascule : si la TV était déjà " +
            (if (on) "allumée, elle s'est éteinte" else "éteinte, elle s'est allumée") +
            ". Appairer la TV en Wi-Fi (tv_rechercher, tv_appairer) évite ce problème."
    }
}

/** Décodeur Orange : en Wi-Fi il dit s'il est en veille, on n'appuie donc que si c'est utile. */
class DecoderPower(
    private val wifi: KeyRoute,
    private val ir: KeyRoute,
    /** true = veille, false = allumé, null = injoignable. */
    private val standby: () -> Boolean?,
) : PowerControl {
    override fun set(on: Boolean): String {
        when (if (wifi.ready) standby() else null) {
            false -> return if (on) {
                "Décodeur déjà allumé."
            } else {
                wifi.press("allumer")
                "Décodeur mis en veille (Wi-Fi)."
            }
            true -> return if (on) {
                wifi.press("allumer")
                "Décodeur allumé (Wi-Fi)."
            } else {
                "Décodeur déjà en veille."
            }
            null -> Unit
        }
        if (wifi.ready) {
            // Adresse connue mais aucune réponse : veille profonde (il quitte le réseau).
            if (!on) return "Le décodeur ne répond pas en Wi-Fi : il est déjà en veille profonde. Rien n'a été envoyé."
            check(ir.ready) { "Décodeur injoignable en Wi-Fi (veille profonde) et infrarouge indisponible : allumez-le avec sa télécommande." }
            ir.press("allumer")
            return "Décodeur allumé par l'infrarouge (il était en veille profonde)."
        }
        check(ir.ready) { "Impossible de piloter le décodeur : Wi-Fi (${wifi.notReadyReason}) ; infrarouge (${ir.notReadyReason})." }
        ir.press("allumer")
        return "Touche marche/arrêt envoyée au décodeur par l'infrarouge. Elle bascule : si le décodeur était déjà " +
            (if (on) "allumé, il s'est éteint" else "éteint, il s'est allumé") +
            ". Trouver le décodeur sur le Wi-Fi (decodeur_rechercher) évite ce problème."
    }
}

/** Les chaînes connues par leur nom. Celles de base, plus celles apprises (enregistrées sur le téléphone). */
class ChannelBook(private val settings: SettingsStore) {
    fun all(): Map<String, Int> = DEFAULTS + custom()

    fun numberOf(name: String): Int? {
        val n = UniversalRemote.normalize(name)
        return all().entries.firstOrNull { UniversalRemote.normalize(it.key) == n }?.value
    }

    fun save(name: String, number: Int) {
        val updated = custom().filterKeys { UniversalRemote.normalize(it) != UniversalRemote.normalize(name) } + (name.trim() to number)
        settings.set(KEY, JsonObject(updated.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun custom(): Map<String, Int> = runCatching {
        Json.parseToJsonElement(settings.get(KEY) ?: return emptyMap()).jsonObject
            .mapValues { it.value.jsonPrimitive.content.toInt() }
    }.getOrDefault(emptyMap())

    companion object {
        private const val KEY = "telecommande.chaines"

        /** Numéros stables de la TNT, identiques sur la TV d'Orange. Les autres s'apprennent avec chaine_enregistrer. */
        val DEFAULTS: Map<String, Int> = linkedMapOf(
            "TF1" to 1,
            "France 2" to 2,
            "France 3" to 3,
            "France 5" to 5,
            "M6" to 6,
            "Arte" to 7,
            "W9" to 9,
            "TMC" to 10,
            "TFX" to 11,
        )
    }
}
