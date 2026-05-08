package com.musicplayer.presentation.player

import android.graphics.*
import android.util.LruCache
import androidx.core.graphics.createBitmap
import java.util.Locale
import com.musicplayer.domain.model.Song
import kotlin.math.min

object AlbumArtGenerator {

    // Cache keyed by "songId_size". Max 1/8 of available heap.
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun generate(song: Song, size: Int): Bitmap {
        val key = "${song.id}_$size"
        cache.get(key)?.let { return it }

        // RGB_565 for thumbnails (≤128px) saves 50% memory; ARGB_8888 for large art
        val config = if (size <= 128) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val bitmap = createBitmap(size, size, config)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val variant = ((song.id % 6) + 6).toInt() % 6
        val hue = song.id.toFloat() * 37f % 360f
        val sat = 0.5f + (song.id % 3) * 0.1f

        when (variant) {
            0 -> drawRadialGlow(canvas, paint, size, hue, sat, song.artist)
            1 -> drawConcentricCircles(canvas, paint, size, hue, sat)
            2 -> drawHorizontalBands(canvas, paint, size, hue, sat)
            3 -> drawBigInitials(canvas, paint, size, hue, sat, song.displayName)
            4 -> drawGridDots(canvas, paint, size, hue, sat, song.album)
            else -> drawHalfAndHalf(canvas, paint, size, hue, sat, song.id)
        }

        cache.put(key, bitmap)
        return bitmap
    }

    private fun hsvToColor(h: Float, s: Float, l: Float): Int =
        Color.HSVToColor(floatArrayOf(h % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))

    private fun drawRadialGlow(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float, artist: String) {
        p.shader = RadialGradient(
            size * 0.2f, size * 0.2f, size * 1.2f,
            hsvToColor(hue, sat, 0.55f),
            hsvToColor(hue + 30f, sat, 0.15f),
            Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            Color.TRANSPARENT, Color.argb(89, 255, 255, 200), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = Color.argb(216, 255, 255, 255)
        p.textSize = size * 0.09f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(artist.split(" ").firstOrNull()?.uppercase() ?: "", size * 0.08f, size * 0.92f, p)
    }

    private fun drawConcentricCircles(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float) {
        p.shader = null
        p.color = hsvToColor(hue, sat, 0.12f)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        floatArrayOf(0.9f, 0.65f, 0.4f, 0.18f).forEachIndexed { i, r ->
            p.color = if (i % 2 == 0) hsvToColor(hue + i * 8f, sat, 0.20f + i * 0.12f)
                      else             hsvToColor(hue - i * 6f, sat + 0.1f, 0.50f + i * 0.05f)
            c.drawCircle(size * 0.5f, size * 0.55f, size * r / 2f, p)
        }
    }

    private fun drawHorizontalBands(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float) {
        p.shader = null
        for (i in 0..4) {
            p.color = hsvToColor(hue + (i - 2) * 15f, sat + i * 0.05f, 0.25f + i * 0.1f)
            p.alpha = (216 + i * 8).coerceAtMost(255)
            c.drawRect(0f, size * i * 0.2f, size.toFloat(), size * (i + 1) * 0.2f, p)
        }
        p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(),
            Color.TRANSPARENT, Color.argb(89, 0, 0, 0), Shader.TileMode.CLAMP)
        p.alpha = 255
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
    }

    private fun drawBigInitials(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float, title: String) {
        p.shader = LinearGradient(0f, size.toFloat(), size.toFloat(), 0f,
            hsvToColor(hue, sat + 0.1f, 0.45f), hsvToColor(hue + 50f, sat, 0.18f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        val initials = title.split(" ").take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
        p.color = Color.argb(235, 255, 255, 255)
        p.textSize = size * 0.38f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        val bounds = Rect()
        p.getTextBounds(initials, 0, initials.length, bounds)
        c.drawText(initials, size * 0.5f, size * 0.5f + bounds.height() / 2f, p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun drawGridDots(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float, album: String) {
        p.shader = null
        p.color = hsvToColor(hue, sat, 0.10f)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = hsvToColor(hue, sat + 0.2f, 0.65f)
        p.alpha = 153
        val spacing = size / 10f
        var x = spacing / 2f
        while (x < size) {
            var y = spacing / 2f
            while (y < size) { c.drawCircle(x, y, 1.5f, p); y += spacing }
            x += spacing
        }
        p.alpha = 255
        p.color = hsvToColor(hue, sat, 0.70f)
        p.strokeWidth = 2f; p.style = Paint.Style.STROKE
        c.drawLine(size * 0.1f, size * 0.82f, size * 0.9f, size * 0.82f, p)
        p.style = Paint.Style.FILL
        p.textSize = size * 0.055f
        p.color = hsvToColor(hue, sat, 0.85f)
        c.drawText(album.take(14).uppercase(), size * 0.1f, size * 0.92f, p)
    }

    private fun drawHalfAndHalf(c: Canvas, p: Paint, size: Int, hue: Float, sat: Float, id: Long) {
        p.shader = null
        p.color = hsvToColor(hue, sat, 0.15f)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.color = hsvToColor(hue, sat + 0.15f, 0.60f)
        c.drawRect(0f, 0f, size * 0.55f, size.toFloat(), p)
        p.color = hsvToColor(hue + 40f, sat, 0.80f)
        c.drawCircle(size * 0.725f, size * 0.475f, size * 0.175f, p)
        p.color = Color.argb(230, 255, 255, 255)
        p.textSize = size * 0.07f
        p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(String.format(Locale.ROOT, "%02d", id % 12 + 1), size * 0.82f, size * 0.92f, p)
    }
}
