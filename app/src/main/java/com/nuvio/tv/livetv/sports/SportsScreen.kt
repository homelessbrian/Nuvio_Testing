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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LiveDialog
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
import com.nuvio.tv.livetv.ui.MenuItem
import com.nuvio.tv.livetv.ui.ThemeGlow
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Asks the Sports page to do something when it opens (from Settings, a reminder…). */
object SportsLaunch {
    @Volatile private var openPicker = false
    @Volatile private var openEvent: String? = null

    fun openPicker() { openPicker = true }
    fun takeOpenPicker(): Boolean = openPicker.also { openPicker = false }
    fun openEvent(id: String) { openEvent = id }
    fun takeOpenEvent(): String? = openEvent.also { openEvent = null }

    /** A replay from the start, for Live TV to start when it opens. */
    data class Catchup(val channelKey: String, val program: com.nuvio.tv.livetv.model.EpgProgram)

    @Volatile private var catchup: Catchup? = null
    fun catchup(c: Catchup) { catchup = c }
    fun takeCatchup(): Catchup? = catchup.also { catchup = null }
}

private const val CHIP_MINE = "mine"
private const val CHIP_LIVE = "live"
private const val CHIP_ALL = "all"

/**
 * The Sports page: what's on now as big team-colored cards, what's coming up by start time, and
 * results. Every game lists the channels in your own playlists that show it.
 */
