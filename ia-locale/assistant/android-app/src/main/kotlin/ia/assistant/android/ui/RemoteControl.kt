package ia.assistant.android.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Dessine une télécommande à partir des touches disponibles d'un appareil :
 * marche/source/accueil, volume et chaînes de part et d'autre de la croix directionnelle,
 * navigation, lecture, applications, clavier numérique, puis les autres touches.
 */
fun Ui.remote(keys: Collection<String>, send: (String) -> Unit): LinearLayout {
    val available = keys.toSet()
    val used = mutableSetOf<String>()
    val root = vertical()

    fun key(name: String, label: String, style: ButtonStyle = ButtonStyle.SECONDARY): View =
        if (name in available) {
            used += name
            button(label, style) { send(name) }
        } else {
            spacer()
        }

    fun addRow(vararg pairs: Pair<String, String>, style: ButtonStyle = ButtonStyle.SECONDARY) {
        if (pairs.none { it.first in available }) return
        root.addView(row(pairs.map { (name, label) -> key(name, label, style) }))
    }

    // Marche, source, accueil.
    addRow("allumer" to "⏻  Marche", "source" to "Source", "accueil" to "Accueil")

    // Volume | croix | chaînes.
    val hasMiddle = listOf("volume_plus", "haut", "chaine_suivante", "ok").any { it in available }
    if (hasMiddle) {
        val middle = horizontal().apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14); bottomMargin = dp(6) }
        }
        fun rocker(top: Pair<String, String>, mid: Pair<String, String>, bottom: Pair<String, String>) = vertical().apply {
            background = rounded(Palette.SURFACE, 28)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            listOf(top, mid, bottom).forEach { (name, label) ->
                val v = if (name in available) {
                    used += name
                    button(label, ButtonStyle.SECONDARY, heightDp = 52) { send(name) }
                } else {
                    spacer()
                }
                addView(v, LinearLayout.LayoutParams(dp(68), dp(52)))
            }
        }
        middle.addView(rocker("volume_plus" to "Vol +", "muet" to "🔇", "volume_moins" to "Vol −"))

        val dpad = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = oval(Palette.SURFACE)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun arrow(name: String, label: String): View =
            if (name in available) {
                used += name
                roundButton(label, Palette.SURFACE, sizeDp = 48) { send(name) }
            } else {
                spacer().apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) }
            }
        dpad.addView(arrow("haut", "▲"))
        dpad.addView(horizontal().apply {
            gravity = Gravity.CENTER
            addView(arrow("gauche", "◀"))
            addView(
                if ("ok" in available) {
                    used += "ok"
                    roundButton("OK", Palette.ACCENT, textColor = Palette.BACKGROUND, sizeDp = 62) { send("ok") }
                } else {
                    spacer().apply { layoutParams = LinearLayout.LayoutParams(dp(62), dp(62)) }
                },
            )
            addView(arrow("droite", "▶"))
        })
        dpad.addView(arrow("bas", "▼"))
        middle.addView(dpad, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
        })

        middle.addView(rocker("chaine_suivante" to "Ch +", "infos" to "Infos", "chaine_precedente" to "Ch −"))
        root.addView(middle)
    }

    // Navigation.
    addRow("retour" to "↩  Retour", "menu" to "Menu", "quitter" to "Quitter")
    addRow("guide" to "Guide", "applications" to "Applis", "tv" to "TV")

    // Lecture.
    addRow("retour_rapide" to "⏪", "lecture_pause" to "⏯", "avance_rapide" to "⏩", "enregistrer" to "⏺")
    addRow("lecture" to "▶  Lecture", "pause" to "⏸  Pause", "stop" to "⏹  Stop")

    // Applications.
    addRow("netflix" to "Netflix", "youtube" to "YouTube", "prime_video" to "Prime Video", style = ButtonStyle.SOFT)
    addRow("vod" to "VOD", style = ButtonStyle.SOFT)

    // Clavier numérique, replié par défaut.
    val digits = (1..9).map { "chiffre_$it" } + "chiffre_0"
    if (digits.any { it in available }) {
        val pad = vertical().apply { visibility = View.GONE }
        digits.chunked(3).forEach { chunk ->
            val cells = chunk.map { name -> key(name, name.removePrefix("chiffre_")) }
            val padded = if (cells.size == 1) listOf(spacer(), cells[0], spacer()) else cells
            pad.addView(row(padded))
        }
        val toggle = button("Clavier numérique  ▾", ButtonStyle.SECONDARY, heightDp = 44) {}
        toggle.setOnClickListener {
            val open = pad.visibility != View.VISIBLE
            pad.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Clavier numérique  ▴" else "Clavier numérique  ▾"
        }
        root.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
        root.addView(pad)
    }

    // Toutes les autres touches définies pour l'appareil.
    val others = available.filter { it !in used }.sorted()
    if (others.isNotEmpty()) {
        root.addView(text("Autres touches", Palette.MUTED, 12f).apply { setPadding(0, dp(14), 0, 0) })
        others.chunked(3).forEach { chunk ->
            val cells = chunk.map { name -> key(name, name.replace('_', ' ')) }
            root.addView(row(cells + List(3 - cells.size) { spacer() }))
        }
    }
    return root
}
