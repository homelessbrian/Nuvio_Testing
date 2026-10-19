package com.nuvio.tv.livetv.sports

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/*
 * The Sports look, shared by every Sports page and pop-up so they all match: dark cards with a
 * thin border, each team on its own band in its colour, a status column on the right, the league
 * logo on the left. The highlight is a white border with a soft red glow; the game playing in the
 * video window glows in the theme's accent instead. All of it is flat colour and gradients (no
 * blur, nothing that animates all the time), so it stays light on slower TV boxes.
 */

internal object SportsLook {
    val Card = Color(0xFF12141B)
    val CardRaised = Color(0xFF1A1D26)
    val Border = Color.White.copy(alpha = 0.09f)
    val Divider = Color.White.copy(alpha = 0.08f)
    val Dim = Color(0xFF8B90A0)
    val Faint = Color(0xFF5D6170)
    val Live = Color(0xFFE53935)
    val Flash = Color(0xFFFFD54A)
    val Ink = Color(0xFF0B0C10)
    val Shape = RoundedCornerShape(14.dp)
    val SmallShape = RoundedCornerShape(10.dp)

    /** Scores with every digit the same width, so 9 → 10 doesn't shift the row. */
    val ScoreStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Black)
}

/** A team colour made dark enough for white text on top of it. */
internal fun bandColor(c: Color): Color = lerp(c, SportsLook.Ink, if (c.luminance() > 0.5f) 0.5f else if (c.luminance() > 0.25f) 0.25f else 0.08f)

/**
 * The card frame: dark surface and thin border; highlighted: a little bigger, a white border and
 * a soft red glow; playing in the window: an accent glow.
 */
internal fun Modifier.sportsFrame(
    focused: Boolean,
    playing: Boolean = false,
    accent: Color,
    radius: Dp = 14.dp,
    background: Color = SportsLook.Card
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .graphicsLayer { val s = if (focused) 1.02f else 1f; scaleX = s; scaleY = s }
        .drawBehind {
            val glow = when {
                focused -> SportsLook.Live
                playing -> accent
                else -> null
            } ?: return@drawBehind
            val r = radius.toPx()
            listOf(3.dp.toPx() to 0.30f, 7.dp.toPx() to 0.12f).forEach { (g, a) ->
                drawRoundRect(
                    glow.copy(alpha = a),
                    topLeft = Offset(-g, -g),
                    size = Size(size.width + 2 * g, size.height + 2 * g),
                    cornerRadius = CornerRadius(r + g)
                )
            }
        }
        .clip(shape)
        .background(background)
        // The border on top of everything inside (team bands reach the edges).
        .drawWithContent {
            drawContent()
            val w = (if (focused || playing) 2.dp else 1.dp).toPx()
            drawRoundRect(
                when {
                    focused -> Color.White
                    playing -> accent
                    else -> SportsLook.Border
                },
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((radius.toPx() - w / 2).coerceAtLeast(0f)),
                style = Stroke(w)
            )
        }
}

// ------------------------------------------------------------------ logos and marks

/**
 * The league's logo in the card's left column (from ESPN), or its short name on its colour. Sports
 * found only in the guide (darts, wrestling…) show the sport's symbol.
 */
@Composable
internal fun LeagueMark(league: League?, size: Dp) {
    val logo = league?.let { SportsFeeds.leagueLogos[it.id] }
    when {
        logo != null -> AsyncImage(
            model = logo, contentDescription = league?.name, contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
        league == null || league.guideOnly -> Box(
            Modifier.size(size).clip(RoundedCornerShape(size * 0.25f)).background(Color.White.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) { LiveText((league?.sport ?: Sport.OTHER).icon, size = (size.value * 0.55f).sp) }
        else -> Box(
            Modifier.size(width = size, height = size * 1.1f).clip(RoundedCornerShape(size * 0.2f)).background(leagueColor(league)),
            contentAlignment = Alignment.Center
        ) {
            LiveText(league.short, color = Color.White, size = (size.value * 0.26f).coerceAtLeast(7f).sp, weight = FontWeight.Black, maxLines = 2)
        }
    }
}

/** A team's logo as it is (no disc), on a faint light spot so dark logos still show; its short name when there's no logo. */
@Composable
internal fun TeamLogo(team: TeamInfo?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (team?.logo != null) {
            Box(
                Modifier.size(size * 0.86f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent)))
            )
            AsyncImage(model = team.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        } else {
            val c = teamColor(team)
            Box(
                Modifier.size(size * 0.86f).clip(CircleShape).background(c).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                LiveText(
                    (team?.abbr?.ifBlank { null } ?: team?.name?.take(3) ?: "?").uppercase().take(4),
                    color = if (c.luminance() > 0.55f) SportsLook.Ink else Color.White,
                    size = (size.value * 0.26f).sp, weight = FontWeight.Black
                )
            }
        }
    }
}

