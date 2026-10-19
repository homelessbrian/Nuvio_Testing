package com.nuvio.tv.livetv.sports

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.ChannelLogo
import com.nuvio.tv.livetv.ui.LivePlayerSurface
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.LiveTextField
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

    /** Live TV was opened from the Sports page (Back goes back there), and on which game. */
    @Volatile private var returnTo: String? = null
    @Volatile private var focusEvent: String? = null
    @Volatile private var returnSetAt = 0L
    fun openedFromSports(eventId: String) { returnTo = eventId; focusEvent = eventId; returnSetAt = System.currentTimeMillis() }
    fun takeReturnToSports(): Boolean = (returnTo != null).also { returnTo = null }
    fun clearReturn() { returnTo = null; focusEvent = null }
    /** Live TV closed some other way: Back there no longer goes to Sports. */
    fun dropReturn() {
        // Not while Live TV is still opening (its screens are swapped in once on the way).
        if (System.currentTimeMillis() - returnSetAt > 5_000) returnTo = null
    }
    fun takeFocusEvent(): String? = focusEvent.also { focusEvent = null }

    @Volatile private var catchup: Catchup? = null
    fun catchup(c: Catchup) { catchup = c }
    fun takeCatchup(): Catchup? = catchup.also { catchup = null }
}

private const val CHIP_MINE = "mine"
private const val CHIP_LIVE = "live"
private const val CHIP_ALL = "all"

/** Width of the game list next to the video window. */
private val LIST_WIDTH = 470.dp

/**
 * The Sports page. With the video window on (the default): the games on the left and a video
 * window on the right, where games play while you browse. With it off: the same cards, two to a
 * row across the whole width, and games open full screen.
 *
 *  - OK on a game: its game page (channels, score by period, scoring plays, follow, remind).
 *  - Hold OK on a game: watch it straight away (in the window, or full screen when it's off).
 *  - On the game page, OK on a channel plays it in the window; OK on it again goes full screen.
 *
 * The window is the same video player as Live TV's preview and full screen, so the picture
 * carries on without restarting from the list to the game page, to full screen and back.
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
    val playingKey by viewModel.playingKey.collectAsStateWithLifecycle()

    var day by rememberSaveable { mutableIntStateOf(0) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var fixFor by remember { mutableStateOf<SportsEvent?>(null) }
    // Back from a game (or Pick Your Teams): the highlight goes back to where you were.
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requesterFor(id: String) = requesters.getOrPut(id) { FocusRequester() }
    var returnTo by remember { mutableStateOf<String?>(null) }
    // Kept while a game is open, so the list is where you left it when you come back.
    val listState = rememberLazyListState()

    // ---- the video window: the shared player, shown where the page puts its slot
    val videoOn = settings.videoWindow && hasChannels
    var playerRef by remember { mutableStateOf<androidx.media3.common.Player?>(null) }
    DisposableEffect(videoOn) {
        if (videoOn) playerRef = viewModel.attachPlayer()
        onDispose { if (videoOn) { viewModel.detachPlayer(); playerRef = null } }
    }
    var slot by remember { mutableStateOf<Rect?>(null) }
    var root by remember { mutableStateOf<Rect?>(null) }
    val videoSlot: @Composable (Modifier) -> Unit = { m -> VideoSlot(m) { slot = it } }

    DisposableEffect(Unit) {
        viewModel.repo.setViewing(true)
        onDispose { viewModel.repo.setViewing(false) }
    }
    LaunchedEffect(Unit) {
        // Back from watching a game: the highlight goes back on it.
        SportsLaunch.takeFocusEvent()?.let { if (openId == null) returnTo = it }
        SportsLaunch.takeOpenEvent()?.let { openId = it }
        if (SportsLaunch.takeOpenPicker() || viewModel.isFirstVisit()) picker = true
    }

    /** Full screen in Live TV (from the start when the game is over). */
    fun watch(ev: SportsEvent, m: ChannelMatch?) {
        if (m == null) {
            LiveTvNotice.show("No channel found for ${ev.title}. Open it and use Fix Channel to pick one.")
            return
        }
        SportsLaunch.openedFromSports(ev.id)
        if (ev.state == GameState.POST) {
            if (viewModel.watchFromStart(ev, m)) onOpenLive()
            else LiveTvNotice.show("${ev.title} has finished, and ${m.channel.name} can't replay it")
            return
        }
        viewModel.watch(m)
        onOpenLive()
    }
    fun fromStart(ev: SportsEvent, m: ChannelMatch) {
        SportsLaunch.openedFromSports(ev.id)
        if (viewModel.watchFromStart(ev, m)) onOpenLive()
        else LiveTvNotice.show("${m.channel.name} can't replay this from the start")
    }
    /** A channel picked on the game page: in the window (again: full screen), or full screen. */
    fun choose(ev: SportsEvent, m: ChannelMatch) {
        when {
            !videoOn -> watch(ev, m)
            ev.state == GameState.POST -> watch(ev, m)
            playingKey == m.channel.key -> watch(ev, m)
            else -> viewModel.playHere(m)
        }
    }
    /** Hold OK on a game: its best channel, straight away. */
    fun quickWatch(ev: SportsEvent) {
        val m = matches[ev.id]?.firstOrNull()
        when {
            m == null -> LiveTvNotice.show("No channel found for ${ev.title}. Open it and use Fix Channel to pick one.")
            videoOn && ev.state != GameState.POST -> viewModel.playHere(m)
            else -> watch(ev, m)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .onGloballyPositioned { root = it.boundsInRoot() }
    ) {
        val opened = openId?.let { id -> events.firstOrNull { it.id == id } }
        if (openId != null && opened == null && events.isNotEmpty()) openId = null
        when {
            picker -> SportsPicker(viewModel = viewModel, onClose = { picker = false; returnTo = null; viewModel.repo.markSetUp() })
            opened != null -> GamePage(
                ev = opened,
                matches = matches[opened.id].orEmpty(),
                follows = follows,
                settings = settings,
                now = now,
                use24h = use24h,
                matching = matching,
                hasChannels = hasChannels,
                videoOn = videoOn,
                playingKey = playingKey,
                videoSlot = videoSlot,
                viewModel = viewModel,
                onChoose = { m -> choose(opened, m) },
                onFromStart = { m -> fromStart(opened, m) },
                onFix = { fixFor = opened },
                onClose = { returnTo = opened.id; openId = null }
            )
            else -> SportsHub(
                events = events,
                matches = matches,
                follows = follows,
                settings = settings,
                now = now,
                use24h = use24h,
                loading = loading,
                matching = matching,
                problem = problem,
                hasChannels = hasChannels,
                videoOn = videoOn,
                playingKey = playingKey,
                day = day,
                onDay = { day = it },
                chip = chip,
                onChip = { chip = it },
                listState = listState,
                requesterFor = ::requesterFor,
                returnTo = returnTo,
                onReturned = { returnTo = null },
                videoSlot = videoSlot,
                viewModel = viewModel,
                onOpen = { ev -> openId = ev.id },
                onQuickWatch = ::quickWatch,
                onPick = { picker = true }
            )
        }
        // The picture, on top of the page where its slot is (one view for every page, so it
        // never restarts), then the rounded corners and the "nothing playing" note over it.
        if (videoOn) VideoLayer(slot, root, playerRef ?: viewModel.player, playing = playingKey != null, onAttached = { viewModel.onSurfaceAttached() })
    }
    fixFor?.let { ev -> FixChannelDialog(ev, matches[ev.id].orEmpty(), settings.hideScores, viewModel) { fixFor = null } }
}