@Composable
fun SportsScreen(
    /** Live TV is playing the picked channel: open it full screen. */
    onOpenLive: () -> Unit,
    viewModel: SportsViewModel = hiltViewModel()
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val matching by viewModel.matching.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val hasChannels by viewModel.hasChannels.collectAsStateWithLifecycle()

    var day by rememberSaveable { mutableIntStateOf(0) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<SportsEvent?>(null) }
    var fixFor by remember { mutableStateOf<SportsEvent?>(null) }
    // Back from a game (or Pick Your Teams): the highlight goes back to where you were.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var returnTo by remember { mutableStateOf<String?>(null) }
    // Kept while a game is open, so the list is where you left it when you come back.
    val listState = rememberLazyListState()

    DisposableEffect(Unit) {
        viewModel.repo.setViewing(true)
        onDispose { viewModel.repo.setViewing(false) }
    }
    LaunchedEffect(Unit) {
        SportsLaunch.takeOpenEvent()?.let { openId = it }
        if (SportsLaunch.takeOpenPicker() || viewModel.isFirstVisit()) picker = true
    }

    fun watch(ev: SportsEvent, m: ChannelMatch?) {
        if (m == null) {
            LiveTvNotice.show("No channel found for ${ev.title}. Use Fix Channel to pick one.")
            return
        }
        if (ev.state == GameState.POST) {
            if (viewModel.watchFromStart(ev, m)) onOpenLive()
            else LiveTvNotice.show("${ev.title} has finished, and ${m.channel.name} can't replay it")
            return
        }
        viewModel.watch(m)
        onOpenLive()
    }
    fun fromStart(ev: SportsEvent, m: ChannelMatch) {
        if (viewModel.watchFromStart(ev, m)) onOpenLive()
        else LiveTvNotice.show("${m.channel.name} can't replay this from the start")
    }

    if (picker) {
        SportsPicker(viewModel = viewModel, onClose = { picker = false; returnTo = null; viewModel.repo.markSetUp() })
        return
    }
    val opened = openId?.let { id -> events.firstOrNull { it.id == id } }
    if (openId != null && opened == null && events.isNotEmpty()) openId = null
    if (opened != null) {
        GamePage(
            ev = opened,
            matches = matches[opened.id].orEmpty(),
            follows = follows,
            settings = settings,
            now = now,
            use24h = use24h,
            matching = matching,
            hasChannels = hasChannels,
            viewModel = viewModel,
            onWatch = { m -> watch(opened, m) },
            onFromStart = { m -> fromStart(opened, m) },
            onFix = { fixFor = opened },
            onClose = { returnTo = opened.id; openId = null }
        )
        fixFor?.let { ev -> FixChannelDialog(ev, matches[ev.id].orEmpty(), viewModel) { fixFor = null } }
        return
    }

    // ---- what this day holds
    val dayStart = viewModel.repo.dayStart(day)
    val dayEnd = viewModel.repo.dayStart(day + 1)
    val active = remember(settings, follows) { viewModel.repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID }
    val dayEvents = remember(events, dayStart, active) {
        events.filter { ev ->
            ev.leagueId in active && (if (ev.hasTime) ev.startMs in dayStart until dayEnd
            else ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart)
        }
    }
    val effectiveChip = chip ?: if (follows.isEmpty) CHIP_ALL else CHIP_MINE
    val sportsToday = remember(dayEvents, settings) {
        val order = SportsCatalog.sportsOrder(settings)
        dayEvents.mapNotNull { SportsCatalog.league(it.leagueId)?.sport }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) 99 else i } }
    }
    val shown = remember(dayEvents, effectiveChip, follows) {
        when (effectiveChip) {
            CHIP_MINE -> dayEvents.filter { isMine(it, follows) }
            CHIP_LIVE -> dayEvents.filter { it.state == GameState.LIVE }
            CHIP_ALL -> dayEvents
            else -> dayEvents.filter { SportsCatalog.league(it.leagueId)?.sport?.name == effectiveChip }
        }
    }
    val leagueOrder = remember(settings) { SportsCatalog.ordered(settings).map { it.id } }
    fun rank(ev: SportsEvent) = leagueOrder.indexOf(ev.leagueId).let { if (it < 0) 999 else it }
    val live = shown.filter { it.state == GameState.LIVE }
        .sortedWith(compareByDescending<SportsEvent> { isMine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
    val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
        .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { isMine(it, follows) }.thenBy { rank(it) })
    val slots = upcoming.groupBy { if (it.hasTime) it.startMs / 60_000 else -1L }.toList()
    val finished = shown.filter { it.state == GameState.POST }
        .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
    val liveCount = dayEvents.count { it.state == GameState.LIVE }

    val colors = NuvioTheme.colors
    val firstContent = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val hasContent = live.isNotEmpty() || upcoming.isNotEmpty() || finished.isNotEmpty()
    LaunchedEffect(hasContent, day, effectiveChip) {
        delay(120)
        if (!focusedOnce) {
            focusedOnce = true
            val back = returnTo?.let { requesters[it] }
            returnTo = null
            runCatching { (back ?: if (hasContent) firstContent else firstChip).requestFocus() }
                .onFailure { runCatching { if (hasContent) firstContent.requestFocus() else firstChip.requestFocus() }.onFailure { runCatching { firstChip.requestFocus() } } }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        ThemeGlow(Modifier.fillMaxWidth().height(260.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 56.dp, end = 44.dp, top = 26.dp)
        ) {
            // ---- title, days, status
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText("Sports", size = 30.sp, weight = FontWeight.Black)
                Spacer(Modifier.width(20.dp))
                val dayName = SimpleDateFormat("EEE d", Locale.getDefault()).format(Date(viewModel.repo.dayStart(0)))
                listOf(-1 to "Yesterday", 0 to "Today · $dayName", 1 to "Tomorrow").forEach { (offset, label) ->
                    SportsPill(label, selected = day == offset, small = true, onClick = { day = offset; focusedOnce = true })
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.weight(1f))
                LiveText(
                    when {
                        problem != null -> problem.orEmpty()
                        loading -> "Updating scores…"
                        matching -> "Finding channels…"
                        else -> ""
                    },
                    color = if (problem != null) colors.Error else colors.TextSecondary, size = 12.sp
                )
                Spacer(Modifier.width(14.dp))
                SportsPill("✎  Pick Your Teams", onClick = { picker = true })
            }
            Spacer(Modifier.height(12.dp))
            // ---- chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(end = 24.dp)) {
                item(key = CHIP_MINE) {
                    SportsPill("★  My Teams", selected = effectiveChip == CHIP_MINE, modifier = Modifier.focusRequester(firstChip), onClick = { chip = CHIP_MINE })
                }
                item(key = CHIP_LIVE) {
                    SportsPill("●  On Now · $liveCount", selected = effectiveChip == CHIP_LIVE, onClick = { chip = CHIP_LIVE })
                }
                item(key = CHIP_ALL) {
                    SportsPill("All", selected = effectiveChip == CHIP_ALL, onClick = { chip = CHIP_ALL })
                }
                items(sportsToday, key = { it.name }) { s ->
                    SportsPill("${s.icon}  ${s.label}", selected = effectiveChip == s.name, onClick = { chip = s.name })
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!hasChannels) item(key = "nochannels") {
                    LiveText(
                        "Add a playlist in Live TV settings to watch games from here. Scores and times still show.",
                        color = colors.TextSecondary, size = 13.sp, modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                var firstUsed = false
                fun firstMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(firstContent) } else Modifier
                if (live.isNotEmpty()) {
                    item(key = "live_label") { SectionLabel("ON NOW", live = true, extra = if (effectiveChip == CHIP_MINE) "YOUR TEAMS" else null) }
                    val cardFirst = firstMod()
                    item(key = "live_row") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 10.dp)
                        ) {
                            itemsIndexed(live, key = { _, e -> e.id }) { i, ev ->
                                EventCard(
                                    ev = ev,
                                    matches = matches[ev.id].orEmpty(),
                                    follows = follows,
                                    now = now,
                                    use24h = use24h,
                                    hideScores = settings.hideScores,
                                    matching = matching,
                                    width = if (ev.sides.size == 2) 300.dp else 250.dp,
                                    modifier = (if (i == 0) cardFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                    onClick = { openId = ev.id },
                                    onLongClick = { menuFor = ev }
                                )
                            }
                        }
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "up_label") { SectionLabel(if (day == 0) "COMING UP" else if (day > 0) "SCHEDULE" else "DIDN'T START") }
                    slots.forEachIndexed { si, (minute, list) ->
                        val slotFirst = if (si == 0) firstMod() else Modifier
                        item(key = "slot_$minute") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Column(
                                    Modifier.width(78.dp).padding(top = 9.dp, end = 12.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    LiveText(startLabel(list.first(), use24h), size = 13.sp, weight = FontWeight.ExtraBold)
                                    if (day == 0 && list.first().hasTime) {
                                        LiveText(untilLabel(list.first().startMs, now, dayEnd), size = 10.sp, color = colors.TextSecondary)
                                    }
                                }
                                Box(
                                    Modifier
                                        .width(2.dp)
                                        .height((list.size * 46).dp)
                                        .background(Color.White.copy(alpha = 0.08f))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    list.forEachIndexed { i, ev ->
                                        EventRow(
                                            ev = ev,
                                            matches = matches[ev.id].orEmpty(),
                                            follows = follows,
                                            reminded = follows.reminders.any { it.eventId == ev.id },
                                            hideScores = settings.hideScores,
                                            modifier = (if (i == 0) slotFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                            onClick = { openId = ev.id },
                                            onLongClick = { menuFor = ev }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (finished.isNotEmpty()) {
                    item(key = "done_label") { SectionLabel(if (day < 0) "RESULTS" else "FINISHED") }
                    val doneFirst = firstMod()
                    itemsIndexed(finished, key = { _, e -> "done_" + e.id }) { i, ev ->
                        val f = if (i == 0) doneFirst else Modifier
                        Row(Modifier.fillMaxWidth().padding(start = 92.dp)) {
                            EventRow(
                                ev = ev,
                                matches = matches[ev.id].orEmpty().filter { it.catchup },
                                follows = follows,
                                reminded = false,
                                hideScores = settings.hideScores,
                                modifier = f.focusRequester(requesterFor(ev.id)),
                                onClick = { openId = ev.id },
                                onLongClick = { menuFor = ev }
                            )
                        }
                    }
                }
                if (!hasContent) item(key = "empty") {
                    EmptyDay(
                        chip = effectiveChip,
                        loading = loading,
                        anyToday = dayEvents.isNotEmpty(),
                        followsEmpty = follows.isEmpty,
                        onShowAll = { chip = CHIP_ALL },
                        onPick = { picker = true }
                    )
                }
            }
        }
        LiveText(
            "OK: open the game  ·  Hold OK: watch, remind or fix the channel",
            color = colors.TextTertiary, size = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 12.dp)
        )
    }

    menuFor?.let { ev ->
        QuickMenu(
            ev = ev,
            matches = matches[ev.id].orEmpty(),
            follows = follows,
            viewModel = viewModel,
            onWatch = { m -> menuFor = null; watch(ev, m) },
            onFromStart = { m -> menuFor = null; fromStart(ev, m) },
            onFix = { menuFor = null; fixFor = ev },
            onOpen = { menuFor = null; openId = ev.id },
            onDismiss = { menuFor = null }
        )
    }
    fixFor?.let { ev -> FixChannelDialog(ev, matches[ev.id].orEmpty(), viewModel) { fixFor = null } }
}

@Composable
private fun SectionLabel(text: String, live: Boolean = false, extra: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        if (live) {
            LiveText(
                "LIVE",
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE53935)).padding(horizontal = 6.dp, vertical = 1.dp),
                color = Color.White, size = 10.sp, weight = FontWeight.Black
            )
            Spacer(Modifier.width(8.dp))
        }
        LiveText(text, color = NuvioTheme.colors.TextSecondary, size = 11.sp, weight = FontWeight.ExtraBold)
        extra?.let {
            Spacer(Modifier.width(8.dp))
            LiveText("· $it", color = NuvioTheme.colors.TextTertiary, size = 11.sp, weight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun EmptyDay(chip: String, loading: Boolean, anyToday: Boolean, followsEmpty: Boolean, onShowAll: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.padding(top = 30.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveText(
            when {
                loading && !anyToday -> "Getting today's games…"
                chip == CHIP_MINE && followsEmpty -> "You're not following any teams yet."
                chip == CHIP_MINE -> "Nothing from your teams on this day."
                chip == CHIP_LIVE -> "Nothing on right now."
                else -> "Nothing on this day."
            },
            size = 18.sp, weight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chip != CHIP_ALL && anyToday) SportsPill("See Everything", onClick = onShowAll)
            if (chip == CHIP_MINE) SportsPill("✎  Pick Your Teams", onClick = onPick)
        }
    }
}

/** A rounded pill button (days, chips, actions): white when highlighted. */
@Composable
internal fun SportsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    small: Boolean = false,
    onFocused: () -> Unit = {},
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    LiveText(
        text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.17f)
                    else -> Color.White.copy(alpha = 0.06f)
                }
            )
            .border(1.dp, if (selected && !focused) Color.White.copy(alpha = 0.35f) else Color.Transparent, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 15.dp, vertical = if (small) 5.dp else 7.dp),
        color = when {
            focused -> Color(0xFF0B0C10)
            selected -> Color.White
            else -> Color(0xFFC9CCD6)
        },
        size = if (small) 12.sp else 13.sp,
        weight = FontWeight.Bold
    )
}

