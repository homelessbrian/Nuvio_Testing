package com.nuvio.tv.livetv.sports

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveTag
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SportsEntryPoint {
    fun sportsRepository(): SportsRepository
    fun sportsPlayback(): com.nuvio.tv.livetv.player.LiveTvPlaybackController
    fun sportsLivePrefs(): com.nuvio.tv.livetv.data.LiveTvPreferences
}

/** Opens Live TV full screen on whatever it's now playing (or about to replay). */
fun openLiveTvFullscreen(navController: NavController) {
    navController.navigate(Screen.LiveTv.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    navController.navigate(Screen.LiveTvPlayer.route) { launchSingleTop = true }
}

/**
 * Sports, wherever you are in the app: starts the background updates, "starting soon"
 * reminders for your teams and the games you picked, and scoring alerts in the corner.
 */
@Composable
fun SportsHost(navController: NavController) {
    val context = LocalContext.current
    val entry = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java) }.getOrNull()
    } ?: return
    val repo = remember { entry.sportsRepository() }
    LaunchedEffect(Unit) { repo.start() }
    // Only update while the app is in front (TV boxes keep apps alive in the background for days).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> repo.setForeground(true)
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> repo.setForeground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val settings by repo.settings.collectAsState()
    if (!settings.showInSidebar) return

    // ---- reminders
    var due by remember { mutableStateOf<Pair<SportsEvent, String>?>(null) }
    val shown = remember { HashSet<String>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            if (due != null) continue
            val s = repo.settings.value
            val f = repo.follows.value
            val now = System.currentTimeMillis()
            val lead = s.remindMinutes.coerceAtLeast(0) * 60_000L
            val events = repo.events.value
            val manual = f.reminders.mapNotNull { r -> events.firstOrNull { it.id == r.eventId } }
            val auto = if (s.remindMyTeams) events.filter { ev ->
                ev.state == GameState.PRE && ev.hasTime && ev.source == EventSource.ESPN &&
                    (ev.leagueId in f.leagues || ev.sides.any { f.followsTeam(ev.leagueId, it.team.id) })
            } else emptyList()
            val next = (manual + auto).distinctBy { it.id }.firstOrNull { ev ->
                ev.id !in shown && ev.hasTime && now >= ev.startMs - lead - 30_000 && now < ev.startMs + 10 * 60_000L
            } ?: run {
                // A reminder whose game isn't in today's feed any more: still remind at its time.
                f.reminders.firstOrNull { r -> r.eventId !in shown && events.none { it.id == r.eventId } && now >= r.startMs - lead && now < r.startMs + 10 * 60_000L }
                    ?.let { r ->
                        SportsEvent(r.eventId, SportsMatcher.OTHER_ID, r.startMs, true, GameState.PRE, r.title, null, null, null, null)
                    }
            }
            if (next != null) {
                shown += next.id
                val mins = ((next.startMs - now) / 60_000L).toInt()
                due = next to when {
                    mins <= 0 -> "Starting now"
                    mins == 1 -> "Starting in a minute"
                    else -> "Starting in $mins minutes"
                }
            }
            runCatching {
                if (f.reminders.any { it.startMs < now - 3 * 60 * 60_000L }) {
                    repo.updateFollows { it.copy(reminders = it.reminders.filter { r -> r.startMs >= now - 3 * 60 * 60_000L }) }
                }
            }
        }
    }
    due?.let { (ev, heading) ->
        ReminderDialog(
            ev = ev,
            heading = heading,
            best = repo.bestChannel(ev.id),
            onWatch = { m ->
                due = null
                repo.dropReminder(ev.id)
                entry.sportsPlayback().play(m.channel)
                openLiveTvFullscreen(navController)
            },
            onOpen = {
                due = null
                repo.dropReminder(ev.id)
                SportsLaunch.openEvent(ev.id)
                navController.navigate(Screen.Sports.route) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                }
            },
            onDismiss = {
                due = null
                repo.dropReminder(ev.id)
            }
        )
    }

    // ---- scoring alerts (never take the highlight: just a card in the corner for a few seconds)
    val alert by SportsLive.alerts.collectAsState()
    LaunchedEffect(alert) {
        val a = alert ?: return@LaunchedEffect
        delay(7_000)
        if (SportsLive.alerts.value == a) SportsLive.alerts.value = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        AnimatedVisibility(
            visible = alert != null && !settings.hideScores,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            alert?.let { ScoringAlertCard(it) }
        }
    }
}