/** Where the video goes on a page: reports its place on screen, and clears it when it leaves. */
@Composable
private fun VideoSlot(modifier: Modifier, onBounds: (Rect?) -> Unit) {
    DisposableEffect(Unit) { onDispose { onBounds(null) } }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
    )
}

@Composable
private fun VideoLayer(slot: Rect?, root: Rect?, player: androidx.media3.common.Player?, playing: Boolean, onAttached: () -> Unit) {
    val density = LocalDensity.current
    val placed = slot != null && root != null && slot.width > 1f
    val place = if (placed) with(density) {
        Modifier
            .offset { IntOffset((slot!!.left - root!!.left).toInt(), (slot.top - root.top).toInt()) }
            .size(slot!!.width.toDp(), slot.height.toDp())
    } else Modifier.size(1.dp)
    LivePlayerSurface(player = player, useSurfaceView = true, modifier = place, onAttached = onAttached)
    if (!placed) return
    val bg = NuvioTheme.colors.Background
    Box(
        place.drawBehind {
            // Rounded corners: the page's colour over the picture's square corners.
            val p = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(14.dp.toPx())))
            }
            drawPath(p, bg)
        },
        contentAlignment = Alignment.Center
    ) {
        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(20.dp)) {
                LiveText("▶", color = SportsLook.Dim, size = 26.sp)
                Spacer(Modifier.height(6.dp))
                LiveText("Pick a game to watch here", color = Color.White, size = 14.sp, weight = FontWeight.Bold)
                LiveText("Hold OK on a game, or open it and pick a channel", color = SportsLook.Dim, size = 11.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ the list

@Composable
private fun SportsHub(
    events: List<SportsEvent>,
    matches: Map<String, List<ChannelMatch>>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    loading: Boolean,
    matching: Boolean,
    problem: String?,
    hasChannels: Boolean,
    videoOn: Boolean,
    playingKey: String?,
    day: Int,
    onDay: (Int) -> Unit,
    chip: String?,
    onChip: (String) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    requesterFor: (String) -> FocusRequester,
    returnTo: String?,
    onReturned: () -> Unit,
    videoSlot: @Composable (Modifier) -> Unit,
    viewModel: SportsViewModel,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit,
    onPick: () -> Unit
) {
    // ---- what this day holds
    val dayStart = viewModel.repo.dayStart(day)
    val dayEnd = viewModel.repo.dayStart(day + 1)
    val active = remember(settings, follows) { viewModel.repo.activeLeagues(settings, follows).map { it.id }.toSet() + SportsMatcher.OTHER_ID }
    // "Only games on my channels": once channels have been looked for, games on now or coming up
    // that none of them show are left out (results, your teams' games and names you follow stay).
    val filterToChannels = settings.onlyOnMyChannels && hasChannels && matches.isNotEmpty()
    val dayEvents = remember(events, dayStart, dayEnd, active, filterToChannels, matches, follows) {
        events.filter { ev ->
            ev.leagueId in active && (if (ev.hasTime) ev.startMs in dayStart until dayEnd
            else ev.startMs < dayEnd && (ev.endMs ?: (ev.startMs + 1)) >= dayStart) &&
                (!filterToChannels || ev.state == GameState.POST || isMine(ev, follows) || matches[ev.id]?.isNotEmpty() == true)
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
    // League rank as a lookup (it was a list search inside every sort comparison).
    val leagueRank = remember(settings) { SportsCatalog.ordered(settings).mapIndexed { i, l -> l.id to i }.toMap() }
    // The sections, sorted once per change of games, channels or time, not on every redraw
    // (moving the highlight redraws this screen).
    val sections = remember(shown, dayEvents, matches, follows, now, leagueRank) {
        fun rank(ev: SportsEvent) = leagueRank[ev.leagueId] ?: 999
        // Golf, tennis, a race weekend: "in progress" all week in the feed. Live Now only while one
        // of your channels is showing it; otherwise listed under This Week.
        fun airingNow(ev: SportsEvent): Boolean = matches[ev.id]?.any { m -> m.program?.let { now >= it.startMs && now < it.stopMs } == true } == true
        val weekLong = shown.filter { !it.hasTime && it.state == GameState.LIVE && !airingNow(it) }
        val weekIds = weekLong.mapTo(HashSet()) { it.id }
        val live = shown.filter { it.state == GameState.LIVE && it.id !in weekIds }
            .sortedWith(compareByDescending<SportsEvent> { isMine(it, follows) }.thenByDescending { matches[it.id]?.isNotEmpty() == true }.thenBy { rank(it) }.thenBy { it.startMs })
        val upcoming = shown.filter { it.state == GameState.PRE || it.state == GameState.OFF }
            .sortedWith(compareBy<SportsEvent> { if (it.hasTime) it.startMs else Long.MIN_VALUE }.thenByDescending { isMine(it, follows) }.thenBy { rank(it) })
        val finished = shown.filter { it.state == GameState.POST }
            .sortedWith(compareByDescending<SportsEvent> { it.startMs }.thenBy { rank(it) })
        val liveCount = dayEvents.count { it.state == GameState.LIVE && (it.hasTime || airingNow(it)) }
        DaySections(weekLong, live, upcoming, finished, liveCount)
    }
    val columns = if (videoOn) 1 else 2

    val firstContent = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val hasContent = sections.live.isNotEmpty() || sections.upcoming.isNotEmpty() || sections.finished.isNotEmpty() || sections.weekLong.isNotEmpty()
    LaunchedEffect(hasContent, day, effectiveChip) {
        delay(120)
        if (!focusedOnce) {
            focusedOnce = true
            val back = returnTo?.let { requesterFor(it) }
            onReturned()
            runCatching { (back ?: if (hasContent) firstContent else firstChip).requestFocus() }
                .onFailure { runCatching { if (hasContent) firstContent.requestFocus() else firstChip.requestFocus() }.onFailure { runCatching { firstChip.requestFocus() } } }
        }
    }

    val status = when {
        problem != null -> problem
        loading -> "Updating scores…"
        matching -> "Finding channels…"
        else -> null
    }
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText("Sports", size = 26.sp, weight = FontWeight.Black)
            Spacer(Modifier.width(14.dp))
            listOf(-1 to "Yesterday", 0 to "Today", 1 to "Tomorrow").forEach { (offset, label) ->
                DayTab(label, selected = day == offset, onClick = { onDay(offset); focusedOnce = true })
                Spacer(Modifier.width(4.dp))
            }
        }
    }
    val filters: @Composable (Modifier) -> Unit = { m ->
        LazyRow(m, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
            item(key = CHIP_MINE) { FilterIcon("★", "My Teams", effectiveChip == CHIP_MINE, Modifier.focusRequester(firstChip)) { onChip(CHIP_MINE) } }
            item(key = CHIP_LIVE) { FilterIcon("●", "Live · ${sections.liveCount}", effectiveChip == CHIP_LIVE, iconColor = SportsLook.Live) { onChip(CHIP_LIVE) } }
            item(key = CHIP_ALL) { FilterIcon("▦", "All", effectiveChip == CHIP_ALL) { onChip(CHIP_ALL) } }
            if (sportsToday.isNotEmpty()) item(key = "sep1") { Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(SportsLook.Divider)) }
            items(sportsToday, key = { it.name }) { s -> FilterIcon(s.icon, s.label, effectiveChip == s.name) { onChip(s.name) } }
            item(key = "sep2") { Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(SportsLook.Divider)) }
            item(key = "pick") { FilterIcon("✎", "Pick Your Teams", false) { onPick() } }
        }
    }
    val list: @Composable (Modifier) -> Unit = { m ->
        GameList(
            modifier = m,
            sections = sections,
            columns = columns,
            day = day,
            matches = matches,
            follows = follows,
            settings = settings,
            now = now,
            use24h = use24h,
            loading = loading && events.isEmpty(),
            hasChannels = hasChannels,
            playingKey = playingKey,
            listState = listState,
            firstContent = firstContent,
            requesterFor = requesterFor,
            emptyChip = effectiveChip,
            anyToday = dayEvents.isNotEmpty(),
            onShowAll = { onChip(CHIP_ALL) },
            onPick = onPick,
            onOpen = onOpen,
            onQuickWatch = onQuickWatch
        )
    }

    Box(Modifier.fillMaxSize()) {
    if (videoOn) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp)) {
            Column(Modifier.width(LIST_WIDTH).fillMaxHeight()) {
                header()
                Spacer(Modifier.height(8.dp))
                filters(Modifier.fillMaxWidth())
                status?.let { LiveText(it, color = if (problem != null) NuvioTheme.colors.Error else SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 2.dp)) }
                list(Modifier.weight(1f))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Spacer(Modifier.height(10.dp))
                NowPlayingPanel(events, matches, settings, now, use24h, playingKey, viewModel)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                header()
                Spacer(Modifier.weight(1f))
                status?.let { LiveText(it, color = if (problem != null) NuvioTheme.colors.Error else SportsLook.Dim, size = 11.sp, modifier = Modifier.padding(end = 10.dp)) }
                filters(Modifier.width(470.dp))
            }
            Spacer(Modifier.height(4.dp))
            list(Modifier.weight(1f))
        }
    }
    LiveText(
        if (videoOn) "OK: open the game  ·  Hold OK: watch it in the window" else "OK: open the game  ·  Hold OK: watch it",
        color = NuvioTheme.colors.TextTertiary, size = 10.sp,
        modifier = Modifier.align(Alignment.BottomStart).padding(start = 60.dp, bottom = 8.dp)
    )
    }
}