// ------------------------------------------------------------------ hold-OK menu

@Composable
private fun QuickMenu(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    follows: SportsFollows,
    viewModel: SportsViewModel,
    onWatch: (ChannelMatch) -> Unit,
    onFromStart: (ChannelMatch) -> Unit,
    onFix: () -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    val best = matches.firstOrNull()
    val league = SportsCatalog.league(ev.leagueId)
    LiveDialog(onDismiss = onDismiss, width = 480.dp) {
        LiveText(ev.title, size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
        LiveText(listOfNotNull(league?.name, ev.subtitle).joinToString(" · "), color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            var firstSet = false
            fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
            if (best != null && ev.state != GameState.POST) MenuItem("Watch on ${best.channel.name}", f()) { onWatch(best) }
            val replay = matches.firstOrNull { it.catchup }
            if (replay != null && (ev.state == GameState.LIVE || ev.state == GameState.POST)) {
                MenuItem("Watch from the start on ${replay.channel.name}", f()) { onFromStart(replay) }
            }
            if (ev.state == GameState.PRE && ev.hasTime) {
                val on = follows.reminders.any { it.eventId == ev.id }
                MenuItem(if (on) "Cancel Reminder" else "Remind Me", f()) {
                    viewModel.repo.toggleReminder(ev)
                    LiveTvNotice.show(if (on) "Reminder cancelled" else "We'll remind you before ${ev.title} starts")
                    onDismiss()
                }
            }
            ev.sides.forEach { side ->
                val following = follows.followsTeam(ev.leagueId, side.team.id)
                MenuItem(if (following) "Unfollow ${side.team.name}" else "Follow ${side.team.name}", f()) {
                    viewModel.repo.toggleTeam(side.team)
                    onDismiss()
                }
            }
            MenuItem("Fix Channel", f()) { onFix() }
            MenuItem("Open Game", f()) { onOpen() }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

// ------------------------------------------------------------------ the game page

@Composable
private fun GamePage(
    ev: SportsEvent,
    matches: List<ChannelMatch>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    matching: Boolean,
    hasChannels: Boolean,
    viewModel: SportsViewModel,
    onWatch: (ChannelMatch) -> Unit,
    onFromStart: (ChannelMatch) -> Unit,
    onFix: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val colors = NuvioTheme.colors
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    val showScore = ev.hasScore && !settings.hideScores
    val first = remember(ev.id) { FocusRequester() }
    val firstAction = remember(ev.id) { FocusRequester() }
    var plays by remember(ev.id) { mutableStateOf<List<ScoringPlay>>(emptyList()) }
    LaunchedEffect(ev.id, ev.state, ev.away?.score, ev.home?.score) {
        if (settings.hideScores) return@LaunchedEffect
        plays = viewModel.plays(ev)
    }
    LaunchedEffect(ev.id) {
        delay(120)
        runCatching { if (matches.isNotEmpty()) first.requestFocus() else firstAction.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(colors.Background)) {
        // ---- header in the teams' colors
        val left = if (sides.size == 2) teamColor(sides[0].team) else leagueColor(league)
        val right = if (sides.size == 2) teamColor(sides[1].team) else leagueColor(league)
        Box(
            Modifier
                .fillMaxWidth()
                .height(210.dp)
                .splitBackground(left, right)
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to colors.Background)))
            Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 48.dp, top = 22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ev.state == GameState.LIVE) {
                        LiveText(
                            "LIVE",
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFE53935)).padding(horizontal = 6.dp, vertical = 1.dp),
                            color = Color.White, size = 10.sp, weight = FontWeight.Black
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    LeagueTag(league)
                    Spacer(Modifier.width(8.dp))
                    LiveText(ev.subtitle.orEmpty(), color = Color.White.copy(alpha = 0.8f), size = 12.sp, weight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(16.dp))
                if (sides.size == 2) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        HeaderTeam(sides[0], follows.followsTeam(ev.leagueId, sides[0].team.id), alignEnd = false, modifier = Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(300.dp)) {
                            when {
                                showScore -> LiveText("${sides[0].score}  –  ${sides[1].score}", size = 44.sp, weight = FontWeight.Black)
                                ev.state == GameState.PRE -> LiveText(startLabel(ev, use24h), size = 34.sp, weight = FontWeight.Black)
                                else -> LiveText(if (sport.homeFirst) "v" else "at", size = 30.sp, weight = FontWeight.Black, color = Color.White.copy(alpha = 0.7f))
                            }
                            LiveText(
                                when {
                                    ev.state == GameState.PRE -> SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ev.startMs))
                                    else -> cornerStatus(ev, use24h, settings.hideScores)
                                },
                                size = 13.sp, weight = FontWeight.ExtraBold
                            )
                            ev.situation?.takeIf { !settings.hideScores }?.let { LiveText(it, size = 11.sp, color = Color.White.copy(alpha = 0.7f)) }
                        }
                        HeaderTeam(sides[1], follows.followsTeam(ev.leagueId, sides[1].team.id), alignEnd = true, modifier = Modifier.weight(1f))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SportBadge(sport, 64.dp)
                        Spacer(Modifier.width(18.dp))
                        Column {
                            LiveText(ev.title, size = 26.sp, weight = FontWeight.Black, maxLines = 2)
                            LiveText(
                                listOfNotNull(cornerStatus(ev, use24h, settings.hideScores), ev.note, ev.word?.let { "Mentions $it" }).joinToString(" · "),
                                size = 13.sp, weight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
        // ---- channels and actions on the left, the game on the right
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 48.dp, top = 214.dp)) {
            Column(Modifier.width(430.dp)) {
                LiveText("WATCH ON YOUR CHANNELS", color = colors.TextSecondary, size = 11.sp, weight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                when {
                    matches.isNotEmpty() -> LazyColumn(Modifier.heightIn(max = 210.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                            ChannelRow(
                                m = m,
                                finished = ev.state == GameState.POST,
                                modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                                onClick = { onWatch(m) }
                            )
                        }
                    }
                    matching -> LiveText("Looking through your channels…", color = colors.TextSecondary, size = 13.sp)
                    !hasChannels -> LiveText("Add a playlist in Live TV settings to watch from here.", color = colors.TextSecondary, size = 13.sp)
                    else -> LiveText(
                        "Not found in your channels yet. If you know where it's on, pick the channel with Fix Channel and it's remembered.",
                        color = colors.TextSecondary, size = 13.sp, maxLines = 3
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    var firstSet = false
                    fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(firstAction) } else Modifier
                    val replay = matches.firstOrNull { it.catchup }
                    if (replay != null && (ev.state == GameState.LIVE || ev.state == GameState.POST)) {
                        val m = f()
                        item { SportsPill("⏮  Watch from the start", modifier = m, onClick = { onFromStart(replay) }) }
                    }
                    if (ev.state == GameState.PRE && ev.hasTime) {
                        val m = f()
                        item {
                            val on = follows.reminders.any { it.eventId == ev.id }
                            SportsPill(if (on) "🔔  Reminder Set" else "🔔  Remind Me", modifier = m, selected = on, onClick = {
                                viewModel.repo.toggleReminder(ev)
                                LiveTvNotice.show(if (on) "Reminder cancelled" else "We'll remind you before it starts")
                            })
                        }
                    }
                    sides.forEach { side ->
                        val m = f()
                        item(key = "follow_" + side.team.id) {
                            val following = follows.followsTeam(ev.leagueId, side.team.id)
                            SportsPill(
                                if (following) "★  Following ${side.team.name}" else "☆  Follow ${side.team.name}",
                                modifier = m, selected = following,
                                onClick = { viewModel.repo.toggleTeam(side.team) }
                            )
                        }
                    }
                    val m = f()
                    item { SportsPill(if (matches.isEmpty()) "Pick a Channel" else "Fix Channel", modifier = m, onClick = onFix) }
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                if (sides.size == 2 && showScore && sides.any { it.periods.isNotEmpty() }) {
                    LiveText(periodHeading(sport), color = colors.TextSecondary, size = 11.sp, weight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    PeriodTable(sides)
                    Spacer(Modifier.height(14.dp))
                }
                when {
                    settings.hideScores && ev.state != GameState.PRE -> LiveText(
                        "Scores are hidden (Sports settings).", color = colors.TextSecondary, size = 13.sp
                    )
                    plays.isNotEmpty() -> {
                        LiveText("LATEST SCORING", color = colors.TextSecondary, size = 11.sp, weight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(4.dp))
                        plays.takeLast(4).reversed().forEach { p -> PlayRow(p, sport) }
                    }
                    ev.state == GameState.PRE -> {
                        LiveText(
                            "Starts ${startLabel(ev, use24h)}" + (untilLabel(ev.startMs, now, Long.MAX_VALUE).takeIf { it.isNotBlank() && it != "Later today" }?.let { " · $it" } ?: ""),
                            size = 15.sp, weight = FontWeight.Bold
                        )
                        if (ev.broadcasts.isNotEmpty()) LiveText("On TV: " + ev.broadcasts.joinToString(", "), color = colors.TextSecondary, size = 13.sp)
                    }
                    ev.state == GameState.LIVE && !ev.hasScore -> LiveText(
                        "No live score for this event. The time bar runs from the start time.",
                        color = colors.TextSecondary, size = 13.sp, maxLines = 2
                    )
                }
                if (matches.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    LiveText("WHY THESE CHANNELS", color = colors.TextTertiary, size = 10.sp, weight = FontWeight.ExtraBold)
                    matches.take(3).forEach { m ->
                        LiveText("${m.channel.name}: ${m.how}", color = colors.TextTertiary, size = 11.sp)
                    }
                }
            }
        }
    }
}

private fun periodHeading(sport: Sport): String = when (sport) {
    Sport.BASEBALL -> "BY INNING"
    Sport.HOCKEY -> "BY PERIOD"
    Sport.FOOTBALL, Sport.RUGBY -> "BY HALF"
    Sport.AMERICAN_FOOTBALL -> "BY QUARTER"
    else -> "BY PERIOD"
}

@Composable
private fun HeaderTeam(side: Side, followed: Boolean, alignEnd: Boolean, modifier: Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        if (!alignEnd) { TeamBadge(side.team, 64.dp); Spacer(Modifier.width(14.dp)) }
        Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
            LiveText(side.team.name + if (followed) " ★" else "", size = 24.sp, weight = FontWeight.Black)
            LiveText(
                listOfNotNull(side.record, if (side.home) "Home" else "Away").joinToString(" · "),
                size = 12.sp, color = Color.White.copy(alpha = 0.7f), weight = FontWeight.SemiBold
            )
        }
        if (alignEnd) { Spacer(Modifier.width(14.dp)); TeamBadge(side.team, 64.dp) }
    }
}

@Composable
private fun PeriodTable(sides: List<Side>) {
    val n = sides.maxOf { it.periods.size }
    val colors = NuvioTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(140.dp))
            (1..n).forEach { LiveText(if (it > 9) "E$it" else "$it", color = colors.TextTertiary, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.width(38.dp)) }
            LiveText("T", color = colors.TextTertiary, size = 11.sp, weight = FontWeight.Bold, modifier = Modifier.width(38.dp))
        }
        sides.forEach { s ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(teamColor(s.team)))
                Spacer(Modifier.width(8.dp))
                LiveText(s.team.name, size = 13.sp, weight = FontWeight.Bold, modifier = Modifier.width(124.dp))
                (0 until n).forEach { i -> LiveText(s.periods.getOrNull(i) ?: "–", size = 13.sp, modifier = Modifier.width(38.dp)) }
                LiveText(s.score.orEmpty(), size = 14.sp, weight = FontWeight.Black, modifier = Modifier.width(38.dp))
            }
        }
    }
}

