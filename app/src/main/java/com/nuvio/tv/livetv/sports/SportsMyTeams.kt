package com.nuvio.tv.livetv.sports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * My Teams as rows, like a TV app's home: the next big game (with the video window beside it),
 * then "Live & Upcoming", a row per team or country you follow, a row per league on your top bar,
 * and a row per whole league or sport you follow. Up and down between rows, left and right
 * through a row's games. OK opens the game info; hold OK plays it.
 */

/** "Today", "Tomorrow", "Yesterday", or "Sat 24 Oct". */
internal fun dayWord(ms: Long, now: Long): String {
    fun day(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diff = Math.round((day(ms) - day(now)) / 86_400_000.0).toInt()
    return when (diff) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

/** When a game is, for a card's footer: the clock when it's on, "Today 8:00 PM", "FT · Sat 17 Oct". */
internal fun whenLabel(ev: SportsEvent, now: Long, use24h: Boolean, hideScores: Boolean): String = when (ev.state) {
    GameState.LIVE -> cornerStatus(ev, use24h, hideScores)
    GameState.POST -> (ev.status?.takeIf { it.length <= 6 } ?: "Final") + " · " + dayWord(ev.startMs, now)
    GameState.OFF -> (ev.status ?: "Postponed") + " · " + dayWord(ev.startMs, now)
    GameState.PRE -> dayWord(ev.startMs, now) + if (ev.hasTime) " " + formatClock(ev.startMs, use24h) else ""
}

/** The round or week from a game's subtitle ("Round 12", "Week 8"), and the rest (the ground). */
private fun splitSubtitle(ev: SportsEvent): Pair<String?, String?> {
    val parts = ev.subtitle?.split(" · ").orEmpty()
    val round = parts.firstOrNull { it.startsWith("Round ") || it.startsWith("Week ") || it.startsWith("Matchday ") }
    val rest = parts.filter { it != round }.lastOrNull()
    return round to rest
}

// ------------------------------------------------------------------ the page

@Composable
internal fun MyTeamsRows(
    page: MyTeamsPage,
    follows: SportsFollows,
    settings: SportsSettings,
    matches: Map<String, List<ChannelMatch>>,
    now: Long,
    use24h: Boolean,
    playingKey: String?,
    videoOn: Boolean,
    videoSlot: @Composable (Modifier) -> Unit,
    header: @Composable () -> Unit,
    columnState: LazyListState,
    rowState: (String, Int) -> LazyListState,
    requesterFor: (String) -> FocusRequester,
    lastFocus: String?,
    onFocusKey: (String) -> Unit,
    /** Put the highlight on the page (first visit, or back from a game); not when switching filters. */
    autoFocus: Boolean,
    returning: Boolean,
    onReturned: () -> Unit,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    onPick: () -> Unit
) {
    fun playing(ev: SportsEvent) = playingKey != null && matches[ev.id]?.any { it.channel.key == playingKey } == true
    fun channelOf(ev: SportsEvent) = matches[ev.id]?.firstOrNull()?.channel?.name ?: ev.broadcasts.firstOrNull()
    val hero = page.hero
    val heroReq = requesterFor("hero")
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(hero != null, page.rows.isNotEmpty()) {
        delay(140)
        if (focusedOnce || !autoFocus) return@LaunchedEffect
        // Still loading your teams' games: wait for them (the highlight would be lost otherwise).
        if (hero == null && page.rows.isEmpty() && !follows.isEmpty) return@LaunchedEffect
        focusedOnce = true
        val back = if (returning) lastFocus?.let { requesterFor(it) } else null
        onReturned()
        runCatching { (back ?: heroReq).requestFocus() }
            .onFailure { runCatching { heroReq.requestFocus() } }
    }

    Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp)) {
        header()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(168.dp)) {
            if (hero != null) {
                HeroCard(
                    ev = hero,
                    follows = follows,
                    now = now,
                    use24h = use24h,
                    hideScores = settings.hideScores,
                    channel = channelOf(hero),
                    playing = playing(hero),
                    reminded = follows.reminders.any { it.eventId == hero.id },
                    modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(heroReq).onFocusChanged { if (it.isFocused) onFocusKey("hero") },
                    onClick = { onOpen(hero) },
                    onLongClick = { onQuickWatch(hero) }
                )
            } else {
                Column(
                    Modifier.weight(1f).fillMaxHeight().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(18.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    LiveText(
                        if (follows.isEmpty) "Follow your teams to see their games here." else "Nothing coming up for your teams yet.",
                        size = 18.sp, weight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(10.dp))
                    SportsAction("✎  Pick Your Teams", modifier = Modifier.focusRequester(heroReq), onClick = onPick)
                }
            }
            if (videoOn) {
                Spacer(Modifier.width(16.dp))
                videoSlot(Modifier.fillMaxHeight().aspectRatio(16f / 9f))
            }
        }
        LazyColumn(
            state = columnState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 4.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(page.rows, key = { it.key }) { row ->
                Column {
                    RowHeader(row, follows)
                    if (row.events.isEmpty()) {
                        LiveText(
                            "No games found for ${row.title} yet. They show here as soon as the schedule has them.",
                            color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                        )
                    } else LazyRow(
                        state = rowState(row.key, row.startIndex),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(start = 4.dp, end = 24.dp, top = 4.dp, bottom = 6.dp)
                    ) {
                        itemsIndexed(row.events, key = { _, ev -> ev.id }) { _, ev ->
                            val key = "${row.key}|${ev.id}"
                            RowCard(
                                ev = ev,
                                follows = follows,
                                now = now,
                                use24h = use24h,
                                hideScores = settings.hideScores,
                                channel = channelOf(ev),
                                playing = playing(ev),
                                modifier = Modifier.focusRequester(requesterFor(key)).onFocusChanged { if (it.isFocused) onFocusKey(key) },
                                onClick = { onOpen(ev) },
                                onLongClick = { onQuickWatch(ev) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A row's heading: the team's badge (or the league's mark), its name, and how many games. */
@Composable
private fun RowHeader(row: SportsRow, follows: SportsFollows) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (row.kind) {
            SportsRow.Kind.UPCOMING -> Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(SportsLook.Live))
            SportsRow.Kind.TEAM -> row.team?.let { t ->
                TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 22.dp)
            }
            SportsRow.Kind.LEAGUE -> LeagueMark(row.league, 20.dp)
            SportsRow.Kind.WORD -> LiveText("★", color = Color(0xFFF5C400), size = 14.sp)
        }
        Spacer(Modifier.width(9.dp))
        LiveText(row.title, size = 15.sp, weight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        val ahead = row.events.count { it.state != GameState.POST }
        LiveText(
            when (row.kind) {
                SportsRow.Kind.UPCOMING -> "Your teams, soonest first"
                SportsRow.Kind.LEAGUE -> if (ahead == 1) "1 game ahead" else "$ahead games ahead"
                else -> if (ahead == 1) "1 game ahead" else "$ahead games ahead"
            },
            color = SportsLook.Dim, size = 11.sp, weight = FontWeight.Bold
        )
    }
}

// ------------------------------------------------------------------ cards

/**
 * A game in a row: the competition (and round) on top, the two teams on their colour bands with
 * the score, then when and where, and the channel. TODAY / TOMORROW / LIVE in the corner.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    channel: String?,
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
    val look = if (sides.size != 2) eventLook(ev, league) else null
    val premium = look?.premium == true
    val (round, place) = remember(ev.subtitle) { splitSubtitle(ev) }
    Column(
        modifier
            .width(222.dp)
            .height(104.dp)
            .sportsFrame(focused, playing || premium, if (playing) accent else Color(0xFFFFCE54), radius = 12.dp)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            EventMark(ev, league, 14.dp)
            Spacer(Modifier.width(6.dp))
            OneLine(listOfNotNull(league?.name, round).joinToString(" · "), Color(0xFFB4B9C6), 9.sp, FontWeight.SemiBold, Modifier.weight(1f))
            CornerBadge(ev, now, playing)
        }
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
                    modifier = Modifier.height(24.dp),
                    logo = 18.dp, name = 11.sp, score = 14.sp, extra = null
                )
            }
        } else if (look != null) {
            val c = bandColor(look.color)
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.72f to c.copy(alpha = 0.25f), 1f to c.copy(alpha = 0.04f)))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    look.label?.let { LiveText(it, color = if (premium) Color(0xFFFFCE54) else Color.White.copy(alpha = 0.7f), size = 7.sp, weight = FontWeight.Black) }
                    LiveText(look.headline, color = look.text, size = 15.sp, weight = FontWeight.Black, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f).padding(start = 8.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(whenLabel(ev, now, use24h, hideScores), color = Color.White, size = 9.sp, weight = FontWeight.Black)
            Box(Modifier.weight(1f)) {
                (place ?: ev.note)?.let { OneLine(" · $it", SportsLook.Dim, 9.sp) }
            }
            channel?.let {
                LiveText(
                    it,
                    modifier = Modifier.widthIn(max = 72.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 5.dp, vertical = 1.dp),
                    color = Color.White, size = 8.sp, weight = FontWeight.Black, maxLines = 1
                )
            }
        }
    }
}

/** LIVE, PLAYING, TODAY or TOMORROW in a card's top corner. */
@Composable
private fun CornerBadge(ev: SportsEvent, now: Long, playing: Boolean) {
    val colors = NuvioTheme.colors
    when {
        playing -> SportsBadge("▶ PLAYING", colors.Secondary, colors.OnSecondary)
        ev.state == GameState.LIVE -> SportsBadge("● LIVE", SportsLook.Live, Color.White)
        ev.state == GameState.PRE -> when (dayWord(ev.startMs, now)) {
            "Today" -> OutlineBadge("TODAY", colors.Secondary)
            "Tomorrow" -> OutlineBadge("TOMORROW", colors.Secondary)
            else -> Unit
        }
        else -> Unit
    }
}

@Composable
private fun OutlineBadge(text: String, color: Color) {
    val shape = RoundedCornerShape(5.dp)
    LiveText(
        text,
        modifier = Modifier.clip(shape).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.6f), shape).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Color.White, size = 8.sp, weight = FontWeight.Black
    )
}

/**
 * The next big game for your teams (or the one on now): both teams large on the first team's
 * colour, the score when it's on, and when, where and which channel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCard(
    ev: SportsEvent,
    follows: SportsFollows,
    now: Long,
    use24h: Boolean,
    hideScores: Boolean,
    channel: String?,
    playing: Boolean,
    reminded: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val mine = sides.firstOrNull { follows.followsTeam(ev.leagueId, it.team.id) }
    val look = if (sides.size != 2) eventLook(ev, league) else null
    val base = bandColor(mine?.let { teamColor(it.team) } ?: look?.color ?: sides.firstOrNull()?.let { teamColor(it.team) } ?: leagueColor(league))
    val other = sides.firstOrNull { it != mine }?.let { bandColor(teamColor(it.team)) } ?: base
    val label = when {
        ev.state == GameState.LIVE -> "● LIVE NOW"
        mine != null -> "NEXT FOR ${mine.team.name.uppercase()} · ${dayWord(ev.startMs, now).uppercase()}"
        else -> "NEXT UP · ${dayWord(ev.startMs, now).uppercase()}"
    }
    val (round, place) = remember(ev.subtitle) { splitSubtitle(ev) }
    Box(
        modifier
            .sportsFrame(focused, playing, accent, radius = 16.dp)
            .drawBehind {
                drawRect(Brush.horizontalGradient(0f to base.copy(alpha = 0.95f), 0.45f to base.copy(alpha = 0.55f), 1f to other.copy(alpha = 0.35f)), size = Size(size.width, size.height))
            }
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText(
                    label,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (ev.state == GameState.LIVE) SportsLook.Live else Color.Black.copy(alpha = 0.35f)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color.White, size = 10.sp, weight = FontWeight.Black
                )
                if (reminded) LiveText("  🔔 Reminder set", color = Color.White, size = 10.sp, weight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                EventMark(ev, league, 26.dp)
            }
            Spacer(Modifier.weight(1f))
            if (sides.size == 2) {
                val showScore = ev.hasScore && !hideScores
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeroSide(sides[0], Modifier.weight(1f, fill = false))
                    LiveText(
                        if (showScore) "  ${sides[0].score} – ${sides[1].score}  " else if (sport.homeFirst) "  v  " else "  @  ",
                        color = Color.White, size = if (showScore) 26.sp else 14.sp, weight = FontWeight.Black
                    )
                    HeroSide(sides[1], Modifier.weight(1f, fill = false))
                }
            } else if (look != null) {
                LiveText(look.headline, color = look.text, size = 28.sp, weight = FontWeight.Black, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            OneLine(
                listOfNotNull(league?.name, round, whenLabel(ev, now, use24h, hideScores), place, channel).joinToString("  ·  "),
                Color.White.copy(alpha = 0.9f), 12.sp, FontWeight.SemiBold
            )
        }
        LiveText(
            "OK: game info  ·  Hold OK: watch",
            color = Color.White.copy(alpha = 0.55f), size = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 18.dp)
        )
    }
}

@Composable
private fun HeroSide(side: Side, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(side.team, 42.dp)
        Spacer(Modifier.width(10.dp))
        LiveText(side.team.name, color = Color.White, size = 20.sp, weight = FontWeight.Black, maxLines = 1)
    }
}

// ------------------------------------------------------------------ the top bar

/** A league's own button on the top bar: its logo (or short name) and name. */
@Composable
internal fun LeagueChip(league: League, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(32.dp)
            .clip(shape)
            .background(
                when {
                    selected -> Color.White
                    focused -> SportsLook.CardRaised
                    else -> SportsLook.Card
                }
            )
            .border(if (focused) NuvioTheme.focusRing.border(2.dp) else androidx.compose.foundation.BorderStroke(1.dp, SportsLook.Border), shape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 5.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(if (selected) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            LeagueMark(league, 18.dp)
        }
        Spacer(Modifier.width(6.dp))
        LiveText(league.short.takeIf { it.length <= 8 } ?: league.name, color = if (selected) SportsLook.Ink else Color.White, size = 12.sp, weight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** The thin line between groups of buttons on the top bar. */
@Composable
internal fun ChipGap(width: Dp = 2.dp) {
    Box(Modifier.padding(horizontal = width).width(1.dp).height(20.dp).background(SportsLook.Divider))
}
