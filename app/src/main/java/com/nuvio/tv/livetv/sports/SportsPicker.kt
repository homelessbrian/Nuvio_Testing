package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.TextInputDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Pick Your Teams: sport on the left, its leagues across the top, the teams below. OK follows
 * or unfollows; everything you follow sits in the bar at the top (OK there takes it off).
 * Sports that come from your guide (darts, snooker…) are followed whole, and any name can be
 * followed too ("Luke Littler"): shows that mention it come up under My Teams.
 */
@Composable
fun SportsPicker(viewModel: SportsViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val colors = NuvioTheme.colors
    val sports = remember(settings) { SportsCatalog.sportsOrder(settings) }
    var sport by rememberSaveable { mutableStateOf(sports.firstOrNull()?.name ?: Sport.FOOTBALL.name) }
    val leagues = remember(sport, settings) {
        SportsCatalog.ordered(settings).filter { it.sport.name == sport }
            .sortedByDescending { SportsCatalog.shown(it, settings) }
    }
    var leagueId by rememberSaveable(sport) { mutableStateOf(leagues.firstOrNull()?.id) }
    val league = leagues.firstOrNull { it.id == leagueId } ?: leagues.firstOrNull()
    var query by rememberSaveable { mutableStateOf("") }
    var askWord by remember { mutableStateOf(false) }
    val firstSport = remember { FocusRequester() }
    // Up from the sports list or the leagues: the Following bar (it was skipped, the highlight
    // went to "‹ Sports" instead).
    val followFirst = remember { FocusRequester() }
    val hasFollows = !follows.isEmpty
    val leagueRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val leagueReqs = remember { HashMap<String, FocusRequester>() }
    fun leagueReq(id: String) = leagueReqs.getOrPut(id) { FocusRequester() }
    // OK on a sport: the highlight goes over to its first league.
    var toLeagues by remember { mutableStateOf(0) }
    LaunchedEffect(toLeagues) {
        if (toLeagues == 0) return@LaunchedEffect
        delay(90)
        runCatching { leagueRowState.scrollToItem(0) }
        leagues.firstOrNull()?.let { runCatching { leagueReq(it.id).requestFocus() } }
    }

    val teams by produceState<List<TeamInfo>?>(null, league?.id) {
        value = null
        value = league?.let { if (it.espnPath != null || it.id.startsWith("tsdb/")) viewModel.teams(it) else emptyList() }.orEmpty()
    }
    // Search: every team in the leagues shown in your region (loaded as needed, then saved).
    val searchResults by produceState<List<TeamInfo>?>(null, query) {
        val q = SportsCatalog.norm(query).trim()
        if (q.length < 2) { value = null; return@produceState }
        delay(350)
        val all = ArrayList<TeamInfo>()
        val searchIn = (viewModel.repo.activeLeagues().filter { it.espnPath != null && it.kind == LeagueKind.TEAMS && !it.national } +
            SportsCatalog.leagues.firstOrNull { it.national }.let { listOfNotNull(it) } + SportsCatalog.extra).distinctBy { it.id }
        for (l in searchIn) {
            all += viewModel.teams(l).filter { t ->
                listOfNotNull(t.fullName, t.name, t.location, t.abbr).any { SportsCatalog.norm(it).contains(q) }
            }
            value = all.toList()
        }
        value = all.toList()
    }
    LaunchedEffect(Unit) { delay(150); runCatching { firstSport.requestFocus() } }

    Box(Modifier.fillMaxSize().background(colors.Background)) {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 44.dp, top = 26.dp)) {
            // A way back that's on screen (Back works too): "‹ Sports" on the left, Done on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SportsAction("‹  Sports", onClick = onClose)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    LiveText("Pick Your Teams", size = 26.sp, weight = FontWeight.Black)
                    LiveText(
                        "Their games show first in Sports, with a reminder before they start.",
                        color = colors.TextSecondary, size = 13.sp
                    )
                }
                Box(Modifier.width(280.dp)) {
                    LiveTextField(value = query, onValueChange = { query = it }, hint = "Search any team")
                }
                Spacer(Modifier.width(12.dp))
                SportsAction("✓  Done", selected = true, onClick = onClose)
            }
            Spacer(Modifier.height(12.dp))
            FollowingBar(follows, viewModel, firstReq = followFirst, fallback = firstSport)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxSize()) {
                // ---- sports
                LazyColumn(Modifier.width(196.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(sports, key = { it.name }) { s ->
                        val count = follows.teams.count { SportsCatalog.league(it.leagueId)?.sport == s } +
                            follows.leagues.count { SportsCatalog.league(it)?.sport == s }
                        SportRow(
                            sport = s,
                            selected = s.name == sport && query.isBlank(),
                            count = count,
                            modifier = if (s == sports.first()) Modifier.focusRequester(firstSport)
                                .focusUp(if (hasFollows) followFirst else null) else Modifier,
                            onFocused = { if (query.isBlank()) sport = s.name },
                            onClick = { query = ""; sport = s.name; toLeagues++ }
                        )
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    val results = searchResults
                    if (query.trim().length >= 2) {
                        LiveText(
                            if (results == null) "Searching…" else "${results.size} teams match \"${query.trim()}\"",
                            color = colors.TextSecondary, size = 12.sp, weight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        TeamGrid(results.orEmpty(), follows, viewModel, showLeague = true, modifier = Modifier.weight(1f))
                        return@Column
                    }
                    // ---- leagues
                    LazyRow(state = leagueRowState, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
                        items(leagues.size, key = { leagues[it].id }) { i ->
                            val l = leagues[i]
                            LeagueCard(
                                modifier = Modifier
                                    .focusRequester(leagueReq(l.id))
                                    .focusUp(if (hasFollows) followFirst else null)
                                    .steadyRowKeys(leagueRowState, i, leagues.size) { j -> leagueReq(leagues[j].id) },
                                league = l,
                                selected = l.id == league?.id,
                                followed = l.id in follows.leagues,
                                teamsFollowed = follows.teams.count { it.leagueId == l.id },
                                inRegion = SportsCatalog.shown(l, settings),
                                onFocused = { leagueId = l.id },
                                onClick = { leagueId = l.id }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (league != null) {
                        val all = league.id in follows.leagues
                        val pinned = league.id in follows.pins
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Its own row under My Teams (this week's games), without making every
                            // game one of "yours".
                            SportsPill(
                                if (pinned) "✓  Shown on My Teams" else "＋  Show on My Teams",
                                selected = pinned,
                                onClick = { viewModel.repo.togglePin(league.id) }
                            )
                            SportsPill(
                                if (all) "★  Every game is under My Teams" else "☆  Put every game under My Teams",
                                selected = all,
                                onClick = { viewModel.repo.toggleLeague(league.id) }
                            )
                            if (league.guideOnly || league.kind == LeagueKind.EVENT) {
                                SportsPill("＋  Follow a Name…", onClick = { askWord = true })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when {
                            league.sport == Sport.WRESTLING -> LiveText(
                                "${league.name}'s schedule (weekly shows and big events, with times and venues) comes from TheSportsDB, updated every day. " +
                                    "Your own TV guide is checked too, to find the channel it's on. Follow it to see every show under My Teams.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            league.guideOnly -> LiveText(
                                "${league.name} comes from your own TV guide: live shows with \"${league.guideWords.first()}\" in the title. " +
                                    "Follow it to see them under My Teams. Follow a name (a player, a team) to catch shows that mention it.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            league.kind == LeagueKind.EVENT -> LiveText(
                                "${league.name} is listed by event (each race, card or tournament). Follow it to see every one under My Teams, " +
                                    "or follow a name to catch shows that mention a driver or fighter.",
                                color = colors.TextSecondary, size = 13.sp, maxLines = 4
                            )
                            teams == null -> LiveText("Getting the teams…", color = colors.TextSecondary, size = 13.sp)
                            league.national && teams.orEmpty().isNotEmpty() -> Column(Modifier.weight(1f)) {
                                LiveText(
                                    "Countries: follow one to see all its games, from qualifiers and the Nations League to friendlies, the World Cup and the Euros.",
                                    color = colors.TextSecondary, size = 12.sp, maxLines = 2
                                )
                                Spacer(Modifier.height(8.dp))
                                TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                            }
                            league.id.startsWith("tsdb/") && teams.orEmpty().isNotEmpty() -> Column(Modifier.weight(1f)) {
                                LiveText(
                                    "Fixtures and results from TheSportsDB. On match days, scores update about every 15 minutes.",
                                    color = colors.TextSecondary, size = 12.sp, maxLines = 2
                                )
                                Spacer(Modifier.height(8.dp))
                                TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                            }
                            teams.orEmpty().isEmpty() -> LiveText("Couldn't get this league's teams. Try again in a little while.", color = colors.TextSecondary, size = 13.sp)
                            else -> TeamGrid(teams.orEmpty(), follows, viewModel, showLeague = false, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (askWord) {
        TextInputDialog(
            title = "Follow a name",
            initial = "",
            hint = "A player, driver, fighter or team",
            confirmLabel = "Follow",
            onDismiss = { askWord = false },
            onConfirm = { w ->
                askWord = false
                viewModel.repo.addWord(w)
            }
        )
    }
}

/** Something you follow, in the Following bar. */
private class FollowEntry(val key: String, val label: String, val badge: (@Composable () -> Unit)?, val remove: () -> Unit)

@Composable
private fun FollowingBar(follows: SportsFollows, viewModel: SportsViewModel, firstReq: FocusRequester, fallback: FocusRequester) {
    val colors = NuvioTheme.colors
    val entries = buildList {
        follows.teams.forEach { t ->
            add(FollowEntry("t" + t.key, t.name, { TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 22.dp) }) {
                viewModel.repo.updateFollows { f -> f.copy(teams = f.teams.filterNot { it.key == t.key }) }
            })
        }
        follows.leagues.forEach { id ->
            val l = SportsCatalog.league(id)
            add(FollowEntry("l$id", "All of ${l?.name ?: id}", { LeagueMark(l, 18.dp) }) { viewModel.repo.toggleLeague(id) })
        }
        follows.words.forEach { w -> add(FollowEntry("w$w", "\"$w\"", null) { viewModel.repo.removeWord(w) }) }
    }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    // Taking one off: the highlight moves to the one next to it (the last one: the one before).
    var refocus by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(entries.size, refocus) {
        val at = refocus ?: return@LaunchedEffect
        delay(60)
        val target = entries.getOrNull(at) ?: entries.getOrNull(at - 1)
        val ok = target != null && runCatching { req(target.key).requestFocus() }.isSuccess
        if (!ok) runCatching { fallback.requestFocus() }
        refocus = null
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .sportsFrame(focused = false, accent = NuvioTheme.colors.Secondary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveText("FOLLOWING · ${entries.size}", color = colors.TextTertiary, size = 10.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            if (entries.isEmpty()) {
                LiveText("Nothing yet: pick a team below.", color = colors.TextSecondary, size = 12.sp)
            } else {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries.size, key = { entries[it].key }) { i ->
                        val e = entries[i]
                        FollowPill(
                            label = e.label,
                            badge = e.badge,
                            modifier = Modifier.focusRequester(req(e.key)).then(if (i == 0) Modifier.focusRequester(firstReq) else Modifier),
                            onClick = { refocus = i; e.remove() }
                        )
                    }
                }
            }
        }
        if (entries.isNotEmpty()) {
            LiveText("OK on one to stop following it", color = colors.TextTertiary, size = 10.sp, modifier = Modifier.padding(start = 12.dp, top = 3.dp))
        }
    }
}

@Composable
private fun FollowPill(label: String, badge: (@Composable () -> Unit)?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color.White else SportsLook.CardRaised)
            .border(1.dp, if (focused) Color.White else SportsLook.Border, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = if (badge != null) 4.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badge?.let { it(); Spacer(Modifier.width(6.dp)) }
        LiveText(if (focused) "$label  ✕" else label, color = if (focused) Color(0xFF0B0C10) else Color.White, size = 12.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun SportRow(sport: Sport, selected: Boolean, count: Int, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Row(
        modifier
            // Holding ◀ through the leagues stops here instead of carrying on into the side menu.
            .onPreviewKeyEvent { e ->
                e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    e.key == androidx.compose.ui.input.key.Key.DirectionLeft && e.nativeKeyEvent.repeatCount > 0
            }
            .fillMaxWidth()
            .height(40.dp)
            .clip(SportsLook.SmallShape)
            .background(
                when {
                    focused -> Color.White
                    selected -> accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                }
            )
            .border(1.5.dp, if (selected && !focused) accent else Color.Transparent, SportsLook.SmallShape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(if (focused) Color(0xFFE8E9EE) else SportsLook.Card),
            contentAlignment = Alignment.Center
        ) { LiveText(sport.icon, size = 14.sp) }
        Spacer(Modifier.width(10.dp))
        LiveText(sport.label, color = if (focused) SportsLook.Ink else Color.White, size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (count > 0) SportsBadge("$count", accent, NuvioTheme.colors.OnSecondary)
    }
}

@Composable
private fun LeagueCard(
    modifier: Modifier = Modifier,
    league: League,
    selected: Boolean,
    followed: Boolean,
    teamsFollowed: Int,
    inRegion: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val accent = NuvioTheme.colors.Secondary
    Row(
        Modifier
            .padding(4.dp)
            .then(modifier)
            .height(54.dp)
            .sportsFrame(focused, playing = selected && !focused, accent = accent, radius = 12.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueMark(league, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            LiveText(league.name + if (followed) " ★" else "", color = Color.White, size = 13.sp, weight = FontWeight.ExtraBold)
            LiveText(
                listOfNotNull(
                    if (teamsFollowed > 0) "$teamsFollowed followed" else null,
                    if (league.sport == Sport.WRESTLING) "Schedule + your guide" else if (league.guideOnly) "From your guide" else null,
                    if (!inRegion) "Not on your Sports page" else null
                ).joinToString(" · ").ifBlank { if (league.kind == LeagueKind.EVENT) "Events" else "Teams" },
                color = SportsLook.Dim, size = 10.sp
            )
        }
    }
}

@Composable
private fun TeamGrid(teams: List<TeamInfo>, follows: SportsFollows, viewModel: SportsViewModel, showLeague: Boolean, modifier: Modifier = Modifier) {
    // Grouped A–Z by city / school, so long lists (134 college teams) are easy to get through.
    val groups = remember(teams) {
        teams.groupBy { (it.location ?: it.fullName).trim().firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }
            .toSortedMap()
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 40.dp, end = 8.dp, top = 4.dp)
    ) {
        groups.forEach { (letter, list) ->
            if (teams.size > 24) item(key = "h$letter", span = { GridItemSpan(maxLineSpan) }) {
                LiveText(letter.toString(), color = NuvioTheme.colors.TextTertiary, size = 11.sp, weight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
            }
            items(list, key = { it.key }) { t ->
                TeamTile(t, follows.followsTeam(t.leagueId, t.id), showLeague) {
                    viewModel.repo.toggleTeam(t)
                }
            }
        }
    }
}

/** A team in Pick Your Teams: on its band in its colour, like the game cards; ★ when followed. */
@Composable
private fun TeamTile(team: TeamInfo, followed: Boolean, showLeague: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val c = bandColor(teamColor(team))
    Box(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .sportsFrame(focused, playing = followed && !focused, accent = Color(0xFFF5C400), radius = 12.dp)
            .background(Brush.horizontalGradient(0f to c.copy(alpha = 0.92f), 0.75f to c.copy(alpha = 0.25f), 1f to c.copy(alpha = 0.05f)))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(team, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                LiveText(team.name, color = Color.White, size = 13.sp, weight = FontWeight.ExtraBold)
                LiveText(
                    listOfNotNull(
                        if (showLeague) SportsCatalog.league(team.leagueId)?.short else null,
                        team.location?.takeIf { !team.name.lowercase(Locale.ROOT).contains(it.lowercase(Locale.ROOT)) },
                        team.record
                    ).joinToString(" · "),
                    color = Color.White.copy(alpha = 0.6f), size = 10.sp
                )
            }
            if (followed) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFFF5C400)), contentAlignment = Alignment.Center) {
                    LiveText("★", color = Color.Black, size = 10.sp, weight = FontWeight.Black)
                }
            }
        }
        if (focused) {
            LiveText(
                if (followed) "FOLLOWING · OK TO UNFOLLOW" else "OK TO FOLLOW",
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).padding(vertical = 1.dp),
                color = SportsLook.Ink, size = 8.sp, weight = FontWeight.Black,
                align = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Where ↑ goes from here (null: the usual place). */
private fun Modifier.focusUp(target: FocusRequester?): Modifier =
    if (target == null) this else focusProperties { up = target }