@Composable
private fun GameList(
    modifier: Modifier,
    sections: DaySections,
    columns: Int,
    day: Int,
    matches: Map<String, List<ChannelMatch>>,
    follows: SportsFollows,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    loading: Boolean,
    hasChannels: Boolean,
    playingKey: String?,
    listState: androidx.compose.foundation.lazy.LazyListState,
    firstContent: FocusRequester,
    requesterFor: (String) -> FocusRequester,
    emptyChip: String,
    anyToday: Boolean,
    onShowAll: () -> Unit,
    onPick: () -> Unit,
    onOpen: (SportsEvent) -> Unit,
    onQuickWatch: (SportsEvent) -> Unit
) {
    fun playing(ev: SportsEvent) = playingKey != null && matches[ev.id]?.any { it.channel.key == playingKey } == true
    LazyColumn(
        state = listState,
        modifier = modifier,
        // Room for the highlight's glow and bigger size at the edges.
        contentPadding = PaddingValues(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!hasChannels) item(key = "nochannels") {
            LiveText(
                "Add a playlist in Live TV settings to watch games from here. Scores and times still show.",
                color = SportsLook.Dim, size = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        if (loading) {
            item(key = "ph_label") { SectionHeader("LIVE NOW", live = true) }
            items(3, key = { "ph_$it" }) { PlaceholderCard() }
            return@LazyColumn
        }
        var firstUsed = false
        fun firstMod(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(firstContent) } else Modifier
        fun cardRows(prefix: String, list: List<SportsEvent>) {
            list.chunked(columns).forEachIndexed { ri, row ->
                val rowFirst = if (ri == 0) firstMod() else Modifier
                item(key = prefix + row.first().id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, ev ->
                            GameCard(
                                ev = ev,
                                follows = follows,
                                now = now,
                                use24h = use24h,
                                hideScores = settings.hideScores,
                                playing = playing(ev),
                                modifier = Modifier.weight(1f).then(if (i == 0) rowFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                onClick = { onOpen(ev) },
                                onLongClick = { onQuickWatch(ev) }
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (sections.live.isNotEmpty()) {
            item(key = "live_label") { SectionHeader("LIVE NOW", live = true, count = sections.live.size) }
            cardRows("live_", sections.live)
        }
        if (sections.upcoming.isNotEmpty()) {
            item(key = "up_label") { SectionHeader(if (day == 0) "COMING UP" else if (day > 0) "SCHEDULE" else "DIDN'T START", count = sections.upcoming.size) }
            sections.upcoming.chunked(columns).forEachIndexed { ri, row ->
                val rowFirst = if (ri == 0) firstMod() else Modifier
                item(key = "up_" + row.first().id) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { i, ev ->
                            val ms = matches[ev.id].orEmpty()
                            UpcomingRow(
                                ev = ev,
                                channel = ms.firstOrNull()?.channel?.name ?: ev.broadcasts.firstOrNull(),
                                follows = follows,
                                reminded = follows.reminders.any { it.eventId == ev.id },
                                use24h = use24h,
                                playing = playing(ev),
                                modifier = Modifier.weight(1f).then(if (i == 0) rowFirst else Modifier).focusRequester(requesterFor(ev.id)),
                                onClick = { onOpen(ev) },
                                onLongClick = { onQuickWatch(ev) }
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (sections.weekLong.isNotEmpty()) {
            item(key = "week_label") { SectionHeader("THIS WEEK", count = sections.weekLong.size) }
            cardRows("week_", sections.weekLong)
        }
        if (sections.finished.isNotEmpty()) {
            item(key = "done_label") { SectionHeader(if (day < 0) "RESULTS" else "FINISHED", count = sections.finished.size) }
            cardRows("done_", sections.finished)
        }
        val hasContent = sections.live.isNotEmpty() || sections.upcoming.isNotEmpty() || sections.finished.isNotEmpty() || sections.weekLong.isNotEmpty()
        if (!hasContent) item(key = "empty") {
            EmptyDay(chip = emptyChip, loading = false, anyToday = anyToday, followsEmpty = follows.isEmpty, onShowAll = onShowAll, onPick = onPick)
        }
    }
}

/**
 * Under the video window: the game that's playing (both teams on their bands with the score and
 * clock), its latest scoring, and the channel it's on. A hint when nothing's playing.
 */
@Composable
private fun NowPlayingPanel(
    events: List<SportsEvent>,
    matches: Map<String, List<ChannelMatch>>,
    settings: SportsSettings,
    now: Long,
    use24h: Boolean,
    playingKey: String?,
    viewModel: SportsViewModel
) {
    val ev = remember(playingKey, events, matches) {
        if (playingKey == null) null
        else events.filter { e -> matches[e.id]?.any { it.channel.key == playingKey } == true }
            .sortedBy { if (it.state == GameState.LIVE) 0 else if (it.state == GameState.PRE) 1 else 2 }
            .firstOrNull()
    }
    val match = ev?.let { e -> matches[e.id]?.firstOrNull { it.channel.key == playingKey } }
    if (ev == null) {
        Box(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(14.dp)) {
            LiveText(
                if (playingKey == null) "Hold OK on a game to watch it here while you browse. OK opens the game."
                else "Playing a channel that isn't showing a game from this page.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
        }
        return
    }
    ScoreboardStrip(ev, settings.hideScores, now, use24h, height = 88.dp)
    Spacer(Modifier.height(8.dp))
    var plays by remember(ev.id) { mutableStateOf<List<ScoringPlay>>(emptyList()) }
    LaunchedEffect(ev.id, ev.away?.score, ev.home?.score) {
        if (!settings.hideScores && ev.hasScore) plays = viewModel.plays(ev)
    }
    val sport = SportsCatalog.league(ev.leagueId)?.sport ?: Sport.OTHER
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 11.dp, vertical = 9.dp)) {
            LiveText("LATEST SCORING", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            if (plays.isEmpty()) LiveText(if (settings.hideScores) "Scores are hidden" else "No scoring yet", color = SportsLook.Dim, size = 10.sp)
            plays.takeLast(2).reversed().forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OneLine(listOfNotNull(p.clock, p.teamAbbr, p.text).joinToString(" · "), Color.White, 10.sp, modifier = Modifier.weight(1f))
                    if (p.awayScore != null && p.homeScore != null) {
                        LiveText(if (sport.homeFirst) " ${p.homeScore}–${p.awayScore}" else " ${p.awayScore}–${p.homeScore}", size = 10.sp, weight = FontWeight.Black)
                    }
                }
            }
        }
        Column(Modifier.width(132.dp).sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 11.dp, vertical = 9.dp)) {
            LiveText("WATCHING ON", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
            Spacer(Modifier.height(4.dp))
            OneLine(match?.channel?.name.orEmpty(), Color.White, 11.sp, FontWeight.ExtraBold)
            match?.quality?.let { LiveText(it, color = NuvioTheme.colors.Secondary, size = 9.sp, weight = FontWeight.Black) }
            matches[ev.id]?.firstOrNull { it.channel.key != playingKey }?.let {
                OneLine("Also on ${it.channel.name}", SportsLook.Dim, 9.sp)
            }
        }
    }
}

/** A game's two bands with the status column: the info under the video, the top of the game page. */
@Composable
private fun ScoreboardStrip(ev: SportsEvent, hideScores: Boolean, now: Long, use24h: Boolean, height: androidx.compose.ui.unit.Dp, big: Boolean = false) {
    val league = SportsCatalog.league(ev.leagueId)
    val sport = league?.sport ?: Sport.OTHER
    val sides = ev.ordered(sport)
    Row(Modifier.fillMaxWidth().height(height).sportsFrame(false, accent = NuvioTheme.colors.Secondary)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (sides.size == 2) {
                val showScore = ev.hasScore && !hideScores
                val lead = if (showScore) sides.maxByOrNull { it.score?.toDoubleOrNull() ?: 0.0 } else null
                val tied = showScore && sides[0].score == sides[1].score
                sides.forEach { s ->
                    TeamBand(
                        side = s, showScore = showScore, dim = showScore && !tied && s != lead, followed = false,
                        modifier = Modifier.weight(1f),
                        logo = if (big) 44.dp else 30.dp, name = if (big) 19.sp else 13.sp, score = if (big) 34.sp else 22.sp,
                        extra = listOfNotNull(s.record, if (s.home) "Home" else "Away").joinToString(" · ")
                    )
                }
            } else {
                EventBand(ev, league, sport, Modifier.fillMaxSize(), big = big)
            }
        }
        StatusColumn(ev, playing = false, now = now, use24h = use24h, hideScores = hideScores, modifier = Modifier.width(if (big) 150.dp else 124.dp))
    }
}

@Composable
private fun EmptyDay(chip: String, loading: Boolean, anyToday: Boolean, followsEmpty: Boolean, onShowAll: () -> Unit, onPick: () -> Unit) {
    Column(Modifier.padding(top = 24.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LiveText(
            when {
                loading && !anyToday -> "Getting today's games…"
                chip == CHIP_MINE && followsEmpty -> "You're not following any teams yet."
                chip == CHIP_MINE -> "Nothing from your teams on this day."
                chip == CHIP_LIVE -> "Nothing on right now."
                else -> "Nothing on this day."
            },
            size = 17.sp, weight = FontWeight.Bold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chip != CHIP_ALL && anyToday) SportsAction("See Everything", onClick = onShowAll)
            if (chip == CHIP_MINE) SportsAction("✎  Pick Your Teams", onClick = onPick)
        }
    }
}

/** A rounded pill button, in the Sports look (Pick Your Teams uses it too). */
@Composable
internal fun SportsPill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    @Suppress("UNUSED_PARAMETER") small: Boolean = false,
    onClick: () -> Unit
) = SportsAction(text, modifier, selected, onClick)

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
    videoOn: Boolean,
    playingKey: String?,
    videoSlot: @Composable (Modifier) -> Unit,
    viewModel: SportsViewModel,
    onChoose: (ChannelMatch) -> Unit,
    onFromStart: (ChannelMatch) -> Unit,
    onFix: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
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

    val top: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            LiveText("‹  Sports", color = SportsLook.Dim, size = 12.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            LiveText(listOfNotNull(league?.name, ev.subtitle?.takeIf { sides.size == 2 }).joinToString(" · "), color = SportsLook.Dim, size = 12.sp)
        }
        ScoreboardStrip(ev, settings.hideScores, now, use24h, height = if (sides.size == 2) 124.dp else 100.dp, big = true)
    }
    val channels: @Composable () -> Unit = {
        SectionHeader("WATCH ON YOUR CHANNELS")
        when {
            matches.isNotEmpty() -> LazyColumn(
                Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
            ) {
                itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                    SportsChannelRow(
                        m = m,
                        finished = ev.state == GameState.POST,
                        playing = videoOn && playingKey == m.channel.key,
                        now = now,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onChoose(m) }
                    )
                }
            }
            matching -> LiveText("Looking through your channels…", color = SportsLook.Dim, size = 12.sp)
            !hasChannels -> LiveText("Add a playlist in Live TV settings to watch from here.", color = SportsLook.Dim, size = 12.sp)
            else -> LiveText(
                "Not found in your channels yet. If you know where it's on, pick the channel with Fix Channel and it's remembered.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
            var firstSet = false
            fun f(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(firstAction) } else Modifier
            val replay = matches.firstOrNull { it.catchup }
            if (replay != null && (ev.state == GameState.LIVE || ev.state == GameState.POST)) {
                val m = f()
                item { SportsAction("⏮  From the start", modifier = m, onClick = { onFromStart(replay) }) }
            }
            if (ev.state == GameState.PRE && ev.hasTime) {
                val m = f()
                item {
                    val on = follows.reminders.any { it.eventId == ev.id }
                    SportsAction(if (on) "🔔  Reminder set" else "🔔  Remind me", modifier = m, selected = on, onClick = {
                        viewModel.repo.toggleReminder(ev)
                        LiveTvNotice.show(if (on) "Reminder cancelled" else "We'll remind you before it starts")
                    })
                }
            }
            sides.forEach { side ->
                val m = f()
                item(key = "follow_" + side.team.id) {
                    val following = follows.followsTeam(ev.leagueId, side.team.id)
                    SportsAction(
                        if (following) "★  ${side.team.name}" else "☆  Follow ${side.team.name}",
                        modifier = m, selected = following,
                        onClick = { viewModel.repo.toggleTeam(side.team) }
                    )
                }
            }
            val m = f()
            item { SportsAction(if (matches.isEmpty()) "Pick a channel" else "Fix channel", modifier = m, onClick = onFix) }
        }
    }
    val details: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (sides.size == 2 && showScore && sides.any { it.periods.isNotEmpty() }) {
                Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    LiveText(periodHeading(sport), color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    PeriodTable(sides)
                }
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.fillMaxWidth().sportsFrame(false, accent = NuvioTheme.colors.Secondary).padding(horizontal = 12.dp, vertical = 9.dp)) {
                when {
                    settings.hideScores && ev.state != GameState.PRE -> LiveText("Scores are hidden (Sports settings).", color = SportsLook.Dim, size = 12.sp)
                    plays.isNotEmpty() -> {
                        LiveText("LATEST SCORING", color = SportsLook.Dim, size = 9.sp, weight = FontWeight.Black)
                        Spacer(Modifier.height(2.dp))
                        plays.takeLast(4).reversed().forEach { p -> PlayRow(p, sport) }
                    }
                    ev.state == GameState.PRE -> {
                        LiveText(
                            SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(ev.startMs)) + " · " + startLabel(ev, use24h),
                            size = 14.sp, weight = FontWeight.Bold
                        )
                        if (ev.broadcasts.isNotEmpty()) LiveText("On TV: " + ev.broadcasts.joinToString(", "), color = SportsLook.Dim, size = 12.sp)
                    }
                    ev.state == GameState.LIVE && !ev.hasScore -> LiveText(
                        "No live score for this event. The time bar runs from the start time.",
                        color = SportsLook.Dim, size = 12.sp, maxLines = 2
                    )
                    else -> LiveText(ev.note ?: ev.subtitle ?: league?.name.orEmpty(), color = SportsLook.Dim, size = 12.sp, maxLines = 3)
                }
            }
        }
    }

    if (videoOn) {
        Row(Modifier.fillMaxSize().padding(start = 56.dp, end = 28.dp, top = 20.dp)) {
            Column(Modifier.width(LIST_WIDTH).fillMaxHeight()) {
                top()
                Spacer(Modifier.height(4.dp))
                channels()
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp)) {
                videoSlot(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                Spacer(Modifier.height(10.dp))
                details()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 36.dp, top = 20.dp)) {
            top()
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(LIST_WIDTH)) { channels() }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).padding(top = 12.dp)) { details() }
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
private fun PeriodTable(sides: List<Side>) {
    val n = sides.maxOf { it.periods.size }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            (1..n).forEach { LiveText(if (it > 9) "E$it" else "$it", color = SportsLook.Faint, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(30.dp)) }
            LiveText("T", color = SportsLook.Faint, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(30.dp))
        }
        sides.forEach { s ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(teamColor(s.team)))
                Spacer(Modifier.width(7.dp))
                LiveText(s.team.name, size = 12.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                (0 until n).forEach { i -> LiveText(s.periods.getOrNull(i) ?: "–", size = 12.sp, modifier = Modifier.width(30.dp)) }
                LiveText(s.score.orEmpty(), size = 13.sp, weight = FontWeight.Black, modifier = Modifier.width(30.dp))
            }
        }
    }
}