@Composable
private fun PlayRow(p: ScoringPlay, sport: Sport) {
    val colors = NuvioTheme.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(p.clock, color = colors.TextTertiary, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(70.dp))
        p.teamAbbr?.let { LiveText("$it  ", size = 12.sp, weight = FontWeight.Black) }
        LiveText(p.text, size = 12.sp, modifier = Modifier.weight(1f))
        if (p.awayScore != null && p.homeScore != null) {
            LiveText(
                if (sport.homeFirst) "${p.homeScore} – ${p.awayScore}" else "${p.awayScore} – ${p.homeScore}",
                size = 12.sp, weight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun ChannelRow(m: ChannelMatch, finished: Boolean, modifier: Modifier, onClick: () -> Unit) {
    LiveFocusRow(modifier = modifier.fillMaxWidth(), onClick = onClick) { f ->
        ChannelLogo(m.channel.logo, 26.dp)
        Column(Modifier.weight(1f)) {
            LiveText(m.channel.name, color = focusedTextColor(f), size = 14.sp, weight = FontWeight.Bold)
            LiveText(
                listOfNotNull(
                    m.program?.title?.takeIf { it != m.channel.name } ?: m.channel.group,
                    if (finished) (if (m.catchup) "Replay from the start" else "No replay") else null,
                    if (!m.sure && m.score < 150) "Might be showing it" else null
                ).joinToString(" · "),
                color = if (f) focusedTextColor(true).copy(alpha = 0.75f) else NuvioTheme.colors.TextSecondary,
                size = 11.sp
            )
        }
        m.quality?.let { q ->
            LiveText(
                q,
                modifier = Modifier.border(1.dp, if (f) focusedTextColor(true) else Color(0x992BC4E6), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                color = if (f) focusedTextColor(true) else Color(0xFF2BC4E6), size = 10.sp, weight = FontWeight.Black
            )
        }
    }
}

// ------------------------------------------------------------------ fixing the channel

@Composable
private fun FixChannelDialog(ev: SportsEvent, matches: List<ChannelMatch>, viewModel: SportsViewModel, onDismiss: () -> Unit) {
    var picking by remember { mutableStateOf(matches.isEmpty()) }
    var chosen by remember { mutableStateOf<LiveChannel?>(null) }
    val pick = chosen
    if (pick != null) {
        val networks = ev.broadcasts
        if (networks.isEmpty()) {
            LaunchedEffect(pick.key) {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = false)
                LiveTvNotice.show("Saved: ${ev.title} on ${pick.name}")
                onDismiss()
            }
            return
        }
        val first = remember { FocusRequester() }
        LiveDialog(onDismiss = onDismiss, width = 520.dp) {
            LiveText("Use ${pick.name} for every ${networks.first()} game?", size = 19.sp, weight = FontWeight.Bold, maxLines = 2)
            LiveText(
                "${ev.title} is on ${networks.joinToString(", ")}. Pick Yes and future games on that network use ${pick.name} too.",
                color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 3
            )
            Spacer(Modifier.height(12.dp))
            MenuItem("Yes, every ${networks.first()} game", Modifier.focusRequester(first)) {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = true)
                LiveTvNotice.show("Saved: ${networks.first()} games use ${pick.name}")
                onDismiss()
            }
            MenuItem("Just this game") {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = false)
                LiveTvNotice.show("Saved: ${ev.title} on ${pick.name}")
                onDismiss()
            }
        }
        LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
        return
    }
    if (picking) {
        ChannelPickerDialog(
            title = "Pick the channel for ${ev.title}",
            initial = "",
            viewModel = viewModel,
            onPick = { chosen = it },
            onDismiss = onDismiss
        )
        return
    }
    val first = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 560.dp) {
        LiveText("Fix the channel", size = 20.sp, weight = FontWeight.Bold)
        LiveText("${ev.title}: take wrong channels off the list, or pick the right one.", color = NuvioTheme.colors.TextSecondary, size = 13.sp, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                LiveFocusRow(
                    modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                    onClick = {
                        viewModel.repo.blockChannel(ev, m.channel.key)
                        LiveTvNotice.show("${m.channel.name} won't be used for this game")
                    }
                ) { f ->
                    ChannelLogo(m.channel.logo, 22.dp)
                    LiveText(m.channel.name, color = focusedTextColor(f), size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    LiveText("Not this channel", color = if (f) focusedTextColor(true) else NuvioTheme.colors.TextSecondary, size = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        MenuItem("Pick the right channel…") { picking = true }
        MenuItem("Done") { onDismiss() }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** Type part of a channel's name; OK on one picks it. */
@Composable
private fun ChannelPickerDialog(
    title: String,
    initial: String,
    viewModel: SportsViewModel,
    onPick: (LiveChannel) -> Unit,
    onDismiss: () -> Unit
) {
    var q by remember { mutableStateOf(initial) }
    val all = remember { viewModel.repo.channelsForPicker() }
    val list = remember(q, all) {
        val n = SportsCatalog.norm(q).trim()
        if (n.length < 2) emptyList()
        else all.asSequence().filter { SportsCatalog.norm(it.name).contains(n) }.take(80).toList()
    }
    val field = remember { FocusRequester() }
    LiveDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText(title, size = 19.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        LiveTextField(value = q, onValueChange = { q = it }, hint = "Type part of the channel's name", focusRequester = field)
        Spacer(Modifier.height(8.dp))
        if (q.trim().length >= 2 && list.isEmpty()) {
            LiveText("No channel names match.", color = NuvioTheme.colors.TextSecondary, size = 13.sp)
        }
        LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.key }) { ch ->
                LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = { onPick(ch) }) { f ->
                    ChannelLogo(ch.logo, 22.dp)
                    LiveText(ch.name, color = focusedTextColor(f), size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    LiveText(ch.group, color = if (f) focusedTextColor(true) else NuvioTheme.colors.TextSecondary, size = 11.sp)
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
}

/** "1:00 PM" etc., also used by the picker and host. */
internal fun clock(ms: Long, use24h: Boolean): String = formatClock(ms, use24h)