/** A small rounded label: LIVE, PLAYING, FINAL, a start time… */
@Composable
internal fun SportsBadge(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    LiveText(
        text,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(background).padding(horizontal = 6.dp, vertical = 1.5.dp),
        color = color, size = 9.sp, weight = FontWeight.Black
    )
}

/** A score: fixed-width digits, dimmed when behind, and a short yellow flash when it changes. */
@Composable
internal fun ScoreLabel(score: String, dim: Boolean, size: TextUnit) {
    var last by remember { mutableStateOf(score) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(score) {
        if (score != last) {
            last = score
            flash = true
            delay(2_500)
            flash = false
        }
    }
    val shape = RoundedCornerShape(6.dp)
    androidx.compose.material3.Text(
        text = score,
        style = SportsLook.ScoreStyle,
        fontSize = size,
        color = if (dim && !flash) Color.White.copy(alpha = 0.5f) else Color.White,
        maxLines = 1,
        modifier = if (flash) Modifier.clip(shape).background(SportsLook.Flash.copy(alpha = 0.18f)).border(1.5.dp, SportsLook.Flash, shape).padding(horizontal = 5.dp)
        else Modifier.padding(horizontal = 5.dp)
    )
}

// ------------------------------------------------------------------ team bands and status

/** One team on its own band in its colour: logo, name (★ when followed), record, and the score. */
@Composable
internal fun TeamBand(
    side: Side,
    showScore: Boolean,
    dim: Boolean,
    followed: Boolean,
    modifier: Modifier = Modifier,
    logo: Dp = 30.dp,
    name: TextUnit = 13.sp,
    score: TextUnit = 22.sp,
    extra: String? = side.record
) {
    val c = bandColor(teamColor(side.team))
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.7f to c.copy(alpha = 0.28f), 1f to c.copy(alpha = 0.05f)))
            .padding(start = 9.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamLogo(side.team, logo)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText(side.team.name, color = if (dim) Color.White.copy(alpha = 0.75f) else Color.White, size = name, weight = FontWeight.ExtraBold)
                if (followed) LiveText(" ★", color = Color(0xFFF5C400), size = name * 0.8f)
            }
            extra?.takeIf { it.isNotBlank() }?.let { LiveText(it, color = Color.White.copy(alpha = 0.6f), size = name * 0.68f, weight = FontWeight.SemiBold) }
        }
        if (showScore) ScoreLabel(side.score.orEmpty(), dim, score)
    }
}

/** The badge for a game's state: PLAYING (in the window), LIVE, FINAL, postponed or its start time. */
@Composable
internal fun StateBadge(ev: SportsEvent, playing: Boolean, use24h: Boolean) {
    val colors = NuvioTheme.colors
    when {
        playing -> SportsBadge("▶ PLAYING", colors.Secondary, colors.OnSecondary)
        ev.state == GameState.LIVE -> SportsBadge("● LIVE", SportsLook.Live, Color.White)
        ev.state == GameState.POST -> SportsBadge("FINAL", Color.White.copy(alpha = 0.12f), Color.White)
        ev.state == GameState.OFF -> SportsBadge((ev.status ?: "POSTPONED").uppercase(), Color(0x33FF8A80), Color(0xFFFF8A80))
        else -> SportsBadge(startLabel(ev, use24h), Color.White.copy(alpha = 0.10f), Color.White)
    }
}

/** A thin red bar: how far through the game is. */
@Composable
internal fun GameProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(SportsLook.Live))
    }
}

