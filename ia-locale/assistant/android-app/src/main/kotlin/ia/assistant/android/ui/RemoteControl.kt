package ia.assistant.android.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Libellés lisibles des touches qui n'ont pas de place dédiée. */
private val LABELS = mapOf(
    "guide" to "Guide",
    "infos" to "Infos",
    "parametres" to "Paramètres",
    "langue" to "Langue",
    "sous_titres" to "Sous-titres",
    "texte" to "Télétexte",
    "enregistrer" to "Enregistrer",
    "vod" to "VOD",
    "applications" to "Applis",
    "tv" to "TV",
    "lecture_pause" to "Lecture/Pause",
)

private fun label(key: String) = LABELS[key] ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

/**
 * Télécommande, disposée comme une vraie (modèle : télécommande Panasonic) :
 * marche · source · menu ; volume | muet + infos | chaînes ; grande croix ronde avec OK ;
 * retour · accueil · quitter ; applications ; lecture ; touches de couleur ; autres touches ; pavé numérique.
 * Les touches absentes restent visibles mais estompées, pour une grille toujours régulière.
 */
fun Ui.remote(keys: Collection<String>, send: (String) -> Unit): LinearLayout {
    val available = keys.toSet()
    val used = mutableSetOf<String>()
    val root = vertical()

    fun has(key: String) = key in available
    fun press(key: String): () -> Unit = { send(key) }

    fun icon(key: String, glyph: Glyph, description: String, round: Boolean = false, background: Int = Palette.SURFACE, iconColor: Int = Palette.TEXT): View {
        used += key
        return iconButton(glyph, description, background, iconColor, round = round, enabled = has(key), onClick = press(key))
    }

    fun textKey(key: String, text: String, style: ButtonStyle = ButtonStyle.SECONDARY): View {
        used += key
        return button(text, style, onClick = press(key)).apply { setEnabledLook(has(key)) }
    }

    fun fullRow(views: List<View>, topDp: Int = 10) = row(views).apply {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(topDp)
    }

    // 1. Marche · Source · Menu
    root.addView(fullRow(listOf(
        icon("allumer", Glyph.POWER, "Marche / arrêt", background = Palette.DANGER_SOFT, iconColor = Palette.DANGER),
        icon("source", Glyph.SOURCE, "Source"),
        icon("menu", Glyph.MENU, "Menu"),
    ), topDp = 0))

    // 2. Volume | muet + infos | chaînes
    fun rocker(plusKey: String, minusKey: String, caption: String): LinearLayout = vertical().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        background = rounded(Palette.SURFACE, 36)
        used += plusKey
        used += minusKey
        addView(iconButton(Glyph.PLUS, "$caption plus", Palette.SURFACE, enabled = has(plusKey), onClick = press(plusKey)).apply {
            background = ripple(rounded(Palette.SURFACE, 36))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
        addView(text(caption, Palette.MUTED, 12f, bold = true).apply { gravity = Gravity.CENTER })
        addView(iconButton(Glyph.MINUS, "$caption moins", Palette.SURFACE, enabled = has(minusKey), onClick = press(minusKey)).apply {
            background = ripple(rounded(Palette.SURFACE, 36))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)))
    }
    val middle = horizontal().apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) }
    }
    middle.addView(rocker("volume_plus", "volume_moins", "VOL"), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
    middle.addView(vertical().apply {
        gravity = Gravity.CENTER
        addView(icon("muet", Glyph.MUTE, "Muet", round = true), LinearLayout.LayoutParams(dp(58), dp(58)))
        addView(View(context), LinearLayout.LayoutParams(1, dp(14)))
        addView(icon("infos", Glyph.INFO, "Infos", round = true), LinearLayout.LayoutParams(dp(58), dp(58)))
    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    middle.addView(rocker("chaine_suivante", "chaine_precedente", "CH"), LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
    root.addView(middle)

    // 3. Grande croix ronde avec OK au centre
    val padSize = dp(232)
    val dpad = FrameLayout(context).apply {
        background = rounded(Palette.SURFACE, 999).apply { setStroke(dp(1), Palette.SURFACE_PRESSED) }
    }
    fun arrow(key: String, glyph: Glyph, description: String, gravity: Int) {
        used += key
        dpad.addView(
            iconButton(glyph, description, Palette.SURFACE, round = true, iconDp = 30, enabled = has(key), onClick = press(key)),
            FrameLayout.LayoutParams(dp(70), dp(70), gravity).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) },
        )
    }
    arrow("haut", Glyph.UP, "Haut", Gravity.TOP or Gravity.CENTER_HORIZONTAL)
    arrow("bas", Glyph.DOWN, "Bas", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    arrow("gauche", Glyph.LEFT, "Gauche", Gravity.START or Gravity.CENTER_VERTICAL)
    arrow("droite", Glyph.RIGHT, "Droite", Gravity.END or Gravity.CENTER_VERTICAL)
    used += "ok"
    dpad.addView(
        roundButton("OK", Palette.ACCENT, textColor = Palette.BACKGROUND, sizeDp = 84) { send("ok") }.apply { setEnabledLook(has("ok")) },
        FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER),
    )
    root.addView(horizontal().apply {
        gravity = Gravity.CENTER
        addView(dpad, LinearLayout.LayoutParams(padSize, padSize))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(18); bottomMargin = dp(8) }
    })

    // 4. Retour · Accueil · Quitter
    root.addView(fullRow(listOf(
        icon("retour", Glyph.BACK, "Retour"),
        icon("accueil", Glyph.HOME, "Accueil"),
        icon("quitter", Glyph.EXIT, "Quitter"),
    )))

    // 5. Applications
    val apps = listOf("netflix" to "Netflix", "youtube" to "YouTube", "prime_video" to "Prime Video")
    if (apps.any { has(it.first) }) {
        root.addView(fullRow(apps.map { (key, name) -> textKey(key, name, ButtonStyle.SOFT) }, topDp = 14))
    }

    // 6. Lecture
    val playKey = if (has("lecture")) "lecture" else "lecture_pause"
    val media = listOf("retour_rapide", playKey, "pause", "stop", "avance_rapide")
    if (media.any(::has)) {
        root.addView(fullRow(listOf(
            icon("retour_rapide", Glyph.REWIND, "Retour rapide"),
            icon(playKey, Glyph.PLAY, "Lecture"),
            icon("pause", Glyph.PAUSE, "Pause"),
            icon("stop", Glyph.STOP, "Stop"),
            icon("avance_rapide", Glyph.FFWD, "Avance rapide"),
        ), topDp = 14))
    }

    // 7. Touches de couleur, comme sur la vraie télécommande
    val colors = listOf("rouge" to 0xFFE5484D.toInt(), "vert" to 0xFF30A46C.toInt(), "jaune" to 0xFFF5D90A.toInt(), "bleu" to 0xFF3E63DD.toInt())
    if (colors.any { has(it.first) }) {
        root.addView(fullRow(colors.map { (key, color) ->
            used += key
            View(context).apply {
                contentDescription = key
                minimumHeight = dp(30)
                background = ripple(rounded(color, 50))
                setEnabledLook(has(key))
                setOnClickListener { send(key) }
            }
        }, topDp = 14))
    }

    // 8. Autres touches, avec de vrais libellés
    val others = available.filter { it !in used && !it.startsWith("chiffre_") }
        .sortedBy { LABELS.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }
    others.chunked(3).forEachIndexed { i, chunk ->
        val cells = chunk.map { key -> textKey(key, label(key)) }
        // Une dernière rangée incomplète s'étire sur toute la largeur plutôt que de laisser un trou.
        root.addView(fullRow(cells, topDp = if (i == 0) 14 else 8))
    }

    // 9. Pavé numérique, replié par défaut
    val digits = (1..9).map { "chiffre_$it" } + "chiffre_0"
    if (digits.any(::has)) {
        val pad = vertical().apply { visibility = View.GONE }
        digits.take(9).chunked(3).forEach { chunk -> pad.addView(row(chunk.map { textKey(it, it.removePrefix("chiffre_")) })) }
        pad.addView(row(listOf(spacer(), textKey("chiffre_0", "0"), spacer())))
        val toggle = button("Pavé numérique", ButtonStyle.SECONDARY, heightDp = 44) {}
        toggle.setOnClickListener {
            val open = pad.visibility != View.VISIBLE
            pad.visibility = if (open) View.VISIBLE else View.GONE
            toggle.text = if (open) "Masquer le pavé numérique" else "Pavé numérique"
        }
        root.addView(fullRow(listOf(toggle), topDp = 14))
        root.addView(pad)
    }
    return root
}
