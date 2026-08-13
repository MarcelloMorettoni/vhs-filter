package com.retro.vhs.vhs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.retro.vhs.gl.GlUtil

data class OsdState(
    val style: OsdStyle,
    val color: Int,
    val recording: Boolean,
    val elapsedSec: Int,
    val dateLine: String,
    val timeLine: String,
    val speed: String,
    val blink: Boolean,
    val battery: Float
)

/**
 * Camcorder / VCR on-screen display. Drawn at a quarter of the working resolution and
 * sampled with nearest neighbour so the glyphs come out chunky, the way a real character
 * generator looked, and then fed through the tape stage so it smears and jitters too.
 */
class OsdRenderer(private val width: Int = 320, private val height: Int = 240) {

    private val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private var textureId = 0
    private var lastKey: String? = null

    private val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textSize = 12.5f
        letterSpacing = 0.05f
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textSize = 12.5f
        letterSpacing = 0.05f
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
        color = Color.argb(190, 0, 0, 0)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Kept inside the title-safe area so a TV's overscan never eats the stamp. */
    private val margin = 24f

    fun texture(): Int {
        if (textureId == 0) {
            textureId = GlUtil.createTexture(width, height, GLES20.GL_NEAREST)
        }
        return textureId
    }

    /** Redraws and re-uploads only when something visible actually changed. */
    fun update(state: OsdState) {
        val id = texture()
        val key = state.toString()
        if (key == lastKey) return
        lastKey = key

        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        if (state.style != OsdStyle.NONE) {
            draw(state)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun draw(s: OsdState) {
        text.color = s.color
        when (s.style) {
            OsdStyle.CAMCORDER -> {
                drawStatus(s)
                battery(s.battery)
                right(s.timeLine, height - margin - 16f, s.color)
                right(s.dateLine, height - margin, s.color)
            }

            OsdStyle.DATE_ONLY -> {
                right(s.timeLine, height - margin - 16f, s.color)
                right(s.dateLine, height - margin, s.color)
            }

            OsdStyle.PLAYER -> {
                transport(s)
                right(s.speed, margin + 11f, s.color)
                left(counter(s.elapsedSec), height - margin, s.color)
            }

            OsdStyle.TRACKING -> {
                transport(s)
                right(s.speed, margin + 11f, s.color)
                left(counter(s.elapsedSec), height - margin, s.color)
                if (s.blink) center("T R A C K I N G", height - margin - 20f, s.color)
            }

            OsdStyle.NEWS -> {
                if (s.recording && s.blink) {
                    dot(margin + 5f, margin + 7f, 0xFFFF3B20.toInt())
                }
                val tc = timecode(s.elapsedSec)
                fill.color = Color.argb(150, 0, 0, 0)
                val w = text.measureText(tc)
                canvas.drawRect(
                    RectF(margin - 5f, height - margin - 13f, margin + w + 5f, height - margin + 5f),
                    fill
                )
                left(tc, height - margin, s.color)
                if (s.recording) left("   REC", margin + 11f, 0xFFFF4030.toInt())
            }

            OsdStyle.SECURITY -> {
                left("CAM 01", margin + 11f, s.color)
                right("${s.dateLine}  ${s.timeLine}", margin + 11f, s.color)
                left(if (s.recording) "REC ${s.speed}" else s.speed, height - margin, s.color)
            }

            OsdStyle.NONE -> Unit
        }
    }

    /** Top left recording state, exactly as a palmcorder viewfinder showed it. */
    private fun drawStatus(s: OsdState) {
        if (s.recording) {
            if (s.blink) dot(margin + 5f, margin + 7f, 0xFFFF3B20.toInt())
            left("   REC  ${counter(s.elapsedSec)}", margin + 11f, s.color)
        } else {
            left("PAUSE", margin + 11f, s.color)
        }
        right(s.speed, margin + 11f, s.color)
    }

    /** Deck transport indicator: a drawn glyph, so no font fallback can turn it into emoji. */
    private fun transport(s: OsdState) {
        if (s.recording) {
            if (s.blink) dot(margin + 5f, margin + 7f, 0xFFFF3B20.toInt())
            left("   REC", margin + 11f, s.color)
        } else {
            triangle(margin, margin + 7f, s.color)
            left("   PLAY", margin + 11f, s.color)
        }
    }

    private fun dot(cx: Float, cy: Float, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = color
        canvas.drawCircle(cx, cy, 4f, fill)
    }

    private fun triangle(x: Float, cy: Float, color: Int) {
        fill.style = Paint.Style.FILL
        fill.color = color
        val path = android.graphics.Path().apply {
            moveTo(x, cy - 5f)
            lineTo(x + 8f, cy)
            lineTo(x, cy + 5f)
            close()
        }
        canvas.drawPath(path, fill)
    }

    private fun counter(sec: Int): String =
        "%d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60)

    private fun timecode(sec: Int): String =
        "%02d:%02d:%02d:%02d".format(sec / 3600, (sec / 60) % 60, sec % 60, (sec * 7) % 30)

    private fun left(str: String, baseline: Float, color: Int) {
        text.color = color
        canvas.drawText(str, margin, baseline, outline)
        canvas.drawText(str, margin, baseline, text)
    }

    private fun right(str: String, baseline: Float, color: Int) {
        text.color = color
        val x = width - margin - text.measureText(str)
        canvas.drawText(str, x, baseline, outline)
        canvas.drawText(str, x, baseline, text)
    }

    private fun center(str: String, baseline: Float, color: Int) {
        text.color = color
        val x = (width - text.measureText(str)) / 2f
        canvas.drawText(str, x, baseline, outline)
        canvas.drawText(str, x, baseline, text)
    }

    private fun battery(level: Float) {
        val w = 20f
        val h = 9f
        val x = width - margin - w
        val y = margin + 18f
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 2f
        fill.color = Color.argb(190, 0, 0, 0)
        canvas.drawRect(x - 1f, y - 1f, x + w + 1f, y + h + 1f, fill)
        fill.color = Color.WHITE
        canvas.drawRect(x, y, x + w, y + h, fill)
        fill.style = Paint.Style.FILL
        canvas.drawRect(x + w, y + 2.5f, x + w + 2.5f, y + h - 2.5f, fill)
        canvas.drawRect(x + 2f, y + 2f, x + 2f + (w - 4f) * level.coerceIn(0f, 1f), y + h - 2f, fill)
    }

    fun release() {
        GlUtil.deleteTexture(textureId)
        textureId = 0
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}
