package com.nuvio.tv.livetv.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LiveTag
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class CalTab { UPCOMING, RECENT, AIRING, FOLLOWING, HIDDEN }
private enum class CalKind { ALL, SHOWS, MOVIES, SPORTS, ON_TV }
private enum class AirFilter { ALL, PRIME, STREAMING, FOLLOWING }

private val CARD_W = 208.dp
private const val HERO_H = 236

/** Called when the card Back returns to (the first one, or today) gets the focus. */
private val LocalAtHome = staticCompositionLocalOf<() -> Unit> { {} }

/** Marks the card Back returns to. */
@Composable
private fun Modifier.homeMark(): Modifier {
    val mark = LocalAtHome.current
    return this.onFocusChanged { if (it.isFocused) mark() }
}

/**
 * The Calendar: your shows' and movies' release dates as rows of big cards by day (or a week,
 * or a month), what's airing today, and the shows you follow. The highlighted entry fills the
 * top, like Nuvio's Home. Live TV comes first: OK on an episode in your guide opens its Live TV
 * options (watch, from the start, record it or every new episode, remind, channel).
 */
@Composable
fun CalendarScreen(
    /** Opens a show's or movie's page (at the episode, when there is one). */
    onOpenDetail: (id: String, type: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit,
    /** Finds sources for an episode that's out. */
    onFindSources: (CalendarEntry) -> Unit,
    /** Live TV is tuned: open it full screen. */
    onOpenLive: () -> Unit,
    /** Opens a game on the Sports page. */
    onOpenGame: (eventId: String) -> Unit = {},
    /** Plays a finished recording (address, title, poster, recording id). */
    onPlayRecording: (url: String, title: String, poster: String?, recordingId: String) -> Unit = { _, _, _, _ -> },
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    // Anything Live TV changes (recordings, plans, reminders, picks) redraws the badges and menus.
    viewModel.schedules.collectAsStateWithLifecycle().value
    viewModel.channelPicks.collectAsStateWithLifecycle().value
    viewModel.plans.collectAsStateWithLifecycle().value
    viewModel.reminders.collectAsStateWithLifecycle().value
    viewModel.recordings.collectAsStateWithLifecycle().value

    var tab by rememberSaveable { mutableStateOf(CalTab.UPCOMING) }
    var kind by rememberSaveable { mutableStateOf(CalKind.ALL) }
    var airFilter by rememberSaveable { mutableStateOf(AirFilter.ALL) }
    val view = settings.calendarView.takeIf { it == "WEEK" || it == "MONTH" } ?: "LIST"
    val grid = view != "LIST"
    var heroKey by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<CalendarEntry?>(null) }
    var liveCard by remember { mutableStateOf<CalendarEntry?>(null) }
    var followOptions by remember { mutableStateOf<CalendarFollow?>(null) }
    var picker by remember { mutableStateOf<Pair<CalendarEntry, Boolean>?>(null) }
    var channelFor by remember { mutableStateOf<CalendarEntry?>(null) }
    val firstItem = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Back: deep in the cards, back to the first one (or today); from there, the side menu.
    var resetKey by remember { mutableStateOf(0) }
    var atHome by remember { mutableStateOf(true) }
    var inContent by remember { mutableStateOf(false) }
    val openSidebar = com.nuvio.tv.LocalOpenSidebar.current
    val sidebarOpen = com.nuvio.tv.LocalSidebarExpanded.current
    BackHandler(enabled = !sidebarOpen) {
        if (inContent && !atHome) { resetKey++; atHome = true } else openSidebar()
    }

    LaunchedEffect(tab) { if (tab == CalTab.AIRING) viewModel.loadAiring() }

    val today = LocalDate.now()
    val all = state.entries
    val filtered = all.filter {
        when (kind) {
            CalKind.ALL -> true
            CalKind.SHOWS -> it.kind == CalendarKind.EPISODE
            CalKind.MOVIES -> it.kind == CalendarKind.MOVIE
            CalKind.SPORTS -> it.kind == CalendarKind.GAME
            CalKind.ON_TV -> it.guide != null || viewModel.status(it).let { st -> st.waitRecord || st.waitRemind || st.series || st.recordedId != null }
        }
    }
    val upcoming = filtered.filter { !it.day.isBefore(today) && (it.day == today || whenOf(it) + (it.runtimeMin ?: 30) * 60_000L > now) }
    val recent = filtered.filter { !it.day.isAfter(today) && whenOf(it) <= now && !(settings.calendarHideWatched && it.watched) }
        .sortedWith(compareByDescending<CalendarEntry> { it.day }.thenByDescending { it.airMs ?: 0L })
    val airingShown = airing.entries.filter {
        when (airFilter) {
            AirFilter.ALL -> true
            AirFilter.PRIME -> it.airMs?.let { ms -> java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).hour in 19..22 } == true
            AirFilter.STREAMING -> it.streaming
            AirFilter.FOLLOWING -> it.followed || follows.any { f -> f.id == it.contentId || f.imdbId == it.contentId }
        }
    }
    val pool = when (tab) {
        CalTab.UPCOMING -> upcoming
        CalTab.RECENT -> recent
        CalTab.AIRING -> airingShown
        else -> emptyList()
    }
    // The entry shown big at the top: the one highlighted, else the next thing coming.
    val hero = pool.firstOrNull { it.key == heroKey } ?: pool.firstOrNull()

    fun channels(e: CalendarEntry) = viewModel.repo.channelsOf(e)
    fun isFollowed(e: CalendarEntry) = follows.any { it.id == e.contentId || it.imdbId == e.contentId || it.id == e.key }
    fun page(e: CalendarEntry) {
        if (e.kind == CalendarKind.GAME) { e.eventId?.let(onOpenGame); return }
        if (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")) return
        val today2 = e.key.startsWith("today|")
        onOpenDetail(e.contentId, e.type, e.addonBaseUrl, if (today2) null else e.season, if (today2) null else e.episode)
    }
    /** OK: an episode or movie airing in your guide opens its Live TV options (unless set to open the page). */
    fun open(e: CalendarEntry) {
        if (e.kind == CalendarKind.GAME) { page(e); return }
        val inGuide = channels(e).any { it.stopMs > now || it.catchup }
        val tvFirst = settings.calendarOkAction != "PAGE"
        if (inGuide && (tvFirst || (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")))) liveCard = e else page(e)
    }
    /**
     * Watch live / record. Looks in the guide again first (a guide that finished loading since
     * counts). Then the channel saved for the show or its network; watching with only one
     * channel showing it goes straight there. Recording with nothing saved (or [forceList], hold
     * OK) opens the channel list, so you always see which channel it is.
     */
    fun act(e0: CalendarEntry, record: Boolean, forceList: Boolean = false) {
        scope.launch {
            val e = viewModel.fresh(e0)
            if (forceList) { picker = e to record; return@launch }
            val opts = channels(e)
            val t = System.currentTimeMillis()
            if (record) opts.firstOrNull { viewModel.repo.isRecording(it) }?.let { viewModel.record(e, it); return@launch }
            val usable = if (!record) opts.filter { t in it.startMs until it.stopMs } else opts.filter { it.stopMs > t }
            val saved = opts.firstOrNull { it.mine }?.channelKey ?: viewModel.repo.savedKey(e)
            val direct = usable.firstOrNull { it.channelKey == saved } ?: if (!record) usable.singleOrNull() else null
            if (direct == null) { picker = e to record; return@launch }
            if (record) viewModel.record(e, direct)
            else if (viewModel.watchLive(direct)) onOpenLive()
        }
    }
    fun playRecording(e: CalendarEntry, id: String) {
        val (url, title) = viewModel.recordingToPlay(id) ?: run { LiveTvNotice.show("That recording isn't there any more"); return }
        onPlayRecording(url, title, e.poster, id)
    }
    fun handlers(e: CalendarEntry, close: () -> Unit): LiveMenuHandlers {
        val st = viewModel.status(e)
        val mine = viewModel.repo.showChannel(e.contentId)?.let { k -> viewModel.repo.channelsForPicker().firstOrNull { it.key == k }?.name }
        return LiveMenuHandlers(
            watch = { close(); act(e, record = false) },
            fromStart = {
                close()
                val replay = channels(e).firstOrNull { it.catchup && it.startMs <= now }
                if (replay != null && viewModel.watchFromStart(e, replay)) onOpenLive()
                else LiveTvNotice.show("This channel can't replay it from the start")
            },
            record = { close(); act(e, record = true) },
            series = { close(); viewModel.toggleSeries(e) },
            remind = { close(); viewModel.repo.bestHit(e)?.let { viewModel.toggleReminder(e, it) } },
            choose = { close(); act(e, record = channels(e).none { now in it.startMs until it.stopMs }, forceList = true) },
            waitRecord = { close(); viewModel.toggleWait(e, CalendarPlan.RECORD) },
            waitRemind = { close(); viewModel.toggleWait(e, CalendarPlan.REMIND) },
            playRecording = st.recordedId?.let { id -> { close(); playRecording(e, id) } },
            setChannel = { close(); channelFor = e },
            myChannel = mine
        )
    }
    fun liveList(e: CalendarEntry, close: () -> Unit): List<MenuAction> = liveActions(
        e, viewModel.status(e), channels(e), viewModel.repo.bestHit(e), now, use24h,
        viewModel.repo.canWaitForGuide(e), handlers(e, close)
    )

    Box(Modifier.fillMaxSize().guideBackdrop(settings.guideBackdrop)) {
        val showHero = when (tab) {
            CalTab.UPCOMING, CalTab.RECENT -> !grid
            CalTab.AIRING -> true
            else -> false
        }
        if (showHero) HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- the big picture
            if (showHero) {
                val st = hero?.let { viewModel.status(it) } ?: CalendarLiveStatus()
                Hero(
                    e = hero,
                    st = st,
                    now = now,
                    use24h = use24h,
                    followed = hero?.let { isFollowed(it) } == true,
                    channelCount = hero?.let { channels(it).size } ?: 0,
                    canWait = hero?.let { viewModel.repo.canWaitForGuide(it) } == true,
                    loading = (state.loading && state.entries.isEmpty()) || (tab == CalTab.AIRING && airing.loading && airing.entries.isEmpty()),
                    onRecord = { hero?.let { act(it, record = true) } },
                    onChoose = { record -> hero?.let { act(it, record = record, forceList = true) } },
                    label = { viewModel.repo.channelLabel(it) },
                    onWait = { hero?.let { viewModel.toggleWait(it, CalendarPlan.RECORD) } },
                    onWatch = { hero?.let { act(it, record = false) } },
                    onLiveOptions = { liveCard = hero },
                    onOpen = { hero?.let(::page) },
                    onBell = { hero?.let { viewModel.toggleFollow(it) } }
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Row(Modifier.padding(start = 64.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiveText(
                        when (tab) { CalTab.FOLLOWING -> "Following"; CalTab.HIDDEN -> "Hidden shows"; else -> "Calendar" },
                        size = 28.sp, weight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(14.dp))
                    LiveText(
                        when {
                            tab == CalTab.FOLLOWING -> "${follows.distinctBy { it.id }.size} with the bell on"
                            tab == CalTab.HIDDEN -> "Left off your Calendar"
                            state.loading -> state.progress?.let { (a, b) -> "Looking up your shows… $a of $b" } ?: "Looking up your shows…"
                            else -> "Times in your time zone"
                        },
                        size = 13.sp, color = NuvioTheme.colors.TextSecondary
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            // ---------------------------------------------------------------- tabs and filters
            Row(
                Modifier.padding(start = 64.dp, end = 32.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                fun tabMod(on: Boolean) = if (on) Modifier.focusRequester(tabFocus) else Modifier
                if (grid) {
                    val mine = tab == CalTab.UPCOMING || tab == CalTab.RECENT
                    Pill("My calendar", mine, tabMod(mine)) { tab = CalTab.UPCOMING }
                } else {
                    Pill("Upcoming", tab == CalTab.UPCOMING, tabMod(tab == CalTab.UPCOMING)) { tab = CalTab.UPCOMING; heroKey = null }
                    Pill("Recently aired", tab == CalTab.RECENT, tabMod(tab == CalTab.RECENT)) { tab = CalTab.RECENT; heroKey = null }
                }
                Pill("Airing today", tab == CalTab.AIRING, tabMod(tab == CalTab.AIRING)) { tab = CalTab.AIRING; heroKey = null }
                Pill("Following", tab == CalTab.FOLLOWING, tabMod(tab == CalTab.FOLLOWING)) { tab = CalTab.FOLLOWING }
                if (hidden.isNotEmpty() || tab == CalTab.HIDDEN) Pill("Hidden", tab == CalTab.HIDDEN, tabMod(tab == CalTab.HIDDEN)) { tab = CalTab.HIDDEN }
                Divider()
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> {
                        Pill("All", kind == CalKind.ALL) { kind = CalKind.ALL; heroKey = null }
                        Pill("Shows", kind == CalKind.SHOWS) { kind = CalKind.SHOWS; heroKey = null }
                        Pill("Movies", kind == CalKind.MOVIES) { kind = CalKind.MOVIES; heroKey = null }
                        if (kind == CalKind.SPORTS || all.any { it.kind == CalendarKind.GAME }) Pill("Sports", kind == CalKind.SPORTS) { kind = CalKind.SPORTS; heroKey = null }
                        Pill("On my TV", kind == CalKind.ON_TV) { kind = CalKind.ON_TV; heroKey = null }
                        if (tab == CalTab.RECENT && !grid) {
                            Divider()
                            SwitchPill("Unwatched only", settings.calendarHideWatched) { viewModel.setHideWatched(!settings.calendarHideWatched); heroKey = null }
                        }
                    }
                    CalTab.AIRING -> {
                        Pill("All", airFilter == AirFilter.ALL) { airFilter = AirFilter.ALL; heroKey = null }
                        Pill("Prime time", airFilter == AirFilter.PRIME) { airFilter = AirFilter.PRIME; heroKey = null }
                        Pill("Streaming", airFilter == AirFilter.STREAMING) { airFilter = AirFilter.STREAMING; heroKey = null }
                        Pill("Following", airFilter == AirFilter.FOLLOWING) { airFilter = AirFilter.FOLLOWING; heroKey = null }
                        Divider()
                        // A switch, not a filter: news and talk shows in the list, or left out.
                        SwitchPill("News & talk shows", !settings.calendarSkipNewsTalk) { viewModel.setSkipNewsTalk(!settings.calendarSkipNewsTalk) }
                    }
                    else -> Unit
                }
                Spacer(Modifier.weight(1f))
                if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
                    Segmented(listOf("LIST" to "List", "WEEK" to "Week", "MONTH" to "Month"), view) { v ->
                        viewModel.setView(v); heroKey = null
                        if (v != "LIST") tab = CalTab.UPCOMING
                    }
                    Spacer(Modifier.width(4.dp))
                }
                IconPill { if (tab == CalTab.AIRING) viewModel.loadAiring(force = true) else viewModel.refresh() }
            }
            // ---------------------------------------------------------------- what's on
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .onFocusChanged { inContent = it.hasFocus }
                    // Any move among the cards leaves "home" (landing back on it marks it again).
                    .onPreviewKeyEvent { k ->
                        if (k.type == KeyEventType.KeyDown) {
                            when (k.nativeKeyEvent.keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP,
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> atHome = false
                            }
                        }
                        false
                    }
            ) { CompositionLocalProvider(LocalAtHome provides { atHome = true }) {
                val onFocus: (CalendarEntry) -> Unit = { heroKey = it.key }
                val status: (CalendarEntry) -> CalendarLiveStatus = { viewModel.status(it) }
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> when {
                        view == "MONTH" -> MonthView(
                            entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey,
                            status = status, onOpen = ::open, onOptions = { options = it }
                        )
                        view == "WEEK" -> WeekView(
                            entries = filtered, use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey,
                            status = status, onOpen = ::open, onOptions = { options = it }
                        )
                        pool.isEmpty() -> EmptyNote(
                            when {
                                state.loading && state.entries.isEmpty() -> "Looking up your shows…"
                                state.showCount == 0 -> "Nothing here yet. Shows you watch, your Library and shows you ring the bell on show up here with their new episodes."
                                kind == CalKind.ON_TV -> "None of your shows are in your Live TV guide right now. Your guide usually covers the next few days."
                                tab == CalTab.UPCOMING -> "Nothing coming up in the next four months."
                                settings.calendarHideWatched -> "You're all caught up."
                                else -> "Nothing aired in the last month."
                            }
                        )
                        else -> DayRows(
                            entries = pool,
                            newestFirst = tab == CalTab.RECENT,
                            use24h = use24h, now = now, firstFocus = firstItem, reset = resetKey,
                            status = status,
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.AIRING -> when {
                        airing.problem != null && airing.entries.isEmpty() -> EmptyNote(airing.problem!!)
                        airing.entries.isEmpty() -> EmptyNote(if (airing.loading) "Getting today's shows…" else "No new episodes found for today.")
                        airingShown.isEmpty() -> EmptyNote("Nothing here with this filter.")
                        else -> PosterGrid(
                            entries = airingShown, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                            isFollowed = ::isFollowed, status = status,
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.FOLLOWING -> {
                        if (follows.isEmpty()) EmptyNote("No bells rung yet. Ring the bell on a show's page, in Live TV or in Search (hold OK) to follow it.")
                        else FollowingGrid(
                            follows = follows.distinctBy { it.id }, entries = all, now = now, use24h = use24h, firstFocus = firstItem, reset = resetKey,
                            seriesOn = { f -> viewModel.plans.value.any { it.kind == CalendarPlan.SERIES && (it.contentId == f.id || it.contentId == f.imdbId) } },
                            onOpen = { f ->
                                if (!f.isAiring) onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                                else f.imdbId?.let { onOpenDetail(it, "movie", null, null, null) }
                            },
                            onOptions = { followOptions = it }
                        )
                    }
                    CalTab.HIDDEN -> {
                        if (hidden.isEmpty()) EmptyNote("No hidden shows.")
                        else LazyColumn(
                            Modifier.padding(start = 64.dp, end = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(bottom = 30.dp)
                        ) {
                            val list = hidden.distinctBy { it.id }
                            items(list, key = { it.id }) { h ->
                                LiveFocusRow(
                                    modifier = Modifier.fillMaxWidth().then(if (h == list.first()) Modifier.focusRequester(firstItem).homeMark() else Modifier),
                                    onClick = { viewModel.unhide(h) }
                                ) { focused ->
                                    LiveText(h.name.ifBlank { h.id }, size = 17.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused), modifier = Modifier.weight(1f))
                                    LiveText("OK: show it on the Calendar again", size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            } }
        }

        // First open: straight onto the cards.
        LaunchedEffect(Unit) {
            delay(300)
            if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
        }

        // OK on an episode in your guide: its Live TV options.
        liveCard?.let { e ->
            val close = { liveCard = null }
            CalendarMenu(
                kicker = "LIVE TV",
                title = e.title,
                subtitle = entryLine(e, use24h),
                status = viewModel.status(e),
                actions = liveList(e, close) + MenuAction(
                    if (e.kind == CalendarKind.MOVIE) "Open movie page" else "Show page",
                    if (e.kind == CalendarKind.EPISODE) "Episodes, and your sources to watch on demand" else null
                ) { close(); page(e) },
                onDismiss = close
            )
        }
        // Hold OK: everything for it.
        options?.let { e ->
            val close = { options = null }
            val fromAiringToday = e.key.startsWith("today|")
            val airingFollow = e.key.startsWith("air|")
            val followed = isFollowed(e)
            val extra = buildList {
                if (e.kind == CalendarKind.GAME) {
                    add(MenuAction("Open in Sports") { close(); page(e) })
                    return@buildList
                }
                if (e.kind == CalendarKind.EPISODE && e.videoId != null && whenOf(e) <= now) {
                    add(MenuAction("Find sources for this episode") { close(); onFindSources(e) })
                }
                if (e.contentId.startsWith("tt") || e.kind == CalendarKind.EPISODE) {
                    add(MenuAction(
                        when {
                            e.kind == CalendarKind.MOVIE -> "Open movie page"
                            e.episode != null && !fromAiringToday -> "Open show page at this episode"
                            else -> "Open show page"
                        }
                    ) { close(); page(e) })
                }
                if (e.kind == CalendarKind.EPISODE) {
                    add(MenuAction(if (followed) "Turn off the bell" else "Ring the bell",
                        if (followed) null else "Follow it: its new episodes on Home and here") { close(); viewModel.toggleFollow(e) })
                }
                when {
                    airingFollow -> add(MenuAction("Take it off your Calendar") { close(); viewModel.toggleFollow(e) })
                    // Airing Today lists every show on today: nothing there is yours to take off.
                    fromAiringToday -> Unit
                    e.kind == CalendarKind.MOVIE && followed -> add(MenuAction("Turn off the bell") { close(); viewModel.toggleFollow(e) })
                    else -> add(MenuAction("Hide this ${if (e.kind == CalendarKind.MOVIE) "movie" else "show"} from the Calendar",
                        "It comes back from Hidden") { close(); viewModel.hide(e) })
                }
            }
            CalendarMenu(
                kicker = null,
                title = e.title,
                subtitle = entryLine(e, use24h),
                status = viewModel.status(e),
                actions = (if (e.kind == CalendarKind.GAME) emptyList() else liveList(e, close)) + extra,
                onDismiss = close
            )
        }
        followOptions?.let { f ->
            val close = { followOptions = null }
            CalendarMenu(
                kicker = null, title = f.name, subtitle = null, status = null,
                actions = buildList {
                    if (!f.isAiring || f.imdbId != null) add(MenuAction("Open ${if (f.type == "series") "show" else "movie"} page") {
                        close()
                        if (f.isAiring) onOpenDetail(f.imdbId!!, "movie", null, null, null) else onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                    })
                    add(MenuAction(if (f.isAiring) "Take it off your Calendar" else "Turn off the bell") { close(); viewModel.toggleFollow(f) })
                },
                onDismiss = close
            )
        }
        picker?.let { (e, record) ->
            val saved = viewModel.repo.savedChoice(e)
            ChannelPicker(
                label = { viewModel.repo.channelLabel(it) },
                savedText = saved?.first,
                onForget = if (saved == null) null else ({
                    picker = null
                    viewModel.forgetSaved(saved.second)
                }),
                onSearchAll = { picker = null; channelFor = e },
                entry = e,
                record = record,
                options = channels(e).filter { it.stopMs > now || it.catchup },
                now = now,
                use24h = use24h,
                pickedKey = viewModel.repo.savedKey(e),
                isRecording = { viewModel.repo.isRecording(it) },
                onDismiss = { picker = null },
                onPick = { hit, always, holdRecord ->
                    picker = null
                    val had = viewModel.repo.savedKey(e)
                    if (always && had != hit.channelKey) viewModel.setAlways(e, hit)
                    else if (!always && had != null) viewModel.setAlways(e, null)
                    // OK on a channel that isn't showing it yet records it there.
                    if (holdRecord || record || now !in hit.startMs until hit.stopMs) viewModel.record(e, hit)
                    else if (viewModel.watchLive(hit)) onOpenLive()
                }
            )
        }
        channelFor?.let { e ->
            ShowChannelPicker(
                title = e.title,
                initial = e.network.orEmpty(),
                channels = viewModel.repo.channelsForPicker(),
                hasPick = viewModel.repo.showChannel(e.contentId) != null,
                onPick = { ch -> channelFor = null; viewModel.setShowChannel(e, ch) },
                onDismiss = { channelFor = null }
            )
        }
    }
}

/** "S2 · E5 · Tonight 10:00 PM · FX". */
private fun entryLine(e: CalendarEntry, use24h: Boolean): String = listOfNotNull(
    e.episodeCode,
    e.league,
    dayWord(e.day, LocalDate.now()) + (e.airMs?.takeIf { !e.streaming }?.let { " " + formatClock(it, use24h) } ?: ""),
    e.guide?.channelName ?: e.network
).joinToString(" · ")

// -------------------------------------------------------------------- the big picture at the top

/**
 * The highlighted entry's wide picture, top right, fading into the page on the left and at the
 * bottom (the picture itself fades, so it blends into whatever background the page has, like
 * Nuvio's Home). Never a poster: those cut off heads when stretched wide.
 */
@Composable
private fun HeroBackdrop(e: CalendarEntry?) {
    val art = when {
        e == null || e.kind == CalendarKind.GAME -> null
        e.spoiler -> e.background
        else -> e.background ?: e.episodeImage
    }
    Box(Modifier.fillMaxWidth().height((HERO_H + 120).dp)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.74f)
                .fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    // Left edge: clear to solid across the first 60%. Bottom: solid to clear over the last 45%.
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.18f to Color.Black.copy(alpha = 0.25f),
                            0.42f to Color.Black.copy(alpha = 0.75f), 0.62f to Color.Black
                        ),
                        blendMode = BlendMode.DstIn
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Black, 0.55f to Color.Black, 0.8f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            if (e != null && e.kind == CalendarKind.GAME && e.teams.size == 2) {
                GameArt(e, big = true, modifier = Modifier.fillMaxSize())
            } else {
                Crossfade(targetState = art, animationSpec = tween(350), label = "calendarHero") { url ->
                    if (url != null) {
                        coil3.compose.AsyncImage(
                            model = url, contentDescription = null, contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(
    e: CalendarEntry?,
    st: CalendarLiveStatus,
    now: Long,
    use24h: Boolean,
    followed: Boolean,
    channelCount: Int,
    canWait: Boolean,
    loading: Boolean,
    onRecord: () -> Unit,
    /** Hold OK on Record or Watch live: the channel list (true: to record). */
    onChoose: (Boolean) -> Unit,
    label: (CalendarGuideHit) -> String,
    onWait: () -> Unit,
    onWatch: () -> Unit,
    onLiveOptions: () -> Unit,
    onOpen: () -> Unit,
    onBell: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().height(HERO_H.dp).padding(start = 64.dp, top = 26.dp, end = 40.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (e == null) {
            LiveText("Calendar", size = 30.sp, weight = FontWeight.ExtraBold)
            LiveText(if (loading) "Looking up your shows…" else "Nothing to show here yet", size = 15.sp, color = NuvioTheme.colors.TextSecondary)
            return@Column
        }
        val today = LocalDate.now()
        val airMs = e.airMs
        val onNow = e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onNow) LiveTag("ON NOW", LIVE_RED, Color.White)
            e.tags.take(2).forEach { TagChip(it) }
            LiveTag(soonText(e, now, today).uppercase(), Color.White.copy(alpha = 0.16f), Color.White)
            if (e.watched) LiveTag("✓ WATCHED", Color.White.copy(alpha = 0.16f), Color.White)
            LiveBadges(st)
        }
        LiveText(e.title.uppercase(), size = 30.sp, weight = FontWeight.ExtraBold, shadow = true, modifier = Modifier.width(560.dp))
        val line = when (e.kind) {
            CalendarKind.GAME -> listOfNotNull(e.league, e.score).joinToString(" · ")
            CalendarKind.MOVIE -> e.note ?: if (CalendarTag.AT_HOME in e.tags) "Out to watch at home" else ""
            else -> listOfNotNull(
                e.episodeCode,
                seasonDrop(e),
                if (e.spoiler) "title hidden: you're not there yet" else e.episodeTitle
            ).joinToString(" · ")
        }
        if (line.isNotBlank()) LiveText(line, size = 16.sp, weight = FontWeight.SemiBold, shadow = true, modifier = Modifier.width(560.dp))
        val whenText = dayWord(e.day, today) + when {
            airMs != null && !e.streaming -> " " + formatClock(airMs, use24h)
            e.streaming && e.kind == CalendarKind.EPISODE -> " · streaming"
            else -> ""
        }
        LiveText(
            listOfNotNull(whenText, e.guide?.channelName ?: e.network, e.runtimeMin?.let { "$it min" }).joinToString(" · "),
            size = 13.sp, color = NuvioTheme.colors.TextSecondary, shadow = true
        )
        val about = e.description?.takeIf { !e.spoiler && it.isNotBlank() }
        if (about != null) LiveText(about, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2, modifier = Modifier.width(480.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = e.guide
            val more = if (channelCount > 1) " +${channelCount - 1}" else ""
            if (g != null && now in g.startMs until g.stopMs) HeroButton("Watch live on ${label(g)}$more", dot = false, onClick = onWatch, onLongClick = { onChoose(false) })
            when {
                g != null && g.stopMs > now -> HeroButton(if (st.recording) "Recording · cancel" else "Record on ${label(g)}$more", dot = true, onClick = onRecord, onLongClick = { onChoose(true) })
                st.waitRecord -> HeroButton("Records when it's in your guide · cancel", dot = true, onClick = onWait)
                canWait -> HeroButton("Record when it's in my guide", dot = true, onClick = onWait)
            }
            if (e.kind != CalendarKind.GAME) HeroButton("Live TV options", dot = false, onClick = onLiveOptions)
            HeroButton(
                when (e.kind) { CalendarKind.GAME -> "Open in Sports"; CalendarKind.MOVIE -> "Movie page"; else -> "Show page" },
                dot = false, onClick = onOpen
            )
            if (e.kind == CalendarKind.EPISODE) HeroBell(on = followed, onClick = onBell)
        }
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroButton(text: String, dot: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0xE62A2A30))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.32f), shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(LIVE_RED))
            Spacer(Modifier.width(7.dp))
        }
        LiveText(text, size = 13.sp, weight = FontWeight.SemiBold, color = if (focused) Color(0xFF111111) else Color.White)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroBell(on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val light = focused || on
    Box(
        Modifier
            .size(34.dp)
            .clip(shape)
            .background(if (light) Color.White else Color(0xE62A2A30))
            .border(if (focused) 3.dp else 1.dp, if (focused) LIVE_RED else if (on) Color.Transparent else Color.White.copy(alpha = 0.32f), shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(
            if (on) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
            contentDescription = if (on) "Turn off the bell" else "Ring the bell",
            tint = if (light) Color(0xFF111111) else Color.White,
            modifier = Modifier.size(17.dp)
        )
    }
}

// -------------------------------------------------------------------- rows of cards, by day

private data class DayRow(val day: LocalDate, val games: Boolean, val list: List<CalendarEntry>, val firstOfDay: Boolean)

@Composable
private fun DayRows(
    entries: List<CalendarEntry>,
    newestFirst: Boolean,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val rowStates = remember { HashMap<String, LazyListState>() }
    // Back: to the top, and the first card.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        listState.scrollToItem(0)
        rowStates.values.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    // Each day: its shows and movies in one row, its games in a row of their own under it.
    val rows = remember(entries, newestFirst) {
        val byDay = entries.distinctBy { it.key }.groupBy { it.day }.toList()
            .let { if (newestFirst) it.sortedByDescending { d -> d.first } else it.sortedBy { d -> d.first } }
        byDay.flatMap { (day, list) ->
            val (games, rest) = list.partition { it.kind == CalendarKind.GAME }
            listOfNotNull(
                rest.takeIf { it.isNotEmpty() }?.let { DayRow(day, false, it, true) },
                games.takeIf { it.isNotEmpty() }?.let { DayRow(day, true, it, rest.isEmpty()) }
            )
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(rows, key = { _, r -> r.day.toString() + if (r.games) "g" else "s" }) { rowIndex, r ->
            Column {
                Row(Modifier.padding(start = 64.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    if (r.firstOfDay) {
                        LiveText(dayWord(r.day, today), size = 17.sp, weight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        LiveText(r.day.format(DateTimeFormatter.ofPattern("EEE MMM d")), size = 13.sp, color = NuvioTheme.colors.TextSecondary)
                        Spacer(Modifier.width(8.dp))
                    }
                    LiveText(
                        if (r.games) "Games · ${r.list.size}" else "· ${r.list.size}",
                        size = 13.sp, weight = if (r.games) FontWeight.SemiBold else FontWeight.Normal, color = NuvioTheme.colors.TextSecondary
                    )
                }
                val rowKey = r.day.toString() + if (r.games) "g" else "s"
                LazyRow(
                    state = rowStates.getOrPut(rowKey) { LazyListState() },
                    contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(r.list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, st = status(e),
                            modifier = if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier,
                            onFocus = { onFocus(e) }, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WideCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    st: CalendarLiveStatus,
    modifier: Modifier = Modifier,
    width: Dp = CARD_W,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val height = width * 9f / 16f
    val airMs = e.airMs
    val onNow = e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)
    val game = e.kind == CalendarKind.GAME && e.teams.size == 2
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
                .size(width, height)
                .clip(shape)
                .background(Color(0xFF1C1E26))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (game) {
                GameArt(e, big = false, modifier = Modifier.fillMaxSize())
            } else {
                val art = when {
                    e.kind == CalendarKind.GAME -> null
                    e.spoiler -> e.background ?: e.poster
                    else -> e.episodeImage ?: e.background ?: e.poster
                }
                if (art != null) {
                    coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else if (e.kind == CalendarKind.GAME && e.poster != null) {
                    coil3.compose.AsyncImage(model = e.poster, contentDescription = null, contentScale = ContentScale.Fit,
                        modifier = Modifier.align(Alignment.Center).size(height * 0.5f))
                }
                // A dark edge at the bottom so the name reads on any picture.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            }
            Row(Modifier.align(Alignment.TopStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
                LiveBadges(st, compact = true)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                when {
                    onNow -> LiveTag("LIVE", LIVE_RED, Color.White)
                    e.watched -> LiveTag("✓", Color.Black.copy(alpha = 0.78f), Color.White)
                    e.tags.isNotEmpty() && !game -> TagChip(e.tags.first())
                    else -> Unit
                }
            }
            if (!game) {
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp), verticalAlignment = Alignment.Bottom) {
                    LiveText(
                        if (e.kind == CalendarKind.GAME) e.score?.let { "${e.title}  ·  $it" } ?: e.title else e.title.uppercase(),
                        size = 13.sp, weight = FontWeight.ExtraBold, color = Color.White, shadow = true,
                        modifier = Modifier.weight(1f), marquee = focused
                    )
                    (e.guide?.channelName ?: e.network)?.let {
                        Spacer(Modifier.width(6.dp))
                        LiveTag(it.take(14), Color.White.copy(alpha = 0.9f), Color(0xFF111111))
                    }
                }
            }
        }
        LiveText(
            when (e.kind) {
                CalendarKind.GAME -> listOfNotNull(e.league, e.guide?.channelName ?: e.network).joinToString(" · ")
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, seasonDrop(e), if (e.spoiler) null else e.episodeTitle).joinToString(" · ")
            },
            size = 12.sp, weight = FontWeight.Medium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = 2.dp), marquee = focused
        )
    }
}

/** "8 episodes" for a season (or part of one) that comes out at once. */
private fun seasonDrop(e: CalendarEntry): String? {
    val first = e.episode ?: return null
    val last = e.lastEpisode?.takeIf { it > first } ?: return null
    return "${last - first + 1} episodes" + if (first == 1) " (whole season)" else ""
}

// -------------------------------------------------------------------- games

/**
 * A game, drawn like the Sports page draws it: each team's colour on its half, both logos big,
 * and the score (or the start time) between them.
 */
@Composable
private fun GameArt(e: CalendarEntry, big: Boolean, modifier: Modifier) {
    val a = e.teams.getOrNull(0)
    val b = e.teams.getOrNull(1)
    val ca = a?.color?.let { Color(it) } ?: Color(0xFF2B3442)
    val cb = b?.color?.let { Color(it) } ?: Color(0xFF3A2B42)
    val base = Color(0xFF0E1015)
    Box(
        modifier.background(
            Brush.horizontalGradient(
                0f to lerpColor(ca, base, 0.35f), 0.46f to lerpColor(ca, base, 0.62f),
                0.54f to lerpColor(cb, base, 0.62f), 1f to lerpColor(cb, base, 0.35f)
            )
        )
    ) {
        val logo = if (big) 120.dp else 44.dp
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (big) 48.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TeamMark(a, logo, big)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val scores = if (a?.score != null && b?.score != null) "${a.score} – ${b.score}" else null
                LiveText(
                    scores ?: e.airMs?.let { formatClock(it, false) } ?: "vs",
                    size = if (big) 34.sp else 15.sp, weight = FontWeight.ExtraBold, color = Color.White, shadow = true
                )
                if (e.live) LiveTag("LIVE", LIVE_RED, Color.White)
                else if (scores != null) LiveText("Final", size = if (big) 14.sp else 10.sp, color = Color.White.copy(alpha = 0.8f), shadow = true)
            }
            TeamMark(b, logo, big)
        }
    }
}

@Composable
private fun TeamMark(t: CalendarTeam?, size: Dp, big: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (t?.logo != null) {
                coil3.compose.AsyncImage(model = t.logo, contentDescription = t.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                LiveText(t?.abbr ?: t?.name?.take(3)?.uppercase() ?: "", size = if (big) 30.sp else 14.sp, weight = FontWeight.ExtraBold, color = Color.White)
            }
        }
        LiveText(
            (if (big) t?.name else t?.abbr ?: t?.name) ?: "",
            size = if (big) 16.sp else 10.sp, weight = FontWeight.Bold, color = Color.White, shadow = true
        )
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f
)

// -------------------------------------------------------------------- the week

@Composable
private fun WeekView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val thisWeek = today.minusDays((today.dayOfWeek.value % 7).toLong())
    var start by remember { mutableStateOf(thisWeek) }
    val columnStates = remember { List(7) { LazyListState() } }
    // Back: this week, today.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        start = thisWeek
        columnStates.forEach { runCatching { it.scrollToItem(0) } }
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(
                start.format(DateTimeFormatter.ofPattern("MMM d")) + " – " + start.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d")),
                size = 20.sp, weight = FontWeight.ExtraBold
            )
            Spacer(Modifier.width(10.dp))
            LiveText(
                when (start) { thisWeek -> "This week"; thisWeek.plusWeeks(1) -> "Next week"; thisWeek.minusWeeks(1) -> "Last week"; else -> "" },
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹ Week", false) { start = start.minusWeeks(1) }
            Spacer(Modifier.width(6.dp))
            if (start != thisWeek) {
                Pill("This week", false) { start = thisWeek }
                Spacer(Modifier.width(6.dp))
            }
            Pill("Week ›", false) { start = start.plusWeeks(1) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (d in 0 until 7) {
                val day = start.plusDays(d.toLong())
                val list = byDay[day].orEmpty().sortedWith(compareBy<CalendarEntry> { it.kind == CalendarKind.GAME }.thenBy { it.airMs ?: Long.MAX_VALUE })
                val isToday = day == today
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isToday) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                ) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        LiveText(
                            day.format(DateTimeFormatter.ofPattern("EEE")).uppercase(), size = 12.sp, weight = FontWeight.Bold,
                            color = if (isToday) LIVE_RED else NuvioTheme.colors.TextSecondary
                        )
                        Spacer(Modifier.width(5.dp))
                        LiveText(
                            day.dayOfMonth.toString(), size = 16.sp, weight = FontWeight.ExtraBold,
                            color = if (day.isBefore(today)) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary
                        )
                    }
                    if (list.isEmpty()) {
                        LiveText("—", size = 13.sp, color = NuvioTheme.colors.TextSecondary.copy(alpha = 0.5f), modifier = Modifier.padding(horizontal = 8.dp))
                    } else {
                        LazyColumn(
                            state = columnStates[d],
                            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 40.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                                MiniCard(
                                    e = e, use24h = use24h, now = now, st = status(e),
                                    modifier = if (i == 0 && (isToday || (start != thisWeek && d == 0))) Modifier.focusRequester(firstFocus).homeMark() else Modifier,
                                    onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small card for the week's columns: the picture, then the name and episode. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MiniCard(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    st: CalendarLiveStatus,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(9.dp)
    val game = e.kind == CalendarKind.GAME && e.teams.size == 2
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) 1.04f else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Color(0xFF1C1E26))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (game) {
                GameArt(e, big = false, modifier = Modifier.fillMaxSize())
            } else {
                val art = if (e.spoiler) e.background ?: e.poster else e.episodeImage ?: e.background ?: e.poster
                if (art != null) coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.align(Alignment.TopStart).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
                if (st.recording || st.series) LiveTag("●", LIVE_RED, Color.White)
            }
            val airMs = e.airMs
            if (e.live || (airMs != null && now in airMs until airMs + (e.runtimeMin ?: 30) * 60_000L)) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) { LiveTag("LIVE", LIVE_RED, Color.White) }
            }
        }
        LiveText(
            if (game) e.teams.joinToString(" v ") { it.abbr ?: it.name } else e.title, size = 12.sp, weight = FontWeight.Bold,
            color = NuvioTheme.colors.TextPrimary, marquee = focused
        )
        LiveText(
            when (e.kind) {
                CalendarKind.GAME -> e.league ?: ""
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, e.guide?.channelName ?: e.network).joinToString(" · ")
            },
            size = 10.sp, color = NuvioTheme.colors.TextSecondary
        )
    }
}

// -------------------------------------------------------------------- the month

@Composable
private fun MonthView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    reset: Int,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    var shown by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(today) }
    // Back: this month, today.
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        shown = YearMonth.now()
        selected = today
        delay(80)
        runCatching { firstFocus.requestFocus() }
    }
    val byDay = remember(entries) {
        // Shows and movies first in each day: a game only stands for a day that has nothing else.
        entries.distinctBy { it.key }.groupBy { it.day }.mapValues { (_, l) -> l.sortedBy { it.kind == CalendarKind.GAME } }
    }
    val stripFocus = remember { FocusRequester() }
    val list = byDay[selected].orEmpty()
    Column(Modifier.fillMaxSize().padding(end = 32.dp)) {
        // The highlighted day, big: its releases as cards.
        Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), size = 22.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            LiveText(
                (if (list.isEmpty()) "Nothing out" else "${list.size} on this day") + " · " + dayWord(selected, today).lowercase(),
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹", false) { shown = shown.minusMonths(1) }
            LiveText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
            Pill("›", false) { shown = shown.plusMonths(1) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.height(CARD_W * 0.8f * 9f / 16f + 36.dp)) {
            if (list.isEmpty()) {
                LiveText("Highlight a day with a picture to see what's out", size = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 64.dp, top = 30.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, st = status(e), width = CARD_W * 0.8f,
                            modifier = if (i == 0) Modifier.focusRequester(stripFocus) else Modifier,
                            onFocus = {}, onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 64.dp)) {
            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                LiveText(it, size = 10.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        val firstDay = shown.atDay(1)
        val start = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong())
        val weeks = ((ChronoUnit.DAYS.between(start, shown.atEndOfMonth()) / 7) + 1).toInt()
        Column(Modifier.weight(1f).padding(start = 64.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (w in 0 until weeks) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val day = start.plusDays((w * 7 + d).toLong())
                        DayCell(
                            day = day,
                            inMonth = day.month == shown.month,
                            today = day == today,
                            entries = byDay[day].orEmpty(),
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .then(if (day == today && shown == YearMonth.now()) Modifier.focusRequester(firstFocus).homeMark() else Modifier),
                            onFocus = { selected = day },
                            onClick = { if (byDay[day].orEmpty().isNotEmpty()) runCatching { stripFocus.requestFocus() } }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    today: Boolean,
    entries: List<CalendarEntry>,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val top = entries.firstOrNull()
    val games = entries.filter { it.kind == CalendarKind.GAME }
    val art = when {
        top == null || top.kind == CalendarKind.GAME -> null
        top.spoiler -> top.background ?: top.poster
        else -> top.episodeImage ?: top.background ?: top.poster
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (inMonth) 1f else 0.45f }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(
                if (focused) 3.dp else if (today) 2.dp else 0.dp,
                if (focused) Color.White else if (today) LIVE_RED else Color.Transparent, shape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (art != null) {
            coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        } else if (top != null && top.teams.size == 2) {
            // Only games that day: the game, in its teams' colours.
            GameArt(top, big = false, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f })
        }
        LiveText(
            if (day.dayOfMonth == 1) day.format(DateTimeFormatter.ofPattern("MMM d")) else day.dayOfMonth.toString(),
            size = 12.sp, weight = if (today || focused) FontWeight.ExtraBold else FontWeight.SemiBold, color = Color.White, shadow = true,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
        )
        // Games that day besides a show: your team's logo in the corner.
        if (games.isNotEmpty() && top?.kind != CalendarKind.GAME) {
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                games.take(2).forEach { g ->
                    Box(Modifier.size(18.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(2.dp)) {
                        g.poster?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        when {
            top == null -> Unit
            top.kind != CalendarKind.GAME || top.teams.size != 2 -> CellCaption(top.title, entries.size)
            entries.size > 1 -> Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) { LiveTag("+${entries.size - 1}", Color.White, Color(0xFF111111)) }
            else -> Unit
        }
    }
}

@Composable
private fun BoxScope.CellCaption(title: String, count: Int) {
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveText(title, size = 10.sp, weight = FontWeight.Bold, color = Color.White, shadow = true, modifier = Modifier.weight(1f))
        if (count > 1) LiveTag("+${count - 1}", Color.White, Color(0xFF111111))
    }
}

// -------------------------------------------------------------------- posters (Airing Today, Following)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    poster: String?,
    title: String,
    caption: String,
    corner: String?,
    bell: Boolean,
    tag: CalendarTag?,
    st: CalendarLiveStatus?,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            if (poster != null) coil3.compose.AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else LiveText(title, size = 13.sp, weight = FontWeight.Bold, maxLines = 3, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                corner?.let { LiveTag(it, Color.Black.copy(alpha = 0.78f), Color.White) }
                Spacer(Modifier.weight(1f))
                if (bell) Box(Modifier.size(20.dp).clip(RoundedCornerShape(50)).background(Color.White), contentAlignment = Alignment.Center) {
                    androidx.tv.material3.Icon(Icons.Default.Notifications, contentDescription = "Bell on", tint = Color(0xFF111111), modifier = Modifier.size(12.dp))
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (st != null && st != CalendarLiveStatus()) LiveBadges(st, compact = true)
                tag?.let { TagChip(it) }
            }
        }
        LiveText(title, size = 12.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextPrimary, marquee = focused)
        LiveText(caption, size = 11.sp, color = NuvioTheme.colors.TextSecondary)
    }
}

@Composable
private fun PosterGrid(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    isFollowed: (CalendarEntry) -> Boolean,
    status: (CalendarEntry) -> CalendarLiveStatus,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val list = remember(entries) { entries.distinctBy { it.key } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
            PosterCard(
                poster = e.poster,
                title = e.title,
                caption = listOfNotNull(e.episodeCode, e.guide?.channelName ?: e.network).joinToString(" · "),
                corner = cardTime(e, use24h),
                bell = e.followed || isFollowed(e),
                tag = e.tags.firstOrNull { it != CalendarTag.NEW },
                st = status(e),
                modifier = if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier,
                onFocus = { onFocus(e) },
                onClick = { onOpen(e) },
                onLongClick = { onOptions(e) }
            )
        }
    }
}

@Composable
private fun FollowingGrid(
    follows: List<CalendarFollow>,
    entries: List<CalendarEntry>,
    now: Long,
    use24h: Boolean,
    firstFocus: FocusRequester,
    reset: Int,
    seriesOn: (CalendarFollow) -> Boolean,
    onOpen: (CalendarFollow) -> Unit,
    onOptions: (CalendarFollow) -> Unit
) {
    val today = LocalDate.now()
    val gridState = rememberLazyGridState()
    LaunchedEffect(reset) {
        if (reset == 0) return@LaunchedEffect
        gridState.scrollToItem(0); delay(80); runCatching { firstFocus.requestFocus() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(follows, key = { _, f -> f.id }) { i, f ->
            val next = entries.firstOrNull { (it.contentId == (f.imdbId ?: f.id) || it.contentId == f.id || it.key == f.id) && whenOf(it) > now }
            val caption = when {
                f.isAiring -> "On ${f.channelName ?: "TV"} · ${dayWord(java.time.Instant.ofEpochMilli(f.startMs).atZone(ZoneId.systemDefault()).toLocalDate(), today)} ${formatClock(f.startMs, use24h)}"
                next != null -> "Next: ${dayWord(next.day, today)}" + (next.episodeCode?.let { " · $it" } ?: "")
                f.type == "movie" -> "Waiting to come out"
                else -> "Nothing announced yet"
            }
            PosterCard(
                poster = f.poster, title = f.name, caption = caption, corner = null, bell = true, tag = null,
                st = if (seriesOn(f)) CalendarLiveStatus(series = true) else null,
                modifier = if (i == 0) Modifier.focusRequester(firstFocus).homeMark() else Modifier,
                onFocus = {}, onClick = { onOpen(f) }, onLongClick = { onOptions(f) }
            )
        }
    }
}

// -------------------------------------------------------------------- small parts

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, if (selected) Color.White else Color.White.copy(alpha = 0.08f))
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp)
    ) {
        LiveText(
            text, size = 12.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected && !focused) Color(0xFF111111) else colors.text
        )
    }
}

/** An on/off setting in the filter bar: an outline and a switch, so it doesn't read as one more filter. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwitchPill(text: String, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.Transparent)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.border else Color.White.copy(alpha = 0.28f), shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(start = 7.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SwitchMark(on)
        Spacer(Modifier.width(7.dp))
        LiveText(text, size = 12.sp, weight = FontWeight.Medium, color = colors.text)
    }
}

/** List · Week · Month, side by side in one control. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        options.forEach { (id, label) ->
            var focused by remember { mutableStateOf(false) }
            val on = id == selected
            val colors = liveCellColors(focused, if (on) Color.White.copy(alpha = 0.9f) else Color.Transparent)
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.background)
                    .border(if (focused) 2.dp else 0.dp, colors.border, RoundedCornerShape(50))
                    .onFocusChanged { focused = it.isFocused }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { onPick(id) })
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                LiveText(
                    label, size = 12.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on && !focused) Color(0xFF111111) else colors.text
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 4.dp).size(width = 1.dp, height = 18.dp).background(Color.White.copy(alpha = 0.18f)))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconPill(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.White.copy(alpha = 0.08f))
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(30.dp)
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        androidx.tv.material3.Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.text, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TagChip(tag: CalendarTag) {
    val (bg, fg) = when (tag) {
        CalendarTag.NEW -> LIVE_RED to Color.White
        CalendarTag.SERIES_PREMIERE, CalendarTag.SEASON_PREMIERE -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
        CalendarTag.SEASON_FINALE, CalendarTag.SERIES_FINALE -> Color(0xFF8A4B12) to Color(0xFFFFE2C7)
        CalendarTag.AT_HOME -> Color(0xFF1C6B45) to Color(0xFFD6F5E4)
        CalendarTag.IN_THEATERS, CalendarTag.ON_DISC -> Color(0xFF3A3D48) to Color(0xFFE6E8EE)
        CalendarTag.ON_TV -> Color(0xFF2B4C8C) to Color(0xFFDCE6FF)
    }
    LiveTag(tag.label.uppercase(), bg, fg)
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 30.dp, start = 64.dp, end = 40.dp), contentAlignment = Alignment.TopStart) {
        LiveText(text, size = 16.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 4, modifier = Modifier.width(600.dp))
    }
}

private fun whenOf(e: CalendarEntry): Long =
    e.airMs ?: e.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun cardTime(e: CalendarEntry, use24h: Boolean): String {
    val airMs = e.airMs
    return when {
        e.kind == CalendarKind.MOVIE && CalendarTag.ON_TV in e.tags && airMs != null -> formatClock(airMs, use24h)
        e.kind == CalendarKind.MOVIE -> if (CalendarTag.IN_THEATERS in e.tags) "In theaters" else if (CalendarTag.ON_DISC in e.tags) "On disc" else "At home"
        airMs != null && !e.streaming -> formatClock(airMs, use24h)
        e.streaming -> "Streaming"
        else -> "All day"
    }
}

/** "In 3 h", "Tomorrow", "In 5 days", "2 days ago". */
private fun soonText(e: CalendarEntry, now: Long, today: LocalDate): String {
    val at = whenOf(e)
    val days = ChronoUnit.DAYS.between(today, e.day)
    return when {
        at <= now && days == 0L -> "Today"
        at <= now -> if (days == -1L) "Yesterday" else "${-days} days ago"
        days == 0L && e.airMs != null -> ((at - now) / 3_600_000L).let { h -> if (h < 1) "In ${((at - now) / 60_000L).coerceAtLeast(1)} min" else "In $h h" }
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        else -> "In $days days"
    }
}

private fun dayWord(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEEE"))
    else -> day.format(DateTimeFormatter.ofPattern("EEE MMM d"))
}