/** The status column on the right of a game card: badge, clock, the down and distance, the bar. */
@Composable
internal fun StatusColumn(ev: SportsEvent, playing: Boolean, now: Long, use24h: Boolean, hideScores: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .drawBehind { drawRect(SportsLook.Divider, size = Size(1.dp.toPx(), size.height)) }
            .padding(start = 11.dp, end = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        StateBadge(ev, playing, use24h)
        Spacer(Modifier.height(4.dp))
        val clockText = when (ev.state) {
            GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
            GameState.POST -> ev.status?.takeIf { it != "Final" && it != "FT" } ?: if (ev.hasTime) formatClock(ev.startMs, use24h) else ""
            GameState.PRE -> (if (ev.hasTime && ev.startMs - now < 6 * 3_600_000L) untilLabel(ev.startMs, now, Long.MAX_VALUE) else "").ifBlank { ev.note.orEmpty() }
            GameState.OFF -> ""
        }
        if (clockText.isNotBlank()) LiveText(clockText, color = Color.White, size = 12.sp, weight = FontWeight.Black)
        ev.situation?.takeIf { ev.state == GameState.LIVE && !hideScores }?.let {
            LiveText(it.substringAfter(" · ", it), color = Color(0xFFAAB0BE), size = 9.sp, maxLines = 1)
        }
        if (ev.state == GameState.LIVE) {
            Spacer(Modifier.height(6.dp))
            GameProgress(progressOf(ev, now))
        }
    }
}

// ------------------------------------------------------------------ the game card

/**
 * A game: the league logo on the left, each team on its own band with its score, the status
 * column on the right. Events without two teams (a race, a fight card, wrestling) get one band
 * with the sport's symbol and the event's name.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GameCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    Box(
        modifier
            .fillMaxWidth()
            .height(86.dp)
            .sportsFrame(focused, playing, accent)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.width(48.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.03f))
                    .drawBehind { drawRect(SportsLook.Divider, topLeft = Offset(size.width - 1.dp.toPx(), 0f), size = Size(1.dp.toPx(), size.height)) },
                contentAlignment = Alignment.Center
            ) { LeagueMark(league, 28.dp) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (sides.size == 2) {
                    val showScore = ev.hasScore && !hideScores
                    val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
                    val tied = showScore && sides[0].score == sides[1].score
                    sides.forEach { side ->
                        TeamBand(
                            side = side,
                            showScore = showScore,
                            dim = showScore && !tied && side != lead,
                            followed = follows.followsTeam(ev.leagueId, side.team.id),
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    EventBand(ev, league, sport, Modifier.fillMaxSize())
                }
            }
            StatusColumn(ev, playing, now, use24h, hideScores, Modifier.width(118.dp))
        }
        LiveText("›", color = SportsLook.Dim, size = 16.sp, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 7.dp))
    }
}

/** The single band of an event without two teams: the sport's symbol, its name and a note. */
@Composable
internal fun EventBand(ev: SportsEvent, league: League?, sport: Sport, modifier: Modifier = Modifier, big: Boolean = false) {
    val c = bandColor(leagueColor(league))
    Row(
        modifier
            .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.9f), 0.75f to c.copy(alpha = 0.25f), 1f to Color.Transparent))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(if (big) 52.dp else 38.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) { LiveText(sport.icon, size = if (big) 26.sp else 19.sp) }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            LiveText(ev.title, color = Color.White, size = if (big) 22.sp else 14.sp, weight = FontWeight.ExtraBold, maxLines = 2)
            (ev.note ?: ev.word?.let { "Mentions $it" } ?: league?.name)?.let {
                LiveText(it, color = Color.White.copy(alpha = 0.65f), size = if (big) 13.sp else 10.sp, maxLines = 1)
            }
        }
    }
}

/** Grey card shapes while the games load, so the page doesn't jump when they arrive. */
@Composable
internal fun PlaceholderCard(modifier: Modifier = Modifier, height: Dp = 86.dp) {
    Box(
        modifier.fillMaxWidth().height(height).clip(SportsLook.Shape)
            .background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.04f))))
    )
}

// ------------------------------------------------------------------ coming up

