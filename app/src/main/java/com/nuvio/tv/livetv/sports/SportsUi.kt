package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme

// ------------------------------------------------------------------ colors

/** A team's color that reads on a dark screen: the alternate color when the main one is near white. */
internal fun teamColor(t: TeamInfo?): Color {
    val main = t?.color?.let { Color(it) }
    val alt = t?.altColor?.let { Color(it) }
    val pick = when {
        main == null -> alt
        main.luminance() > 0.62f && alt != null && alt.luminance() < 0.62f -> alt
        else -> main
    }
    return pick ?: Color(0xFF3A4150)
}

/** Darkened enough for white text on top. */
internal fun cardShade(c: Color, extra: Float = 0f): Color =
    lerp(c, Color(0xFF07080B), ((if (c.luminance() > 0.45f) 0.62f else if (c.luminance() > 0.2f) 0.42f else 0.22f) + extra).coerceIn(0f, 0.9f))

internal fun leagueColor(l: League?): Color = Color(l?.color ?: 0xFF2A2F3A)

/** The card background: one team's color on each side of a slanted line, darkened for text. */
internal fun Modifier.splitBackground(left: Color, right: Color): Modifier = this.drawBehind {
    val a = cardShade(left)
    val b = cardShade(right)
    drawRect(a)
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(size.width * 0.58f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width, size.height)
        lineTo(size.width * 0.40f, size.height)
        close()
    }
    drawPath(path, Brush.linearGradient(listOf(b, cardShade(right, 0.2f)), start = Offset(size.width * 0.5f, 0f), end = Offset(size.width, size.height)))
    // A soft dark fade at the bottom, behind the channel buttons.
    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
}

// ------------------------------------------------------------------ small pieces

/** A team's logo on a light disc ringed in its color, or its short name on its color when there's no logo. */
@Composable
internal fun TeamBadge(team: TeamInfo?, size: Dp, modifier: Modifier = Modifier) {
    val ring = teamColor(team)
    if (team?.logo != null) {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(Color(0xFFF4F5F7))
                .border((size.value / 16f).coerceAtLeast(1.5f).dp, ring, CircleShape)
                .padding(size * 0.14f),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(model = team.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    } else {
        val text = if (ring.luminance() > 0.55f) Color(0xFF111111) else Color.White
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(ring)
                .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            LiveText(
                (team?.abbr?.ifBlank { null } ?: team?.name?.take(3) ?: "?").uppercase().take(4),
                color = text, size = (size.value * 0.28f).sp, weight = FontWeight.Black
            )
        }
    }
}

/** A round badge for an event with no teams (a race, a tournament): the sport's symbol. */
@Composable
internal fun SportBadge(sport: Sport, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color.Black.copy(alpha = 0.3f))
            .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center
    ) {
        LiveText(sport.icon, size = (size.value * 0.48f).sp)
    }
}

@Composable
internal fun LeagueTag(league: League?, onCard: Boolean = true) {
    LiveText(
        league?.short ?: "SPORT",
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (onCard) Color.Black.copy(alpha = 0.38f) else Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (onCard) Color.White else NuvioTheme.colors.TextSecondary,
        size = 10.sp,
        weight = FontWeight.Black
    )
}

/** A red pulsing-style dot for "on now". */
@Composable
internal fun LiveDot(size: Dp = 7.dp) {
    Box(
        Modifier
            .size(size + 5.dp)
            .clip(CircleShape)
            .background(Color(0x40FF5252)),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0xFFFF5252)))
    }
}

/** A channel button on a card: the channel's name and quality; the best one is white. */
@Composable
internal fun ChannelChip(m: ChannelMatch, best: Boolean, onCard: Boolean = true) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    best -> Color.White
                    onCard -> Color.Black.copy(alpha = 0.42f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(
            m.channel.name, color = if (best) Color(0xFF0B0C10) else Color.White, size = 11.sp, weight = FontWeight.Bold,
            modifier = Modifier.widthIn(max = 150.dp)
        )
        m.quality?.let { q ->
            Spacer(Modifier.width(5.dp))
            LiveText(
                q,
                modifier = Modifier
                    .border(1.dp, if (best) Color(0x660B0C10) else Color(0x992BC4E6), RoundedCornerShape(3.dp))
                    .padding(horizontal = 3.dp),
                color = if (best) Color(0xFF0B0C10) else Color(0xFF2BC4E6), size = 8.sp, weight = FontWeight.Black
            )
        }
    }
}

// ------------------------------------------------------------------ words for times and states