@Composable
private fun ReminderDialog(
    ev: SportsEvent,
    heading: String,
    best: ChannelMatch?,
    onWatch: (ChannelMatch) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember(ev.id) { FocusRequester() }
    LaunchedEffect(ev.id) { delay(120_000); onDismiss() }
    LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(heading, color = NuvioTheme.colors.Secondary, size = 13.sp, weight = FontWeight.SemiBold)
        LiveText(ev.title, size = 22.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(
            listOfNotNull(SportsCatalog.league(ev.leagueId)?.name, best?.channel?.name ?: ev.broadcasts.firstOrNull()).joinToString(" · "),
            color = NuvioTheme.colors.TextSecondary, size = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (best != null) MenuItem("Watch on ${best.channel.name}", Modifier.focusRequester(first)) { onWatch(best) }
            MenuItem("Open in Sports", if (best == null) Modifier.focusRequester(first) else Modifier) { onOpen() }
            MenuItem("Dismiss") { onDismiss() }
        }
    }
    LaunchedEffect(ev.id) { delay(80); runCatching { first.requestFocus() } }
}

@Composable
private fun ScoringAlertCard(a: SportsAlert) {
    val ev = a.event
    val sides = ev.ordered(a.league.sport)
    Row(
        Modifier
            .padding(top = 28.dp, end = 32.dp)
            .background(Color(0xF00B0C10), RoundedCornerShape(14.dp))
            .border(1.dp, teamColor(a.team).copy(alpha = 0.9f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TeamBadge(a.team, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            LiveText("${a.team.name} score!", color = Color(0xFFF5C400), size = 12.sp, weight = FontWeight.ExtraBold)
            if (sides.size == 2) {
                LiveText(
                    "${sides[0].team.name} ${sides[0].score}  –  ${sides[1].score} ${sides[1].team.name}",
                    color = Color.White, size = 16.sp, weight = FontWeight.Black
                )
            }
            ev.status?.let { LiveText(it, color = Color.White.copy(alpha = 0.7f), size = 11.sp) }
        }
    }
}

/**
 * The score on Live TV's info bar, when the channel is showing a game we're sure about:
 * "CHI 24 – 17 DET · 3rd · 4:12". Nothing when Sports is off or scores are hidden.
 */
@Composable
fun SportsScoreTag(channelKey: String?) {
    if (channelKey == null) return
    val context = LocalContext.current
    val repo = remember {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, SportsEntryPoint::class.java).sportsRepository() }.getOrNull()
    } ?: return
    val settings by repo.settings.collectAsState()
    val scores by SportsLive.byChannel.collectAsState()
    if (!settings.showInSidebar || !settings.scoreOnInfoBar || settings.hideScores) return
    val ev = scores[channelKey] ?: return
    val league = SportsCatalog.league(ev.leagueId) ?: return
    val sides = ev.ordered(league.sport)
    if (sides.size != 2) return
    val text = "${sides[0].team.abbr.ifBlank { sides[0].team.name }} ${sides[0].score} – ${sides[1].score} ${sides[1].team.abbr.ifBlank { sides[1].team.name }}" +
        (ev.status?.let { " · $it" } ?: "")
    Spacer(Modifier.width(6.dp))
    LiveTag(text, Color(0xFF0B0C10).copy(alpha = 0.6f), Color.White, Modifier.border(1.dp, teamColor(sides[0].team).copy(alpha = 0.8f), RoundedCornerShape(5.dp)))
}
