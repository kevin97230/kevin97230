package ia.assistant.android.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Icônes de la télécommande, dessinées en code (traits fins, une seule couleur) : aucune police ni image requise. */
enum class Glyph { POWER, UP, DOWN, LEFT, RIGHT, MUTE, HOME, BACK, MENU, SOURCE, PLAY, PAUSE, STOP, FFWD, REWIND, RECORD, SEARCH, EXIT, INFO, PLUS, MINUS, LINK, SETTINGS, GUIDE }

/** Dessine un [Glyph] sur une grille de 24 × 24, mise à l'échelle de la taille demandée. */
class GlyphDrawable(private val glyph: Glyph, color: Int, private val sizePx: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        drawGlyph(canvas)
        canvas.restore()
    }

    private fun line(c: Canvas, vararg p: Float) {
        val path = Path().apply {
            moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) {
                lineTo(p[i], p[i + 1])
                i += 2
            }
        }
        c.drawPath(path, stroke)
    }

    private fun polygon(c: Canvas, vararg p: Float) {
        val path = Path().apply {
            moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) {
                lineTo(p[i], p[i + 1])
                i += 2
            }
            close()
        }
        c.drawPath(path, fill)
    }

    private fun drawGlyph(c: Canvas) {
        when (glyph) {
            Glyph.POWER -> {
                line(c, 12f, 3f, 12f, 11f)
                c.drawArc(RectF(5f, 5f, 19f, 19f), -60f, 300f, false, stroke)
            }
            Glyph.UP -> line(c, 7f, 14.5f, 12f, 9.5f, 17f, 14.5f)
            Glyph.DOWN -> line(c, 7f, 9.5f, 12f, 14.5f, 17f, 9.5f)
            Glyph.LEFT -> line(c, 14.5f, 7f, 9.5f, 12f, 14.5f, 17f)
            Glyph.RIGHT -> line(c, 9.5f, 7f, 14.5f, 12f, 9.5f, 17f)
            Glyph.MUTE -> {
                polygon(c, 4f, 9f, 8f, 9f, 12f, 5f, 12f, 19f, 8f, 15f, 4f, 15f)
                line(c, 16f, 9.5f, 20.5f, 14f)
                line(c, 20.5f, 9.5f, 16f, 14f)
            }
            Glyph.HOME -> {
                line(c, 4f, 11f, 12f, 4f, 20f, 11f)
                line(c, 6.5f, 9.5f, 6.5f, 20f, 17.5f, 20f, 17.5f, 9.5f)
            }
            Glyph.BACK -> {
                line(c, 9f, 6f, 4f, 11f, 9f, 16f)
                line(c, 4f, 11f, 14f, 11f)
                c.drawArc(RectF(9.5f, 11f, 18.5f, 20f), -90f, 180f, false, stroke)
                line(c, 14f, 20f, 9f, 20f)
            }
            Glyph.MENU -> {
                line(c, 5f, 7f, 19f, 7f)
                line(c, 5f, 12f, 19f, 12f)
                line(c, 5f, 17f, 19f, 17f)
            }
            Glyph.SOURCE -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 17f), 2f, 2f, stroke)
                line(c, 8f, 11f, 15f, 11f)
                line(c, 12f, 8f, 15f, 11f, 12f, 14f)
                line(c, 9f, 20f, 15f, 20f)
            }
            Glyph.PLAY -> polygon(c, 8f, 5f, 19f, 12f, 8f, 19f)
            Glyph.PAUSE -> {
                c.drawRect(6.5f, 5f, 10f, 19f, fill)
                c.drawRect(14f, 5f, 17.5f, 19f, fill)
            }
            Glyph.STOP -> c.drawRect(6.5f, 6.5f, 17.5f, 17.5f, fill)
            Glyph.FFWD -> {
                polygon(c, 4f, 6f, 12f, 12f, 4f, 18f)
                polygon(c, 12f, 6f, 20f, 12f, 12f, 18f)
            }
            Glyph.REWIND -> {
                polygon(c, 20f, 6f, 12f, 12f, 20f, 18f)
                polygon(c, 12f, 6f, 4f, 12f, 12f, 18f)
            }
            Glyph.RECORD -> c.drawCircle(12f, 12f, 6f, fill)
            Glyph.SEARCH -> {
                c.drawCircle(10.5f, 10.5f, 6f, stroke)
                line(c, 15f, 15f, 20f, 20f)
            }
            Glyph.EXIT -> {
                line(c, 6.5f, 6.5f, 17.5f, 17.5f)
                line(c, 17.5f, 6.5f, 6.5f, 17.5f)
            }
            Glyph.INFO -> {
                c.drawCircle(12f, 12f, 9f, stroke)
                line(c, 12f, 11f, 12f, 16.5f)
                c.drawCircle(12f, 7.5f, 1.2f, fill)
            }
            Glyph.PLUS -> {
                line(c, 12f, 6f, 12f, 18f)
                line(c, 6f, 12f, 18f, 12f)
            }
            Glyph.MINUS -> line(c, 6f, 12f, 18f, 12f)
            Glyph.LINK -> {
                c.drawRoundRect(RectF(2.5f, 8.5f, 13f, 15.5f), 3.5f, 3.5f, stroke)
                c.drawRoundRect(RectF(11f, 8.5f, 21.5f, 15.5f), 3.5f, 3.5f, stroke)
            }
            Glyph.SETTINGS -> {
                c.drawCircle(12f, 12f, 3.5f, stroke)
                c.drawCircle(12f, 12f, 7f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(c, 12f + 7f * Math.cos(a).toFloat(), 12f + 7f * Math.sin(a).toFloat(), 12f + 9.5f * Math.cos(a).toFloat(), 12f + 9.5f * Math.sin(a).toFloat())
                }
            }
            Glyph.GUIDE -> {
                c.drawRoundRect(RectF(4f, 5f, 20f, 19f), 2f, 2f, stroke)
                line(c, 4f, 10f, 20f, 10f)
                line(c, 10f, 10f, 10f, 19f)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Requis par Drawable")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