@Composable
private fun PlayRow(p: ScoringPlay, sport: Sport) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SportsLook.Divider))
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(p.clock, color = SportsLook.Dim, size = 10.sp, weight = FontWeight.Bold, modifier = Modifier.width(64.dp))
        p.teamAbbr?.let { LiveText("$it  ", size = 11.sp, weight = FontWeight.Black) }
        OneLine(p.text, Color.White, 11.sp, modifier = Modifier.weight(1f))
        if (p.awayScore != null && p.homeScore != null) {
            LiveText(
                if (sport.homeFirst) "  ${p.homeScore} – ${p.awayScore}" else "  ${p.awayScore} – ${p.homeScore}",
                size = 11.sp, weight = FontWeight.Black
            )
        }
    }
}

// ------------------------------------------------------------------ fixing the channel

@Composable
private fun FixChannelDialog(ev: SportsEvent, matches: List<ChannelMatch>, hideScores: Boolean, viewModel: SportsViewModel, onDismiss: () -> Unit) {
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
        SportsDialog(onDismiss = onDismiss, width = 520.dp, header = { GameHeaderBands(ev, hideScores) }) {
            LiveText("Use ${pick.name} for every ${networks.first()} game?", size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
            LiveText(
                "${ev.title} is on ${networks.joinToString(", ")}. Pick Yes and future games on that network use ${pick.name} too.",
                color = SportsLook.Dim, size = 12.sp, maxLines = 3
            )
            Spacer(Modifier.height(10.dp))
            SportsMenuRow("Yes, every ${networks.first()} game", Modifier.focusRequester(first)) {
                viewModel.repo.pickChannel(ev, pick.key, forNetworks = true)
                LiveTvNotice.show("Saved: ${networks.first()} games use ${pick.name}")
                onDismiss()
            }
            SportsMenuRow("Just this game") {
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
    SportsDialog(onDismiss = onDismiss, width = 560.dp, header = { GameHeaderBands(ev, hideScores) }) {
        LiveText("Fix the channel", size = 18.sp, weight = FontWeight.Bold)
        LiveText("Take wrong channels off the list, or pick the right one.", color = SportsLook.Dim, size = 12.sp, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(matches, key = { _, m -> m.channel.key }) { i, m ->
                ChannelLine(
                    channel = m.channel,
                    detail = "Not this channel",
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = {
                        viewModel.repo.blockChannel(ev, m.channel.key)
                        LiveTvNotice.show("${m.channel.name} won't be used for this game")
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        SportsMenuRow("Pick the right channel…") { picking = true }
        SportsMenuRow("Done") { onDismiss() }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
}

/** A channel in a Sports pop-up: logo, name, and a note on the right; white when highlighted. */
@Composable
private fun ChannelLine(channel: LiveChannel, detail: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(SportsLook.SmallShape)
            .background(if (focused) Color.White else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(channel.logo, 22.dp)
        Spacer(Modifier.width(10.dp))
        LiveText(channel.name, color = if (focused) SportsLook.Ink else Color.White, size = 14.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        LiveText(detail, color = if (focused) Color(0xFF555A66) else SportsLook.Dim, size = 11.sp)
    }
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
    SportsDialog(onDismiss = onDismiss, width = 640.dp) {
        LiveText(title, size = 18.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        LiveTextField(value = q, onValueChange = { q = it }, hint = "Type part of the channel's name", focusRequester = field)
        Spacer(Modifier.height(8.dp))
        if (q.trim().length >= 2 && list.isEmpty()) {
            LiveText("No channel names match.", color = SportsLook.Dim, size = 12.sp)
        }
        LazyColumn(Modifier.heightIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.key }) { ch ->
                ChannelLine(channel = ch, detail = ch.group, onClick = { onPick(ch) })
            }
        }
    }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
}

/** "1:00 PM" etc., also used by the picker and host. */
internal fun clock(ms: Long, use24h: Boolean): String = formatClock(ms, use24h)

/** The Sports page's sections for the day on screen. */
private class DaySections(
    val weekLong: List<SportsEvent>,
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>,
    val finished: List<SportsEvent>,
    val liveCount: Int
)
