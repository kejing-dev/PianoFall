package io.embodit.pianofall.ui.piano.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Bitmaps for the textured plates (ECS has no 3D text, design `container.notes`): the countdown digits on the
 * curtain, the key-count labels of the standard frames, the "F↑" orientation test. Drawn once at build.
 */
internal object Plates {
    /** [text] centred on a transparent [w] x [h] bitmap, [color] ARGB, with a soft dark halo for passthrough. */
    fun text(text: String, w: Int, h: Int, color: Int, sizeFrac: Float = 0.72f): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = h * sizeFrac
        }
        val y = h / 2f - (p.descent() + p.ascent()) / 2f
        p.color = 0x99000000.toInt()
        p.setShadowLayer(h * 0.06f, 0f, 0f, 0xCC000000.toInt())
        c.drawText(text, w / 2f, y, p)
        p.clearShadowLayer()
        p.color = color
        c.drawText(text, w / 2f, y, p)
        return bmp
    }
}