/** A game still to come: start time, league, both teams with logos and records, the channel. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpcomingRow(
    ev: SportsEvent,
    channel: String?,
    follows: SportsFollows,
    reminded: Boolean,
    use24h: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .sportsFrame(focused, playing, accent, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val time = startLabel(ev, use24h)
        val ampm = time.takeLast(2).takeIf { it == "AM" || it == "PM" }
        Row(Modifier.width(62.dp), verticalAlignment = Alignment.Bottom) {
            LiveText(if (ampm != null) time.dropLast(2).trim() else time, color = Color.White, size = 14.sp, weight = FontWeight.Black)
            ampm?.let { LiveText(" $it", color = SportsLook.Dim, size = 8.sp, weight = FontWeight.Bold) }
        }
        LeagueMark(league, 20.dp)
        Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(26.dp).background(SportsLook.Divider))
        if (sides.size == 2) {
            UpcomingTeam(sides[0], follows.followsTeam(ev.leagueId, sides[0].team.id), Modifier.weight(1f))
            LiveText(if (sport.homeFirst) "v" else "@", color = SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
            UpcomingTeam(sides[1], follows.followsTeam(ev.leagueId, sides[1].team.id), Modifier.weight(1f))
        } else {
            LiveText(sport.icon, size = 15.sp)
            Spacer(Modifier.width(8.dp))
            LiveText(ev.title, color = Color.White, size = 12.sp, weight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        if (reminded) {
            LiveText("🔔", size = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        }
        channel?.let {
            LiveText(
                it,
                modifier = Modifier.widthIn(max = 120.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                color = Color.White, size = 9.sp, weight = FontWeight.Black
            )
        }
        LiveText("›", color = SportsLook.Dim, size = 16.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun UpcomingTeam(side: Side, followed: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(side.team, 26.dp)
        Spacer(Modifier.width(7.dp))
        Column {
            LiveText(side.team.name + if (followed) " ★" else "", color = Color.White, size = 11.sp, weight = FontWeight.ExtraBold)
            side.record?.let { LiveText(it, color = SportsLook.Dim, size = 8.sp) }
        }
    }
}

// ------------------------------------------------------------------ the top of the page

/** Yesterday / Today / Tomorrow: the day on screen outlined in the accent colour. */
@Composable
internal fun DayTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val shape = RoundedCornerShape(50)
    LiveText(
        text,
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .border(1.5.dp, if (selected && !focused) accent else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = when {
            focused -> SportsLook.Ink
            selected -> Color.White
            else -> SportsLook.Dim
        },
        size = 12.sp, weight = FontWeight.Bold
    )
}

/**
 * A round icon filter (My Teams, Live, All, a sport). The one switched on is filled white; the
 * highlighted one opens out to show its name, so nobody has to guess what an icon means.
 */
@Composable
internal fun FilterIcon(icon: String, label: String, selected: Boolean, modifier: Modifier = Modifier, iconColor: Color? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(32.dp)
            .widthIn(min = 32.dp)
            .animateContentSize()
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> SportsLook.CardRaised
                    else -> SportsLook.Card
                }
            )
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else SportsLook.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (focused) 11.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        LiveText(icon, color = iconColor ?: if (selected) SportsLook.Ink else Color(0xFFD7DAE3), size = 14.sp, weight = FontWeight.Bold)
        if (focused) {
            Spacer(Modifier.width(6.dp))
            LiveText(label, color = if (selected) SportsLook.Ink else Color.White, size = 12.sp, weight = FontWeight.ExtraBold)
        }
    }
}

/** A section's heading: "● LIVE NOW", "COMING UP", with the count on the right. */
@Composable
internal fun SectionHeader(title: String, live: Boolean = false, count: Int? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(SportsLook.Live))
            Spacer(Modifier.width(7.dp))
        }
        LiveText(title, color = if (live) Color.White else Color(0xFFC9CCD6), size = 11.sp, weight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        count?.let { LiveText(if (it == 1) "1 game" else "$it games", color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold) }
    }
}

// ------------------------------------------------------------------ buttons, rows and pop-ups

/** A rounded button (actions on the game page, in pop-ups): white when highlighted. */
@Composable
internal fun SportsAction(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    LiveText(
        text,
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.14f)
                    else -> SportsLook.Card
                }
            )
            .border(1.dp, if (focused) Color.White else if (selected) Color.White.copy(alpha = 0.35f) else SportsLook.Border, shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (focused) SportsLook.Ink else Color.White,
        size = 12.sp, weight = FontWeight.ExtraBold
    )
}

/** A line in a Sports pop-up: white when highlighted, like the cards. */
@Composable
internal fun SportsMenuRow(label: String, modifier: Modifier = Modifier, detail: String? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveText(label, color = if (focused) SportsLook.Ink else Color.White, size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        detail?.let { LiveText(it, color = if (focused) Color(0xFF555A66) else SportsLook.Dim, size = 12.sp) }
    }
}

