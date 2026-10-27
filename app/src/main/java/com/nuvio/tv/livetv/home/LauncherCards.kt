package com.nuvio.tv.livetv.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.tvprovider.media.tv.TvContractCompat

/** The shape of the cards in the launcher rows (Live TV settings → Integration). */
internal enum class CardShape(val w: Int, val h: Int) {
    WIDE(960, 540), TALL(600, 900), SQUARE(720, 720);

    val aspect: Int
        get() = when (this) {
            WIDE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            TALL -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
            SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
        }

    companion object {
        fun of(s: String?): CardShape = values().firstOrNull { it.name == s } ?: WIDE
    }
}

/** A small tag on a card: LIVE, NEW, REC, SOON. */
internal class CardTag(val text: String, val background: Int, val textColor: Int = Color.WHITE, val dot: Int? = null) {
    override fun toString() = text
}

internal object Tags {
    val LIVE = CardTag("LIVE", 0xFFE53935.toInt())
    val NEW = CardTag("NEW", 0xFF2F80ED.toInt())
    val REC = CardTag("REC", 0xE61A1D25.toInt(), dot = 0xFFFF3B30.toInt())
    val SOON = CardTag("SOON", 0xFFF2A007.toInt(), textColor = 0xFF1A1300.toInt())
}

/** What a card shows. Logos are addresses; the pictures are fetched (once, and kept) when drawn. */
internal sealed class CardSpec {
    abstract val tags: List<CardTag>
    abstract fun signature(): String

    /** A channel and the show on it. */
    class Show(
        val logo: String?,
        val channelName: String,
        val title: String,
        /** "S2 E5", "The Long Night", "Movie", "2019"…: shortened from the end until it fits. */
        val detail: List<String>,
        val time: String?,
        override val tags: List<CardTag>
    ) : CardSpec() {
        override fun signature() = listOf("show", logo, channelName, title, detail.joinToString("|"), time, tags.joinToString()).joinToString("\u0001")
    }

    /** A channel with nothing in the guide: its logo, big. */
    class Channel(val logo: String?, val channelName: String, override val tags: List<CardTag>) : CardSpec() {
        override fun signature() = listOf("ch", logo, channelName, tags.joinToString()).joinToString("\u0001")
    }

    /** A game: both teams, the score and the clock, with the channel showing it. */
    class Game(
        val channelLogo: String?,
        val channelName: String?,
        val away: Team,
        val home: Team,
        /** Null: scores hidden (or not started). */
        val score: Pair<String, String>?,
        val status: String?,
        override val tags: List<CardTag>
    ) : CardSpec() {
        override fun signature() = listOf(
            "game", channelLogo, channelName, away.sig(), home.sig(), score?.toList()?.joinToString("-"), status, tags.joinToString()
        ).joinToString("\u0001")
    }

    class Team(val name: String, val abbr: String, val color: Int?, val logo: String?) {
        fun sig() = "$name/$abbr/$color/$logo"
    }
}

/**
 * Draws the launcher row cards. Everything that matters is in the picture itself (the show, the
 * time, the logo, the teams and score), so every launcher shows the same thing whatever text it
 * does or doesn't show. No rounded corners or borders: launchers add their own.
 */
internal object LauncherCards {
    /** Goes up when the look changes, so every card is drawn again. */
    const val VERSION = "c1"

    private val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    private val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private const val BASE = 0xFF10131A.toInt()