internal fun startLabel(ev: SportsEvent, use24h: Boolean): String =
    if (!ev.hasTime) "All day" else formatClock(ev.startMs, use24h)

/** "in 1 h 51", "in 12 min", "Tonight", "Tomorrow". */
internal fun untilLabel(startMs: Long, now: Long, dayEnd: Long): String {
    val m = ((startMs - now) / 60_000L).toInt()
    return when {
        m <= 0 -> "Starting"
        m < 60 -> "in $m min"
        m < 6 * 60 -> "in ${m / 60} h ${m % 60}"
        startMs < dayEnd -> "Later today"
        else -> ""
    }
}

/** What the corner of a card says: the game clock, "Started 1:00", "Final", or the start time. */
internal fun cornerStatus(ev: SportsEvent, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> if (!hideScores && ev.status != null && ev.source == EventSource.ESPN && ev.hasScore) ev.status
    else "Started " + formatClock(ev.startMs, use24h)
    GameState.POST -> if (ev.source == EventSource.GUIDE) "Ended" else (ev.status ?: "Final")
    GameState.OFF -> ev.status ?: "Postponed"
    GameState.PRE -> startLabel(ev, use24h)
}

/** Time bar: how far through, from the game clock when known, otherwise from the start time and usual length. */
internal fun progressOf(ev: SportsEvent, now: Long): Float {
    if (ev.state == GameState.POST) return 1f
    if (ev.state != GameState.LIVE) return 0f
    ev.progress?.let { return it }
    val league = SportsCatalog.league(ev.leagueId)
    val end = ev.endMs ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
    return ((now - ev.startMs).toFloat() / (end - ev.startMs).coerceAtLeast(1L)).coerceIn(0.02f, 0.98f)
}

internal fun endEstimate(ev: SportsEvent): Long {
    val league = SportsCatalog.league(ev.leagueId)
    return ev.endMs?.takeIf { ev.hasTime } ?: (ev.startMs + (league?.minutes ?: 120) * 60_000L)
}

/** A followed team plays, a league you follow, a name you follow, or a reminder you set. */
internal fun isMine(ev: SportsEvent, f: SportsFollows): Boolean =
    ev.leagueId in f.leagues || ev.word != null ||
        ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) } ||
        f.reminders.any { it.eventId == ev.id }

// ------------------------------------------------------------------ the event card