/**
 * A Sports pop-up: the same dark card and border as the game cards, with an optional band at
 * the top (the game, in its teams' colours). A long press that opened it can't also press the
 * first item (the rest of that press is swallowed), like Live TV's pop-ups.
 */
@Composable
internal fun SportsDialog(
    onDismiss: () -> Unit,
    width: Dp = 520.dp,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var sawKeyDown by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    when (e.type) {
                        KeyEventType.KeyDown -> if (!sawKeyDown && e.nativeKeyEvent.repeatCount > 0) true else { sawKeyDown = true; false }
                        KeyEventType.KeyUp -> !sawKeyDown
                        else -> false
                    }
                }
                .widthIn(min = 320.dp, max = width)
                .heightIn(max = 620.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SportsLook.Card)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
        ) {
            header?.invoke()
            Column(Modifier.padding(18.dp)) { content() }
        }
    }
}

/** The top of a pop-up about a game: the two teams on their bands, or the event's band. */
@Composable
internal fun GameHeaderBands(ev: SportsEvent, hideScores: Boolean) {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    if (sides.size == 2) {
        val showScore = ev.hasScore && !hideScores
        val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
        val tied = showScore && sides[0].score == sides[1].score
        Column(Modifier.fillMaxWidth()) {
            sides.forEach { s ->
                TeamBand(s, showScore, showScore && !tied && s != lead, followed = false, modifier = Modifier.height(46.dp), logo = 32.dp, name = 15.sp, score = 22.sp)
            }
        }
    } else {
        EventBand(ev, league, sport, Modifier.fillMaxWidth().height(64.dp))
    }
}

/**
 * A channel on the game page: its logo, name and what's on, a tag saying how sure we are
 * (SHOWING IT, or MAYBE with the reason when highlighted), the picture quality, and the show's
 * progress along the bottom.
 */
@Composable
internal fun SportsChannelRow(
    m: ChannelMatch,
    finished: Boolean,
    playing: Boolean,
    now: Long,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val ink = if (focused) SportsLook.Ink else Color.White
    val sub = if (focused) Color(0xFF555A66) else SportsLook.Dim
    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .sportsFrame(focused, playing, accent, radius = 12.dp, background = if (focused) Color.White else SportsLook.Card)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 46.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (focused) Color(0xFFE8E9EE) else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) { ChannelLogo(m.channel.logo, 24.dp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                LiveText(m.channel.name, color = ink, size = 13.sp, weight = FontWeight.ExtraBold)
                val line = when {
                    focused && !m.sure -> "Guess: ${m.how}"
                    finished -> if (m.catchup) "Replay from the start" else "No replay on this channel"
                    else -> m.program?.title?.takeIf { it != m.channel.name } ?: m.channel.group
                }
                LiveText(line, color = sub, size = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            when {
                playing -> SportsBadge("▶ PLAYING", accent, NuvioTheme.colors.OnSecondary)
                m.sure -> SportsBadge("SHOWING IT", if (focused) SportsLook.Ink else accent, if (focused) Color.White else NuvioTheme.colors.OnSecondary)
                else -> LiveText(
                    "MAYBE",
                    modifier = Modifier.border(1.dp, if (focused) Color(0xFF999999) else Color.White.copy(alpha = 0.35f), RoundedCornerShape(9.dp))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                    color = if (focused) Color(0xFF555555) else Color(0xFFC9CCD6), size = 9.sp, weight = FontWeight.Black
                )
            }
            m.quality?.let { q ->
                Spacer(Modifier.width(6.dp))
                LiveText(
                    q,
                    modifier = Modifier.border(1.dp, if (focused) SportsLook.Ink else accent.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    color = if (focused) SportsLook.Ink else accent, size = 9.sp, weight = FontWeight.Black
                )
            }
        }
        m.program?.takeIf { !finished && now in it.startMs until it.stopMs }?.let { p ->
            val f = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(f).height(2.dp).background(accent.copy(alpha = if (m.sure) 1f else 0.5f)))
        }
    }
}

/** A text that fits on one line, cut with "…" (for the narrow info strip under the video). */
@Composable
internal fun OneLine(text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