    fun draw(spec: CardSpec, shape: CardShape, load: (String?) -> Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(shape.w, shape.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (spec) {
            is CardSpec.Show -> drawShow(c, shape, spec, LogoArt.of(load(spec.logo)))
            is CardSpec.Channel -> drawChannel(c, shape, spec, LogoArt.of(load(spec.logo)))
            is CardSpec.Game -> drawGame(c, shape, spec, LogoArt.of(load(spec.channelLogo)), load(spec.away.logo), load(spec.home.logo))
        }
        return bmp
    }

    // ------------------------------------------------------------------ shows

    private fun drawShow(c: Canvas, shape: CardShape, s: CardSpec.Show, logo: LogoArt?) {
        val w = shape.w.toFloat()
        val h = shape.h.toFloat()
        backdrop(c, w, h, logo, s.channelName)
        val pad = when (shape) { CardShape.WIDE -> 46f; CardShape.TALL -> 40f; CardShape.SQUARE -> 42f }
        val textW = (w - pad * 2).toInt()
        val titleSize = when (shape) { CardShape.WIDE -> 68f; CardShape.TALL -> 60f; CardShape.SQUARE -> 64f }
        val titleLines = if (shape == CardShape.TALL) 3 else 2
        val detailSize = when (shape) { CardShape.WIDE -> 37f; CardShape.TALL -> 32f; CardShape.SQUARE -> 35f }
        val timeSize = when (shape) { CardShape.WIDE -> 35f; CardShape.TALL -> 30f; CardShape.SQUARE -> 33f }

        // The logo: top left (top centre on tall cards). Tags: top right (under the logo on tall).
        val tile = if (shape == CardShape.TALL) {
            logoTile(c, logo, s.channelName, centerX = w / 2f, left = null, top = pad, maxW = w * 0.72f, h = 150f)
        } else {
            logoTile(c, logo, s.channelName, centerX = null, left = pad, top = pad, maxW = w * 0.34f, h = if (shape == CardShape.WIDE) 120f else 118f)
        }
        if (shape == CardShape.TALL) drawTags(c, s.tags, left = pad, right = null, top = tile.bottom + 26f, h = 48f)
        else drawTags(c, s.tags, left = null, right = w - pad, top = pad, h = 48f)

        // From the bottom up: time, detail, title.
        var y = h - pad
        val timePaint = textPaint(timeSize, 0xFFB3BCCB.toInt(), REGULAR)
        if (!s.time.isNullOrBlank()) {
            val t = TextUtils.ellipsize(s.time, timePaint, textW.toFloat(), TextUtils.TruncateAt.END).toString()
            c.drawText(t, pad, y - timePaint.descent(), timePaint)
            y -= timePaint.fontSpacing + 4f
        }
        val detailPaint = textPaint(detailSize, 0xFFD3DAE5.toInt(), MEDIUM)
        val detail = fitParts(s.detail, detailPaint, textW.toFloat())
        if (detail != null) {
            c.drawText(detail, pad, y - detailPaint.descent(), detailPaint)
            y -= detailPaint.fontSpacing + 6f
        }
        val title = fitTitle(s.title, titleSize, textW, titleLines)
        c.save()
        c.translate(pad, y - title.height)
        title.draw(c)
        c.restore()
    }

    /** A channel with no listings: its logo big in the middle, its name underneath. */
    private fun drawChannel(c: Canvas, shape: CardShape, s: CardSpec.Channel, logo: LogoArt?) {
        val w = shape.w.toFloat()
        val h = shape.h.toFloat()
        backdrop(c, w, h, logo, s.channelName)
        val tileH = when (shape) { CardShape.WIDE -> 190f; CardShape.TALL -> 200f; CardShape.SQUARE -> 200f }
        val tile = logoTile(c, logo, s.channelName, centerX = w / 2f, left = null, top = h * 0.42f - tileH / 2f, maxW = w * 0.62f, h = tileH)
        val p = textPaint(if (shape == CardShape.WIDE) 34f else 32f, 0xFFD3DAE5.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val name = TextUtils.ellipsize(s.channelName, p, w * 0.86f, TextUtils.TruncateAt.END).toString()
        c.drawText(name, w / 2f, tile.bottom + 40f + p.textSize, p)
        drawTags(c, s.tags, left = null, right = w - 40f, top = 40f, h = 44f)
    }

    // ------------------------------------------------------------------ games

    private fun drawGame(c: Canvas, shape: CardShape, s: CardSpec.Game, channel: LogoArt?, awayLogo: Bitmap?, homeLogo: Bitmap?) {
        val w = shape.w.toFloat()
        val h = shape.h.toFloat()
        val a = teamColor(s.away)
        val b = teamColor(s.home)
        // The two teams' colours, split on a slant (darkened so white reads on any of them).
        c.drawColor(BASE)
        val left = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, w * 0.55f, h, darken(a, 0.30f), darken(a, 0.62f), Shader.TileMode.CLAMP) }
        val right = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(w, 0f, w * 0.45f, h, darken(b, 0.30f), darken(b, 0.62f), Shader.TileMode.CLAMP) }
        if (shape == CardShape.TALL) {
            val top = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h * 0.46f); lineTo(0f, h * 0.54f); close() }
            val bottom = Path().apply { moveTo(0f, h * 0.54f); lineTo(w, h * 0.46f); lineTo(w, h); lineTo(0f, h); close() }
            c.drawPath(top, left)
            c.drawPath(bottom, right)
        } else {
            val l = Path().apply { moveTo(0f, 0f); lineTo(w * 0.56f, 0f); lineTo(w * 0.44f, h); lineTo(0f, h); close() }
            val r = Path().apply { moveTo(w * 0.56f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w * 0.44f, h); close() }
            c.drawPath(l, left)
            c.drawPath(r, right)
        }
        bottomShade(c, w, h, 0.55f)

        val pad = 40f
        val score = s.score?.let { "${it.first} – ${it.second}" }
        val statusPaint = textPaint(if (shape == CardShape.WIDE) 35f else 32f, 0xFFE8EDF5.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
        val status = s.status?.let { TextUtils.ellipsize(it, statusPaint, w - pad * 2, TextUtils.TruncateAt.END).toString() }
        when (shape) {
            CardShape.WIDE -> {
                if (channel != null || s.channelName != null) logoTile(c, channel, s.channelName ?: "", centerX = null, left = pad, top = 34f, maxW = w * 0.28f, h = 88f)
                drawTags(c, s.tags, left = null, right = w - pad, top = 38f, h = 44f)
                val cy = h * 0.50f
                val r = 92f
                badge(c, s.away, awayLogo, a, w / 2f - 262f, cy, r)
                badge(c, s.home, homeLogo, b, w / 2f + 262f, cy, r)
                centreText(c, score ?: "vs", w / 2f, cy, if (score != null) 112f else 72f, BOLD)
                val names = textPaint(32f, 0xFFDCE2EC.toInt(), MEDIUM).apply { textAlign = Paint.Align.CENTER }
                c.drawText(TextUtils.ellipsize(s.away.name, names, 270f, TextUtils.TruncateAt.END).toString(), w / 2f - 262f, cy + r + 44f, names)
                c.drawText(TextUtils.ellipsize(s.home.name, names, 270f, TextUtils.TruncateAt.END).toString(), w / 2f + 262f, cy + r + 44f, names)
                status?.let { c.drawText(it, w / 2f, h - 34f - statusPaint.descent(), statusPaint) }
            }
            CardShape.TALL -> {
                if (channel != null || s.channelName != null) logoTile(c, channel, s.channelName ?: "", centerX = w / 2f, left = null, top = 34f, maxW = w * 0.6f, h = 96f)
                drawTags(c, s.tags, left = 34f, right = null, top = 150f, h = 42f)
                val r = 96f
                badge(c, s.away, awayLogo, a, w / 2f, h * 0.36f, r)
                centreText(c, score ?: "vs", w / 2f, h * 0.535f, if (score != null) 96f else 64f, BOLD)
                badge(c, s.home, homeLogo, b, w / 2f, h * 0.71f, r)
                status?.let { c.drawText(it, w / 2f, h - 34f - statusPaint.descent(), statusPaint) }
            }
            CardShape.SQUARE -> {
                if (channel != null || s.channelName != null) logoTile(c, channel, s.channelName ?: "", centerX = w / 2f, left = null, top = 32f, maxW = w * 0.5f, h = 84f)
                drawTags(c, s.tags, left = null, right = w - 32f, top = 36f, h = 42f)
                val cy = h * 0.50f
                val r = 92f
                badge(c, s.away, awayLogo, a, w / 2f - 210f, cy, r)
                badge(c, s.home, homeLogo, b, w / 2f + 210f, cy, r)
                centreText(c, score ?: "vs", w / 2f, cy, if (score != null) 84f else 60f, BOLD)
                status?.let { c.drawText(it, w / 2f, h - 34f - statusPaint.descent(), statusPaint) }
            }
        }
    }

    private fun teamColor(t: CardSpec.Team): Int = t.color ?: hueColor(t.name)

    /** A team's badge: its colour, a white ring, its logo (or its letters). */
    private fun badge(c: Canvas, team: CardSpec.Team, logo: Bitmap?, color: Int, cx: Float, cy: Float, r: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = darken(color, 0.12f)
            setShadowLayer(18f, 0f, 6f, 0x80000000.toInt())
        }
        c.drawCircle(cx, cy, r, fill)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.075f; this.color = 0xE6FFFFFF.toInt() }
        c.drawCircle(cx, cy, r - ring.strokeWidth / 2f, ring)
        val art = logo?.let { trim(it) }
        if (art != null && art.width > 0 && art.height > 0) {
            val box = r * 1.28f
            val scale = minOf(box / art.width, box / art.height)
            val bw = art.width * scale
            val bh = art.height * scale
            c.drawBitmap(art, null, RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } else {
            val text = team.abbr.ifBlank { initials(team.name) }.take(4).uppercase()
            centreText(c, text, cx, cy, if (text.length <= 3) r * 0.62f else r * 0.48f, BOLD)
        }
    }

    // ------------------------------------------------------------------ pieces

    /** A dark backdrop tinted with the logo's colour, with a soft, blurred copy of the logo in it. */
    private fun backdrop(c: Canvas, w: Float, h: Float, logo: LogoArt?, name: String) {
        c.drawColor(BASE)
        val tint = logo?.tint ?: hueColor(name)
        val g = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(darken(tint, 0.42f), darken(tint, 0.74f), BASE),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w, h, g)
        // The logo again, large, soft and faint, towards the right: every channel gets its own look.
        logo?.bitmap?.let { art ->
            val small = Bitmap.createScaledBitmap(art, 24, maxOf(1, (24f * art.height / art.width).toInt()), true)
            val bh = h * 1.15f
            val bw = (bh * art.width / art.height).coerceAtMost(w * 1.2f)
            val bhFit = bw * art.height / art.width
            val left = w - bw * 0.78f
            val top = (h - bhFit) / 2f
            val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 34 }
            c.drawBitmap(small, null, RectF(left, top, left + bw, top + bhFit), p)
        }
        bottomShade(c, w, h, 0.32f)
    }

    private fun bottomShade(c: Canvas, w: Float, h: Float, from: Float) {
        val p = Paint().apply {
            shader = LinearGradient(0f, h * from, 0f, h, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h * from, w, h, p)
    }

    /**
     * The channel's logo on a tile: white for most logos, dark for white ones (or the logo
     * itself, corners rounded, when it has its own background). No logo: the channel's initials.
     */
    private fun logoTile(c: Canvas, logo: LogoArt?, name: String, centerX: Float?, left: Float?, top: Float, maxW: Float, h: Float): RectF {
        val radius = h * 0.2f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(16f, 0f, 5f, 0x73000000) }
        if (logo == null) {
            val text = initials(name)
            val tp = textPaint(h * 0.42f, Color.WHITE, BOLD)
            val tw = (tp.measureText(text) + h * 0.7f).coerceIn(h * 1.2f, maxW)
            val x = centerX?.let { it - tw / 2f } ?: left ?: 0f
            val rect = RectF(x, top, x + tw, top + h)
            shadow.color = darken(hueColor(name), 0.15f)
            c.drawRoundRect(rect, radius, radius, shadow)
            centreText(c, text, rect.centerX(), rect.centerY(), h * 0.42f, BOLD)
            return rect
        }
        val art = logo.bitmap
        val aspect = art.width.toFloat() / art.height
        if (logo.opaque) {
            // Its own background: the logo is the tile.
            var th = h
            var tw = th * aspect
            if (tw > maxW) { tw = maxW; th = tw / aspect }
            val x = centerX?.let { it - tw / 2f } ?: left ?: 0f
            val rect = RectF(x, top, x + tw, top + th)
            shadow.color = Color.BLACK
            c.drawRoundRect(rect, radius, radius, shadow)
            val layer = c.saveLayer(rect, null)
            c.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawBitmap(art, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
            c.restoreToCount(layer)
            return rect
        }
        val inner = h * 0.16f
        var ih = h - inner * 2
        var iw = ih * aspect
        val maxInner = maxW - inner * 2
        if (iw > maxInner) { iw = maxInner; ih = iw / aspect }
        val tw = maxOf(iw + inner * 2, h * 1.25f)
        val x = centerX?.let { it - tw / 2f } ?: left ?: 0f
        val rect = RectF(x, top, x + tw, top + h)
        shadow.color = if (logo.light) 0xFF1C2230.toInt() else Color.WHITE
        c.drawRoundRect(rect, radius, radius, shadow)
        val lx = rect.centerX() - iw / 2f
        val ly = rect.centerY() - ih / 2f
        c.drawBitmap(art, null, RectF(lx, ly, lx + iw, ly + ih), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return rect
    }

    /** Tags in a line, from the left or from the right. */
    private fun drawTags(c: Canvas, tags: List<CardTag>, left: Float?, right: Float?, top: Float, h: Float) {
        if (tags.isEmpty()) return
        val tp = textPaint(h * 0.5f, Color.WHITE, BOLD).apply { letterSpacing = 0.06f }
        val widths = tags.map { t -> tp.measureText(t.text) + h * 0.62f + (if (t.dot != null) h * 0.42f else 0f) }
        val gap = h * 0.25f
        var x = left ?: ((right ?: 0f) - widths.sum() - gap * (tags.size - 1))
        tags.forEachIndexed { i, t ->
            val rect = RectF(x, top, x + widths[i], top + h)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.background; setShadowLayer(10f, 0f, 3f, 0x59000000) }
            c.drawRoundRect(rect, h * 0.22f, h * 0.22f, bg)
            var tx = rect.left + h * 0.31f
            t.dot?.let { d ->
                c.drawCircle(tx + h * 0.15f, rect.centerY(), h * 0.15f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = d })
                tx += h * 0.42f
            }
            tp.color = t.textColor
            c.drawText(t.text, tx, rect.centerY() - (tp.descent() + tp.ascent()) / 2f, tp)
            x += widths[i] + gap
        }
    }

    private fun centreText(c: Canvas, text: String, cx: Float, cy: Float, size: Float, face: Typeface) {
        val p = textPaint(size, Color.WHITE, face).apply { textAlign = Paint.Align.CENTER }
        c.drawText(text, cx, cy - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun textPaint(size: Float, color: Int, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = face
        setShadowLayer(8f, 0f, 2f, 0x8C000000.toInt())
    }

    /**
     * The title on up to [maxLines] lines: a size smaller if that makes it fit, otherwise cut at
     * a whole word with "…" (never in the middle of a word).
     */
    private fun fitTitle(text: String, size: Float, width: Int, maxLines: Int): StaticLayout {
        var last: StaticLayout? = null
        for (s in listOf(size, size * 0.86f, size * 0.76f)) {
            val p = textPaint(s, Color.WHITE, BOLD)
            val l = layout(text, p, width, maxLines)
            last = l
            if (l.lineCount <= maxLines && l.getEllipsisCount(l.lineCount - 1) == 0) return l
        }
        val l = last!!
        val p = textPaint(size * 0.76f, Color.WHITE, BOLD)
        val shown = text.substring(0, (l.getLineStart(l.lineCount - 1) + l.getEllipsisStart(l.lineCount - 1)).coerceIn(0, text.length))
        val cut = shown.substringBeforeLast(' ', shown).trimEnd(',', ':', ';', '-', '–', ' ')
        return layout("$cut…", p, width, maxLines)
    }

    private fun layout(text: String, p: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.98f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build()

    /** Parts joined with " · ", dropping from the end until they fit on one line. */
    private fun fitParts(parts: List<String>, p: TextPaint, width: Float): String? {
        var list = parts.filter { it.isNotBlank() }
        while (list.size > 1 && p.measureText(list.joinToString("  ·  ")) > width) list = list.dropLast(1)
        if (list.isEmpty()) return null
        return TextUtils.ellipsize(list.joinToString("  ·  "), p, width, TextUtils.TruncateAt.END).toString()
    }

    fun initials(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> "TV"
            words.size == 1 -> words[0].take(3).uppercase()
            else -> words.take(3).joinToString("") { it.take(1) }.uppercase()
        }
    }

    /** A steady colour from a name (for channels and teams without one). */
    private fun hueColor(name: String): Int {
        val hue = ((name.lowercase().hashCode().toLong() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.6f))
    }

    private fun darken(color: Int, by: Float): Int {
        val k = 1f - by
        return Color.rgb((Color.red(color) * k).toInt(), (Color.green(color) * k).toInt(), (Color.blue(color) * k).toInt())
    }

    // ------------------------------------------------------------------ logos

    /** A logo ready to draw: trimmed of empty edges, with what it's like (for the tile and the tint). */
    private class LogoArt(val bitmap: Bitmap, val opaque: Boolean, val light: Boolean, val tint: Int?) {
        companion object {
            fun of(raw: Bitmap?): LogoArt? {
                if (raw == null || raw.width < 2 || raw.height < 2) return null
                val art = trim(raw)
                if (art.width < 2 || art.height < 2) return null
                // Look at a small copy: is it see-through, mostly white, and what's its main colour?
                val sw = 32
                val sh = maxOf(1, (32f * art.height / art.width).toInt().coerceAtMost(64))
                val small = Bitmap.createScaledBitmap(art, sw, sh, true)
                val px = IntArray(sw * sh)
                small.getPixels(px, 0, sw, 0, 0, sw, sh)
                var seeThrough = 0
                var solid = 0
                var lumSum = 0.0
                val buckets = DoubleArray(12)
                val rs = DoubleArray(12); val gs = DoubleArray(12); val bs = DoubleArray(12)
                val hsv = FloatArray(3)
                for (p in px) {
                    val a = Color.alpha(p)
                    if (a < 200) { seeThrough++; continue }
                    solid++
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    lumSum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                    Color.colorToHSV(p, hsv)
                    val weight = (hsv[1] * hsv[2]).toDouble()
                    if (hsv[1] > 0.25f && hsv[2] > 0.2f) {
                        val i = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                        buckets[i] += weight; rs[i] += r * weight; gs[i] += g * weight; bs[i] += b * weight
                    }
                }
                val opaque = seeThrough < px.size / 40
                val light = solid > 0 && lumSum / solid > 0.8
                val best = buckets.indices.maxByOrNull { buckets[it] } ?: 0
                val tint = if (buckets[best] > px.size * 0.02) {
                    val k = buckets[best]
                    val c = Color.rgb((rs[best] / k).toInt(), (gs[best] / k).toInt(), (bs[best] / k).toInt())
                    Color.colorToHSV(c, hsv)
                    Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceAtMost(0.8f), 0.7f))
                } else null
                return LogoArt(art, opaque, light, tint)
            }
        }
    }

    /**
     * The logo without the empty space around it (many providers' logos sit in a big
     * transparent or plain square), so it fills its tile properly.
     */
    fun trim(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w * h > 4_000_000) return src
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val corner = px[0]
        val transparentEdges = Color.alpha(corner) < 24
        fun empty(p: Int): Boolean = if (transparentEdges) Color.alpha(p) < 24 else
            Math.abs(Color.red(p) - Color.red(corner)) < 18 && Math.abs(Color.green(p) - Color.green(corner)) < 18 &&
                Math.abs(Color.blue(p) - Color.blue(corner)) < 18 && Color.alpha(p) > 200
        var top = 0; var bottom = h - 1; var left = 0; var right = w - 1
        fun rowEmpty(y: Int) = (0 until w).all { empty(px[y * w + it]) }
        fun colEmpty(x: Int, y0: Int, y1: Int) = (y0..y1).all { empty(px[it * w + x]) }
        while (top < bottom && rowEmpty(top)) top++
        while (bottom > top && rowEmpty(bottom)) bottom--
        while (left < right && colEmpty(left, top, bottom)) left++
        while (right > left && colEmpty(right, top, bottom)) right--
        if (top == 0 && left == 0 && bottom == h - 1 && right == w - 1) return src
        if (right - left < 4 || bottom - top < 4) return src
        // A little breathing room back, so nothing touches the edge.
        val m = (maxOf(right - left, bottom - top) * 0.02f).toInt()
        val x0 = (left - m).coerceAtLeast(0); val y0 = (top - m).coerceAtLeast(0)
        val x1 = (right + m).coerceAtMost(w - 1); val y1 = (bottom + m).coerceAtMost(h - 1)
        return Bitmap.createBitmap(src, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