/**
 * The big card for a game on now: one team per row with the score on the right when there is
 * one ("No live score" otherwise), the time bar and the best channels. Events with no teams
 * (a race, a fight card, darts) show their name instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventCard(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    matching: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 300.dp,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val left = if (sides.size == 2) teamColor(sides[0].team) else leagueColor(league)
    val right = if (sides.size == 2) teamColor(sides[1].team) else lerp(leagueColor(league), Color.Black, 0.3f)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .width(width)
            .height(196.dp)
            .graphicsLayer { val s = if (focused) 1.03f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .splitBackground(left, right)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.1f), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeagueTag(league)
                Spacer(Modifier.width(6.dp))
                LiveText(
                    ev.subtitle?.substringBefore(" · ")?.takeIf { sides.size == 2 } ?: "",
                    color = Color.White.copy(alpha = 0.75f), size = 10.sp, weight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (ev.state == GameState.LIVE) { LiveDot(); Spacer(Modifier.width(4.dp)) }
                LiveText(cornerStatus(ev, use24h, hideScores), color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
            if (sides.size == 2) {
                val showScore = ev.hasScore && !hideScores
                val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
                val tied = showScore && sides[0].score == sides[1].score
                sides.forEachIndexed { i, side ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        TeamBadge(side.team, 30.dp)
                        Spacer(Modifier.width(10.dp))
                        LiveText(side.team.name, color = Color.White, size = 15.sp, weight = FontWeight.Black)
                        if (follows.followsTeam(ev.leagueId, side.team.id)) LiveText(" ★", color = Color(0xFFF5C400), size = 12.sp)
                        side.record?.let { LiveText("  $it", color = Color.White.copy(alpha = 0.55f), size = 10.sp) }
                        Spacer(Modifier.weight(1f))
                        when {
                            showScore -> LiveText(
                                side.score.orEmpty(), size = 24.sp, weight = FontWeight.Black,
                                color = if (tied || side == lead) Color.White else Color.White.copy(alpha = 0.5f)
                            )
                            i == 0 && ev.state == GameState.LIVE && !hideScores -> LiveText(
                                "NO LIVE SCORE", color = Color.White.copy(alpha = 0.55f), size = 9.sp, weight = FontWeight.ExtraBold
                            )
                            i == 0 && ev.state == GameState.LIVE -> LiveText(
                                "SCORE HIDDEN", color = Color.White.copy(alpha = 0.55f), size = 9.sp, weight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SportBadge(sport, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LiveText(ev.title, color = Color.White, size = 15.sp, weight = FontWeight.Black, maxLines = 2)
                        (ev.note ?: ev.word?.let { "Mentions $it" } ?: ev.subtitle)?.let {
                            LiveText(it, color = Color.White.copy(alpha = 0.7f), size = 11.sp, maxLines = 1)
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText(
                    if (ev.hasTime) formatClock(ev.startMs, use24h) else "Today",
                    color = Color.White.copy(alpha = 0.75f), size = 9.sp, weight = FontWeight.ExtraBold
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Box(Modifier.fillMaxWidth(progressOf(ev, now)).fillMaxHeight().background(Color.White))
                }
                Spacer(Modifier.width(8.dp))
                LiveText(
                    if (ev.state == GameState.POST) "Ended" else "~" + formatClock(endEstimate(ev), use24h),
                    color = Color.White.copy(alpha = 0.75f), size = 9.sp, weight = FontWeight.ExtraBold
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    matches.isNotEmpty() -> matches.take(2).forEachIndexed { i, m -> ChannelChip(m, best = i == 0 && m.score >= 55) }
                    matching -> LiveText("Finding channels…", color = Color.White.copy(alpha = 0.6f), size = 10.sp)
                    else -> LiveText("Not found in your channels", color = Color.White.copy(alpha = 0.6f), size = 10.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the row (Coming Up, Finished)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EventRow(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    follows: SportsFollows,
    reminded: Boolean,
    hideScores: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val shape = RoundedCornerShape(10.dp)
    val textColor = if (focused) Color(0xFF0B0C10) else Color.White
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.05f))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueTag(league, onCard = false)
        Spacer(Modifier.width(10.dp))
        val mine = sides.any { follows.followsTeam(ev.leagueId, it.team.id) } || ev.word != null
        if (mine) { LiveText("★", color = Color(0xFFF5C400), size = 12.sp); Spacer(Modifier.width(8.dp)) }
        if (sides.size == 2) {
            TeamBadge(sides[0].team, 22.dp)
            Spacer(Modifier.width(8.dp))
            LiveText(sides[0].team.name, color = textColor, size = 13.sp, weight = FontWeight.Black)
            LiveText(if (sport.homeFirst) "  v  " else "  at  ", color = if (focused) Color(0xFF555A66) else Color(0xFF8B90A0), size = 11.sp)
            LiveText(sides[1].team.name, color = textColor, size = 13.sp, weight = FontWeight.Black)
            Spacer(Modifier.width(8.dp))
            TeamBadge(sides[1].team, 22.dp)
            if (ev.state == GameState.POST && ev.hasScore) {
                Spacer(Modifier.width(12.dp))
                LiveText(
                    if (hideScores) "Final" else "${sides[0].score} – ${sides[1].score}" + (ev.status?.takeIf { it != "Final" && it != "FT" }?.let { "  ($it)" } ?: ""),
                    color = textColor, size = 13.sp, weight = FontWeight.ExtraBold
                )
            }
        } else {
            LiveText(ev.title, color = textColor, size = 13.sp, weight = FontWeight.Black, modifier = Modifier.widthIn(max = 380.dp))
            (ev.note ?: ev.word?.let { "Mentions $it" })?.let {
                LiveText("  ·  $it", color = if (focused) Color(0xFF555A66) else Color(0xFF8B90A0), size = 11.sp)
            }
        }
        if (ev.state == GameState.OFF) {
            Spacer(Modifier.width(10.dp))
            LiveText(ev.status ?: "Postponed", color = Color(0xFFFF8A80), size = 11.sp, weight = FontWeight.Bold)
        }
        if (reminded) {
            Spacer(Modifier.width(10.dp))
            LiveText(
                "🔔 Reminder set",
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF2BC4E6)).padding(horizontal = 7.dp, vertical = 2.dp),
                color = Color(0xFF06222B), size = 10.sp, weight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (matches.isNotEmpty()) matches.take(2).forEach { ChannelChip(it, best = false, onCard = false) }
            else if (ev.state != GameState.POST) LiveText(
                "Not in your channels", color = if (focused) Color(0xFF555A66) else Color(0xFF7C8090), size = 10.sp,
                align = TextAlign.End
            )
        }
    }
}

