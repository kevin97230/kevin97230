package ia.assistant.android.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Couleurs de l'application (thème sombre). */
object Palette {
    const val BACKGROUND = 0xFF0E1014.toInt()
    const val CARD = 0xFF181B22.toInt()
    const val SURFACE = 0xFF232733.toInt()
    const val SURFACE_PRESSED = 0xFF2E3342.toInt()
    const val ACCENT = 0xFF7B8CFF.toInt()
    const val ACCENT_SOFT = 0xFF2A3157.toInt()
    const val DANGER = 0xFFFF5A5F.toInt()
    const val DANGER_SOFT = 0xFF3D1E22.toInt()
    const val SUCCESS = 0xFF3DDC97.toInt()
    const val WARNING = 0xFFFFB547.toInt()
    const val TEXT = 0xFFEDEFF3.toInt()
    const val MUTED = 0xFF8D96A8.toInt()
    const val RIPPLE = 0x33FFFFFF
}

enum class ButtonStyle(val background: Int, val text: Int) {
    PRIMARY(Palette.ACCENT, 0xFF0E1014.toInt()),
    SECONDARY(Palette.SURFACE, Palette.TEXT),
    SOFT(Palette.ACCENT_SOFT, Palette.TEXT),
    DANGER(Palette.DANGER, Palette.TEXT),
    DANGER_SOFT(Palette.DANGER_SOFT, Palette.DANGER),
}

/** Petite boîte à outils pour construire l'interface sans bibliothèque externe. */
class Ui(val context: Context) {
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        strokeColor?.let { setStroke(dp(1), it) }
    }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun ripple(content: Drawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), content, null)

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Carte arrondie qui regroupe une fonction (pont, TV, décodeur…). */
    fun card(title: String, subtitle: String? = null): LinearLayout = vertical().apply {
        background = rounded(Palette.CARD, 24)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(14) }
        addView(text(title, Palette.TEXT, 18f, bold = true))
        subtitle?.let { addView(text(it, Palette.MUTED, 13f).apply { setPadding(0, dp(2), 0, dp(12)) }) }
            ?: addView(View(context), LinearLayout.LayoutParams(1, dp(12)))
    }

    fun text(value: CharSequence, color: Int = Palette.MUTED, sizeSp: Float = 14f, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            setTextColor(color)
            textSize = sizeSp
            if (bold) typeface = medium
            setLineSpacing(0f, 1.1f)
        }

    /** Bouton arrondi (un TextView stylé, pour garder la main sur l'apparence). */
    fun button(label: String, style: ButtonStyle = ButtonStyle.SECONDARY, heightDp: Int = 50, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(style.text)
            textSize = 15f
            typeface = medium
            minHeight = dp(heightDp)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = ripple(rounded(style.background, 16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    /** Bouton rond (croix directionnelle, marche/arrêt). */
    fun roundButton(label: String, color: Int, textColor: Int = Palette.TEXT, sizeDp: Int = 56, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(textColor)
            textSize = 18f
            typeface = medium
            background = ripple(oval(color))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    /** Pastille d'état : « ● Pont actif ». */
    fun chip(label: String, dotColor: Int): TextView = TextView(context).apply {
        setChip(label, dotColor)
        textSize = 12.5f
        setTextColor(Palette.TEXT)
        background = rounded(Palette.SURFACE, 50)
        setPadding(dp(12), dp(7), dp(12), dp(7))
    }

    /** Une rangée de vues qui se partagent la largeur. */
    fun row(views: List<View>, gapDp: Int = 8): LinearLayout = horizontal().apply {
        views.forEachIndexed { i, v ->
            addView(
                v,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(gapDp)
                },
            )
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    /** Case vide qui garde l'alignement quand une touche n'existe pas. */
    fun spacer(): View = View(context).apply { visibility = View.INVISIBLE }

    /** Bouton icône (rond ou arrondi). Désactivé et estompé si [enabled] est faux. */
    fun iconButton(
        glyph: Glyph,
        description: String,
        backgroundColor: Int = Palette.SURFACE,
        iconColor: Int = Palette.TEXT,
        round: Boolean = false,
        iconDp: Int = 24,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ): ImageView = ImageView(context).apply {
        setImageDrawable(GlyphDrawable(glyph, iconColor, dp(iconDp)))
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        background = ripple(if (round) oval(backgroundColor) else rounded(backgroundColor, 16))
        minimumHeight = dp(50)
        setEnabledLook(enabled)
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }

    /** Sélecteur à segments (onglets, Wi-Fi / Infrarouge). */
    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val bar = horizontal().apply {
            background = rounded(Palette.SURFACE, 50)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = labels.map { label ->
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14.5f
                typeface = medium
                minHeight = dp(42)
            }
        }
        fun select(index: Int) {
            items.forEachIndexed { i, item ->
                val on = i == index
                item.setTextColor(if (on) Palette.BACKGROUND else Palette.MUTED)
                item.background = if (on) rounded(Palette.ACCENT, 50) else null
            }
        }
        items.forEachIndexed { i, item ->
            item.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                select(i)
                onSelect(i)
            }
            bar.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        select(selected)
        return bar
    }
}

/** Touche absente pour cet appareil : visible (la grille reste régulière) mais estompée et inactive. */
fun View.setEnabledLook(enabled: Boolean) {
    isEnabled = enabled
    isClickable = enabled
    alpha = if (enabled) 1f else 0.25f
}

fun TextView.setChip(label: String, dotColor: Int) {
    text = SpannableString("●  $label").apply {
        setSpan(ForegroundColorSpan(dotColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
