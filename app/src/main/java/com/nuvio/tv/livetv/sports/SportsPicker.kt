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

    val teams by produceState<List<TeamInfo>?>(null, league?.id) {
        value = null
        value = league?.let { if (it.espnPath != null) viewModel.teams(it) else emptyList() }.orEmpty()
    }
    // Search: every team in the leagues shown in your region (loaded as needed, then saved).
    val searchResults by produceState<List<TeamInfo>?>(null, query) {
        val q = SportsCatalog.norm(query).trim()
        if (q.length < 2) { value = null; return@produceState }
        delay(350)
        val all = ArrayList<TeamInfo>()
        for (l in viewModel.repo.activeLeagues().filter { it.espnPath != null && it.kind == LeagueKind.TEAMS }) {
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
            FollowingBar(follows, viewModel)
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
                            modifier = if (s == sports.first()) Modifier.focusRequester(firstSport) else Modifier,
                            onFocused = { if (query.isBlank()) sport = s.name },
                            onClick = { query = ""; sport = s.name }
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
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp)) {
                        items(leagues, key = { it.id }) { l ->
                            LeagueCard(
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SportsPill(
                                if (all) "★  Following every ${league.name} game" else "☆  Follow every ${league.name} game",
                                selected = all,
                                onClick = { viewModel.repo.toggleLeague(league.id) }
                            )
                            if (league.guideOnly || league.kind == LeagueKind.EVENT) {
                                SportsPill("＋  Follow a Name…", onClick = { askWord = true })
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        when {
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

@Composable
private fun FollowingBar(follows: SportsFollows, viewModel: SportsViewModel) {
    val colors = NuvioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .sportsFrame(focused = false, accent = NuvioTheme.colors.Secondary)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val total = follows.teams.size + follows.leagues.size + follows.words.size
        LiveText("FOLLOWING · $total", color = colors.TextTertiary, size = 10.sp, weight = FontWeight.ExtraBold)
        Spacer(Modifier.width(10.dp))
        if (total == 0) {
            LiveText("Nothing yet: pick a team below.", color = colors.TextSecondary, size = 12.sp)
            return@Row
        }
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(follows.teams, key = { "t" + it.key }) { t ->
                FollowPill(
                    label = t.name,
                    badge = { TeamLogo(TeamInfo(t.leagueId, t.teamId, t.name, t.name, t.abbr, null, t.color, null, t.logo), 22.dp) },
                    onClick = { viewModel.repo.updateFollows { f -> f.copy(teams = f.teams.filterNot { it.key == t.key }) } }
                )
            }
            items(follows.leagues.toList(), key = { "l$it" }) { id ->
                val l = SportsCatalog.league(id)
                FollowPill(label = "All of ${l?.name ?: id}", badge = { LeagueMark(l, 18.dp) }, onClick = { viewModel.repo.toggleLeague(id) })
            }
            items(follows.words, key = { "w$it" }) { w ->
                FollowPill(label = "\"$w\"", badge = null, onClick = { viewModel.repo.removeWord(w) })
            }
        }
        LiveText("OK on one to remove it", color = colors.TextTertiary, size = 11.sp)
    }
}

@Composable
private fun FollowPill(label: String, badge: (@Composable () -> Unit)?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
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
                    if (league.guideOnly) "From your guide" else null,
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
