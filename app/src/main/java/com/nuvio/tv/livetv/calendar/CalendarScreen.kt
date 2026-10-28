package com.nuvio.tv.livetv.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class CalTab { UPCOMING, RECENT, AIRING, FOLLOWING, HIDDEN }
private enum class CalKind { ALL, SHOWS, MOVIES, SPORTS }
private enum class AirFilter { ALL, PRIME, STREAMING, FOLLOWING }

/** Watch live or record, on which channel. */
private enum class PickMode { WATCH, RECORD }

private val CARD_W = 208.dp
private val CARD_H = 117.dp
private const val HERO_H = 232

/**
 * The Calendar: your shows' and movies' release dates as rows of big cards by day (or a
 * month), what's airing today, and the shows you follow. The highlighted entry fills the top,
 * like Nuvio's Home.
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
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    val follows by viewModel.follows.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val use24h by viewModel.use24h.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    viewModel.schedules.collectAsStateWithLifecycle().value // recordings changed: Record/Cancel redrawn
    viewModel.channelPicks.collectAsStateWithLifecycle().value

    var tab by rememberSaveable { mutableStateOf(CalTab.UPCOMING) }
    var kind by rememberSaveable { mutableStateOf(CalKind.ALL) }
    var airFilter by rememberSaveable { mutableStateOf(AirFilter.ALL) }
    val month = settings.calendarView == "MONTH"
    var heroKey by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf<CalendarEntry?>(null) }
    var followOptions by remember { mutableStateOf<CalendarFollow?>(null) }
    var picker by remember { mutableStateOf<Pair<CalendarEntry, PickMode>?>(null) }
    val firstItem = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }

    LaunchedEffect(tab) { if (tab == CalTab.AIRING) viewModel.loadAiring() }

    val today = LocalDate.now()
    val all = state.entries
    val filtered = all.filter {
        when (kind) {
            CalKind.ALL -> true
            CalKind.SHOWS -> it.kind == CalendarKind.EPISODE
            CalKind.MOVIES -> it.kind == CalendarKind.MOVIE
            CalKind.SPORTS -> it.kind == CalendarKind.GAME
        }
    }
    val upcoming = filtered.filter { !it.day.isBefore(today) && (it.day == today || whenOf(it) + (it.runtimeMin ?: 30) * 60_000L > now) }
    val recent = filtered.filter { !it.day.isAfter(today) && whenOf(it) <= now }
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

    fun open(e: CalendarEntry) {
        if (e.kind == CalendarKind.GAME) { e.eventId?.let(onOpenGame); return }
        if (e.kind == CalendarKind.MOVIE && !e.contentId.startsWith("tt")) return
        onOpenDetail(e.contentId, e.type, e.addonBaseUrl, e.season, e.episode)
    }
    fun channels(e: CalendarEntry) = e.guideOptions.ifEmpty { listOfNotNull(e.guide) }
    fun isFollowed(e: CalendarEntry) = follows.any { it.id == e.contentId || it.imdbId == e.contentId || it.id == e.key }
    /**
     * Watch live / record. Straight on when there's one channel (or the one you picked for that
     * network is showing it), else the list of channels. Record on something already set to
     * record cancels it.
     */
    fun act(e: CalendarEntry, mode: PickMode) {
        val opts = channels(e)
        if (opts.isEmpty()) return
        if (mode == PickMode.RECORD) {
            opts.firstOrNull { viewModel.repo.isRecording(it) }?.let { viewModel.record(e, it); return }
        }
        val usable = if (mode == PickMode.WATCH) opts.filter { now in it.startMs until it.stopMs } else opts
        if (usable.isEmpty()) return
        val picked = viewModel.repo.pickedChannel(e.network)?.let { k -> usable.firstOrNull { it.channelKey == k } }
        val direct = picked ?: usable.singleOrNull()
        if (direct == null) { picker = e to mode; return }
        when (mode) {
            PickMode.WATCH -> if (viewModel.watchLive(direct)) onOpenLive()
            PickMode.RECORD -> viewModel.record(e, direct)
        }
    }

    Box(Modifier.fillMaxSize().guideBackdrop(settings.guideBackdrop)) {
        val showHero = when (tab) {
            CalTab.UPCOMING, CalTab.RECENT -> !month
            CalTab.AIRING -> true
            else -> false
        }
        if (showHero) HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- the big picture
            if (showHero) {
                Hero(
                    e = hero,
                    now = now,
                    use24h = use24h,
                    recording = hero?.let { viewModel.repo.isRecording(it) } == true,
                    followed = hero?.let { isFollowed(it) } == true,
                    channelCount = hero?.let { channels(it).size } ?: 0,
                    loading = (state.loading && state.entries.isEmpty()) || (tab == CalTab.AIRING && airing.loading && airing.entries.isEmpty()),
                    onRecord = { hero?.let { act(it, PickMode.RECORD) } },
                    onWatch = { hero?.let { act(it, PickMode.WATCH) } },
                    onOpen = { hero?.let(::open) },
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
                if (month) {
                    val mine = tab == CalTab.UPCOMING || tab == CalTab.RECENT
                    Pill("My calendar", mine, tabMod(mine)) { tab = CalTab.UPCOMING }
                } else {
                    Pill("Upcoming", tab == CalTab.UPCOMING, tabMod(tab == CalTab.UPCOMING)) { tab = CalTab.UPCOMING; heroKey = null }
                    Pill("Recently aired", tab == CalTab.RECENT, tabMod(tab == CalTab.RECENT)) { tab = CalTab.RECENT; heroKey = null }
                }
                Pill("Airing today", tab == CalTab.AIRING, tabMod(tab == CalTab.AIRING)) { tab = CalTab.AIRING; heroKey = null }
                Pill("Following", tab == CalTab.FOLLOWING, tabMod(tab == CalTab.FOLLOWING)) { tab = CalTab.FOLLOWING }
                if (hidden.isNotEmpty() || tab == CalTab.HIDDEN) Pill("Hidden", tab == CalTab.HIDDEN, tabMod(tab == CalTab.HIDDEN)) { tab = CalTab.HIDDEN }
                Spacer(Modifier.width(10.dp))
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> {
                        Pill("All", kind == CalKind.ALL) { kind = CalKind.ALL; heroKey = null }
                        Pill("Shows", kind == CalKind.SHOWS) { kind = CalKind.SHOWS; heroKey = null }
                        Pill("Movies", kind == CalKind.MOVIES) { kind = CalKind.MOVIES; heroKey = null }
                        if (kind == CalKind.SPORTS || all.any { it.kind == CalendarKind.GAME }) Pill("Sports", kind == CalKind.SPORTS) { kind = CalKind.SPORTS; heroKey = null }
                    }
                    CalTab.AIRING -> {
                        Pill("All", airFilter == AirFilter.ALL) { airFilter = AirFilter.ALL; heroKey = null }
                        Pill("Prime time", airFilter == AirFilter.PRIME) { airFilter = AirFilter.PRIME; heroKey = null }
                        Pill("Streaming", airFilter == AirFilter.STREAMING) { airFilter = AirFilter.STREAMING; heroKey = null }
                        Pill("Following", airFilter == AirFilter.FOLLOWING) { airFilter = AirFilter.FOLLOWING; heroKey = null }
                        // Lit = news and talk shows are in the list.
                        Pill("News & talk", !settings.calendarSkipNewsTalk) { viewModel.setSkipNewsTalk(!settings.calendarSkipNewsTalk) }
                    }
                    else -> Unit
                }
                Spacer(Modifier.weight(1f))
                if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
                    Pill(if (month) "List" else "Month", false) { viewModel.setView(if (month) "LIST" else "MONTH") }
                }
                IconPill { if (tab == CalTab.AIRING) viewModel.loadAiring(force = true) else viewModel.refresh() }
            }
            // ---------------------------------------------------------------- what's on
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val onFocus: (CalendarEntry) -> Unit = { heroKey = it.key }
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> when {
                        month -> MonthView(
                            entries = filtered, use24h = use24h, now = now, firstFocus = firstItem,
                            onOpen = ::open, onOptions = { options = it },
                            isRecording = { viewModel.repo.isRecording(it) }
                        )
                        pool.isEmpty() -> EmptyNote(
                            when {
                                state.loading && state.entries.isEmpty() -> "Looking up your shows…"
                                state.showCount == 0 -> "Nothing here yet. Shows you watch, your Library and shows you ring the bell on show up here with their new episodes."
                                tab == CalTab.UPCOMING -> "Nothing coming up in the next four months."
                                else -> "Nothing aired in the last month."
                            }
                        )
                        else -> DayRows(
                            entries = pool,
                            newestFirst = tab == CalTab.RECENT,
                            use24h = use24h, now = now, firstFocus = firstItem,
                            isRecording = { viewModel.repo.isRecording(it) },
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.AIRING -> when {
                        airing.problem != null && airing.entries.isEmpty() -> EmptyNote(airing.problem!!)
                        airing.entries.isEmpty() -> EmptyNote(if (airing.loading) "Getting today's shows…" else "No new episodes found for today.")
                        airingShown.isEmpty() -> EmptyNote("Nothing here with this filter.")
                        else -> PosterGrid(
                            entries = airingShown, use24h = use24h, firstFocus = firstItem,
                            isFollowed = ::isFollowed,
                            onFocus = onFocus, onOpen = ::open, onOptions = { options = it }
                        )
                    }
                    CalTab.FOLLOWING -> {
                        if (follows.isEmpty()) EmptyNote("No bells rung yet. Ring the bell on a show's page, in Live TV or in Search (hold OK) to follow it.")
                        else FollowingGrid(
                            follows = follows.distinctBy { it.id }, entries = all, now = now, use24h = use24h, firstFocus = firstItem,
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
                                    modifier = Modifier.fillMaxWidth().then(if (h == list.first()) Modifier.focusRequester(firstItem) else Modifier),
                                    onClick = { viewModel.unhide(h) }
                                ) { focused ->
                                    LiveText(h.name.ifBlank { h.id }, size = 17.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused), modifier = Modifier.weight(1f))
                                    LiveText("OK: show it on the Calendar again", size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // First open: straight onto the cards.
        LaunchedEffect(Unit) {
            delay(300)
            if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
        }

        options?.let { e ->
            EntryOptions(
                entry = e,
                now = now,
                use24h = use24h,
                followed = isFollowed(e),
                recording = viewModel.repo.isRecording(e),
                channelCount = channels(e).size,
                onDismiss = { options = null },
                onFindSources = { options = null; onFindSources(e) },
                onWatchLive = { options = null; act(e, PickMode.WATCH) },
                onRecord = { options = null; act(e, PickMode.RECORD) },
                onChooseChannel = {
                    options = null
                    picker = e to (if (channels(e).any { now in it.startMs until it.stopMs }) PickMode.WATCH else PickMode.RECORD)
                },
                onOpen = { options = null; open(e) },
                onBell = { options = null; viewModel.toggleFollow(e) },
                onHide = { options = null; viewModel.hide(e) }
            )
        }
        followOptions?.let { f ->
            OptionsPanel(title = f.name, onDismiss = { followOptions = null }) { first ->
                if (!f.isAiring || f.imdbId != null) OptionRow("Open ${if (f.type == "series") "show" else "movie"} page", first) {
                    followOptions = null
                    if (f.isAiring) onOpenDetail(f.imdbId!!, "movie", null, null, null) else onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                }
                OptionRow(if (f.isAiring) "Take it off your Calendar" else "Turn off the bell", if (f.isAiring && f.imdbId == null) first else null) {
                    followOptions = null; viewModel.toggleFollow(f)
                }
            }
        }
        picker?.let { (e, mode) ->
            ChannelPicker(
                entry = e,
                mode = mode,
                options = channels(e),
                now = now,
                use24h = use24h,
                pickedKey = viewModel.repo.pickedChannel(e.network),
                isRecording = { viewModel.repo.isRecording(it) },
                onDismiss = { picker = null },
                onPick = { hit, always, record ->
                    picker = null
                    val had = viewModel.repo.pickedChannel(e.network)
                    if (always && had != hit.channelKey) viewModel.setChannelPick(e.network, hit)
                    else if (!always && had != null) viewModel.setChannelPick(e.network, null)
                    // OK on a channel that isn't showing it yet records it there.
                    if (record || mode == PickMode.RECORD || now !in hit.startMs until hit.stopMs) viewModel.record(e, hit)
                    else if (viewModel.watchLive(hit)) onOpenLive()
                }
            )
        }
    }
}

// -------------------------------------------------------------------- the big picture at the top

@Composable
private fun HeroBackdrop(e: CalendarEntry?) {
    val art = when {
        e == null || e.kind == CalendarKind.GAME -> null
        e.spoiler -> e.background ?: e.poster
        else -> e.background ?: e.episodeImage ?: e.poster
    }
    Box(Modifier.fillMaxWidth().height((HERO_H + 70).dp)) {
        if (art != null) {
            val bg = NuvioTheme.colors.Background
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().aspectRatio(16f / 9f)) {
                coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Fades the picture into the page: on the left (behind the words) and at the bottom (behind the rows).
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg, 0.3f to bg.copy(alpha = 0.75f), 0.65f to bg.copy(alpha = 0.15f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to bg)))
            }
        }
    }
}

@Composable
private fun Hero(
    e: CalendarEntry?,
    now: Long,
    use24h: Boolean,
    recording: Boolean,
    followed: Boolean,
    channelCount: Int,
    loading: Boolean,
    onRecord: () -> Unit,
    onWatch: () -> Unit,
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
        }
        LiveText(e.title.uppercase(), size = 30.sp, weight = FontWeight.ExtraBold, shadow = true, modifier = Modifier.width(560.dp))
        val line = when (e.kind) {
            CalendarKind.GAME -> listOfNotNull(e.league, e.score).joinToString(" · ")
            CalendarKind.MOVIE -> e.note ?: if (CalendarTag.AT_HOME in e.tags) "Out to watch at home" else ""
            else -> listOfNotNull(e.episodeCode, if (e.spoiler) "title hidden: you're not there yet" else e.episodeTitle).joinToString(" · ")
        }
        if (line.isNotBlank()) LiveText(line, size = 16.sp, weight = FontWeight.SemiBold, shadow = true, modifier = Modifier.width(560.dp))
        val whenText = dayWord(e.day, today) + when {
            airMs != null && !e.streaming -> " " + formatClock(airMs, use24h)
            e.streaming && e.kind == CalendarKind.EPISODE -> " · streaming"
            else -> ""
        }
        LiveText(
            listOfNotNull(whenText, e.network, e.runtimeMin?.let { "$it min" }).joinToString(" · "),
            size = 13.sp, color = NuvioTheme.colors.TextSecondary, shadow = true
        )
        val about = e.description?.takeIf { !e.spoiler && it.isNotBlank() }
        if (about != null) LiveText(about, size = 13.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2, modifier = Modifier.width(480.dp))
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val g = e.guide
            val more = if (channelCount > 1) " +${channelCount - 1}" else ""
            if (g != null && now in g.startMs until g.stopMs) HeroButton("Watch live on ${g.channelName}$more", dot = false, onClick = onWatch)
            if (g != null && g.stopMs > now) {
                HeroButton(if (recording) "Recording · cancel" else "Record on ${g.channelName}$more", dot = true, onClick = onRecord)
            }
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
private fun HeroButton(text: String, dot: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (focused) Color.White else Color(0xE62A2A30))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.32f), shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
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

@Composable
private fun DayRows(
    entries: List<CalendarEntry>,
    newestFirst: Boolean,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    isRecording: (CalendarEntry) -> Boolean,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val today = LocalDate.now()
    val days = remember(entries, newestFirst) {
        val byDay = entries.distinctBy { it.key }.groupBy { it.day }.toList()
        if (newestFirst) byDay.sortedByDescending { it.first } else byDay.sortedBy { it.first }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(days, key = { _, d -> d.first.toString() }) { rowIndex, (day, list) ->
            Column {
                Row(Modifier.padding(start = 64.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    LiveText(dayWord(day, today), size = 17.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    LiveText(day.format(DateTimeFormatter.ofPattern("EEE MMM d")) + " · ${list.size}", size = 13.sp, color = NuvioTheme.colors.TextSecondary)
                }
                LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 40.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, recording = isRecording(e),
                            modifier = if (rowIndex == 0 && i == 0) Modifier.focusRequester(firstFocus) else Modifier,
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
    recording: Boolean,
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
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier
                .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
                .size(width, height)
                .clip(shape)
                .background(cardColor(e))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
                .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, onLongClick = onLongClick)
        ) {
            val art = when {
                e.kind == CalendarKind.GAME -> null
                e.spoiler -> e.background ?: e.poster
                else -> e.episodeImage ?: e.background ?: e.poster
            }
            if (art != null) coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (e.kind == CalendarKind.GAME && e.poster != null) {
                Box(Modifier.align(Alignment.Center)) { com.nuvio.tv.livetv.ui.ChannelLogo(e.poster, 40.dp) }
            }
            // A dark edge at the bottom so the name reads on any picture.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f))))
            Row(Modifier.align(Alignment.TopStart).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                LiveTag(cardTime(e, use24h), Color.Black.copy(alpha = 0.78f), Color.White)
                if (recording) LiveTag("● REC", LIVE_RED, Color.White)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(7.dp)) {
                when {
                    onNow -> LiveTag("LIVE", LIVE_RED, Color.White)
                    e.tags.isNotEmpty() -> TagChip(e.tags.first())
                    else -> Unit
                }
            }
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
        LiveText(
            when (e.kind) {
                CalendarKind.GAME -> e.league ?: ""
                CalendarKind.MOVIE -> e.note ?: "Movie"
                else -> listOfNotNull(e.episodeCode, if (e.spoiler) null else e.episodeTitle).joinToString(" · ")
            },
            size = 12.sp, weight = FontWeight.Medium,
            color = if (focused) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

// -------------------------------------------------------------------- the month

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonthView(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit,
    isRecording: (CalendarEntry) -> Boolean
) {
    val today = LocalDate.now()
    var shown by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(today) }
    val byDay = remember(entries) { entries.distinctBy { it.key }.groupBy { it.day } }
    val stripFocus = remember { FocusRequester() }
    val list = byDay[selected].orEmpty()
    Column(Modifier.fillMaxSize().padding(end = 32.dp)) {
        // The highlighted day, big: its releases as cards.
        Row(Modifier.padding(start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveText(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), size = 22.sp, weight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            LiveText(
                (if (list.isEmpty()) "Nothing out" else "${list.size} release${if (list.size == 1) "" else "s"}") + " · " + dayWord(selected, today).lowercase(),
                size = 13.sp, color = NuvioTheme.colors.TextSecondary
            )
            Spacer(Modifier.weight(1f))
            Pill("‹", false) { shown = shown.minusMonths(1) }
            LiveText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), size = 15.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp))
            Pill("›", false) { shown = shown.plusMonths(1) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.height(CARD_H * 0.8f + 34.dp)) {
            if (list.isEmpty()) {
                LiveText("Highlight a day with a picture to see what's out", size = 14.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(start = 64.dp, top = 30.dp))
            } else {
                LazyRow(contentPadding = PaddingValues(start = 64.dp, end = 20.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(list, key = { _, e -> e.key }) { i, e ->
                        WideCard(
                            e = e, use24h = use24h, now = now, recording = isRecording(e), width = CARD_W * 0.8f,
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
                                .then(if (day == today && shown == YearMonth.now()) Modifier.focusRequester(firstFocus) else Modifier),
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
    val art = when {
        top == null || top.kind == CalendarKind.GAME -> null
        top.spoiler -> top.background ?: top.poster
        else -> top.episodeImage ?: top.background ?: top.poster
    }
    Box(
        modifier
            .graphicsLayer { alpha = if (inMonth) 1f else 0.45f }
            .clip(shape)
            .background(if (top != null) cardColor(top) else Color.White.copy(alpha = 0.05f))
            .border(
                if (focused) 3.dp else if (today) 2.dp else 0.dp,
                if (focused) Color.White else if (today) LIVE_RED else Color.Transparent, shape
            )
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (art != null) coil3.compose.AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (top != null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        LiveText(
            if (day.dayOfMonth == 1) day.format(DateTimeFormatter.ofPattern("MMM d")) else day.dayOfMonth.toString(),
            size = 12.sp, weight = if (today || focused) FontWeight.ExtraBold else FontWeight.SemiBold, color = Color.White, shadow = true,
            modifier = Modifier.align(Alignment.TopStart).padding(5.dp)
        )
        if (top != null) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveText(top.title, size = 10.sp, weight = FontWeight.Bold, color = Color.White, shadow = true, modifier = Modifier.weight(1f))
                if (entries.size > 1) LiveTag("+${entries.size - 1}", Color.White, Color(0xFF111111))
            }
        }
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
            tag?.let { Box(Modifier.align(Alignment.BottomStart).padding(6.dp)) { TagChip(it) } }
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
    isFollowed: (CalendarEntry) -> Boolean,
    onFocus: (CalendarEntry) -> Unit,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit
) {
    val list = remember(entries) { entries.distinctBy { it.key } }
    LazyVerticalGrid(
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
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
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
    onOpen: (CalendarFollow) -> Unit,
    onOptions: (CalendarFollow) -> Unit
) {
    val today = LocalDate.now()
    LazyVerticalGrid(
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
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                onFocus = {}, onClick = { onOpen(f) }, onLongClick = { onOptions(f) }
            )
        }
    }
}

// -------------------------------------------------------------------- choosing the channel

@Composable
private fun ChannelPicker(
    entry: CalendarEntry,
    mode: PickMode,
    options: List<CalendarGuideHit>,
    now: Long,
    use24h: Boolean,
    pickedKey: String?,
    isRecording: (CalendarGuideHit) -> Boolean,
    onDismiss: () -> Unit,
    /** The channel, whether to always use it for this network, and whether to record (hold OK). */
    onPick: (CalendarGuideHit, Boolean, Boolean) -> Unit
) {
    var always by remember { mutableStateOf(pickedKey != null) }
    val network = entry.network ?: "this network"
    OptionsPanel(
        title = entry.title,
        subtitle = listOfNotNull(
            entry.episodeCode,
            entry.airMs?.let { dayWord(entry.day, LocalDate.now()) + " " + formatClock(it, use24h) },
            "on ${options.size} of your channels"
        ).joinToString(" · "),
        header = if (mode == PickMode.WATCH) "WATCH LIVE · CHOOSE THE CHANNEL" else "RECORD · CHOOSE THE CHANNEL",
        onDismiss = onDismiss
    ) { first ->
        options.forEachIndexed { i, h ->
            val onNow = now in h.startMs until h.stopMs
            LiveFocusRow(
                modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                onClick = { onPick(h, always, false) },
                onLongClick = { onPick(h, always, true) }
            ) { focused ->
                Box(Modifier.size(width = 52.dp, height = 32.dp), contentAlignment = Alignment.Center) {
                    com.nuvio.tv.livetv.ui.ChannelLogo(h.channelLogo, 28.dp)
                }
                Column(Modifier.weight(1f)) {
                    LiveText(h.channelName, size = 15.sp, weight = FontWeight.Bold, color = focusedTextColor(focused).copy(alpha = if (h.later && !focused) 0.65f else 1f))
                    val sub = listOfNotNull(
                        h.playlist,
                        if (h.favorite) "Favorite" else null,
                        if (h.catchup) "catch-up" else null,
                        when {
                            onNow -> "on now"
                            h.later -> "airs ${formatClock(h.startMs, use24h)} (later)"
                            else -> "starts ${formatClock(h.startMs, use24h)}"
                        }
                    ).joinToString(" · ")
                    LiveText(sub, size = 12.sp, color = focusedTextColor(focused).copy(alpha = 0.7f))
                }
                when {
                    isRecording(h) -> LiveTag("● REC", LIVE_RED, Color.White)
                    h.channelKey == pickedKey -> LiveTag("YOUR PICK", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                    i == 0 && !h.later -> LiveTag("BEST MATCH", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                    h.later -> LiveTag("LATER", Color.White.copy(alpha = 0.16f), focusedTextColor(focused))
                    else -> Unit
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (entry.network != null) {
            LiveFocusRow(modifier = Modifier.fillMaxWidth(), onClick = { always = !always }) { focused ->
                Box(
                    Modifier.size(width = 36.dp, height = 20.dp).clip(RoundedCornerShape(50))
                        .background(if (always) Color(0xFF2B4C8C) else Color.White.copy(alpha = 0.2f)),
                    contentAlignment = if (always) Alignment.CenterEnd else Alignment.CenterStart
                ) { Box(Modifier.padding(2.dp).size(16.dp).clip(RoundedCornerShape(50)).background(Color.White)) }
                LiveText("Always use the channel I pick for $network", size = 14.sp, color = focusedTextColor(focused))
            }
        }
        LiveText(
            if (mode == PickMode.WATCH) "OK watches it (or records it if it hasn't started there) · Hold OK to record"
            else "OK records it on that channel",
            size = 12.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 2,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// -------------------------------------------------------------------- hold OK

@Composable
private fun EntryOptions(
    entry: CalendarEntry,
    now: Long,
    use24h: Boolean,
    followed: Boolean,
    recording: Boolean,
    channelCount: Int,
    onDismiss: () -> Unit,
    onFindSources: () -> Unit,
    onWatchLive: () -> Unit,
    onRecord: () -> Unit,
    onChooseChannel: () -> Unit,
    onOpen: () -> Unit,
    onBell: () -> Unit,
    onHide: () -> Unit
) {
    val sub = buildString {
        entry.episodeCode?.let { append(it).append(" · ") }
        append(dayWord(entry.day, LocalDate.now()))
        entry.airMs?.let { append(" ").append(formatClock(it, use24h)) }
        entry.network?.let { append(" · ").append(it) }
    }
    // A movie airing you put on the Calendar from Live TV: it can be taken off.
    val airing = entry.key.startsWith("air|")
    // Airing Today lists every show on today: nothing there is "on your Calendar" to remove.
    val fromAiringToday = entry.key.startsWith("today|")
    OptionsPanel(title = entry.title, subtitle = sub, onDismiss = onDismiss) { first ->
        var firstUsed = false
        fun req(): FocusRequester? = if (!firstUsed) { firstUsed = true; first } else null
        val g = entry.guide
        if (g != null && now in g.startMs until g.stopMs) OptionRow("Watch live on ${g.channelName}", req(), onWatchLive)
        val out = whenOf(entry) <= now
        if (entry.kind == CalendarKind.EPISODE && entry.videoId != null && out && !fromAiringToday) OptionRow("Find sources for this episode", req(), onFindSources)
        if (g != null && g.stopMs > now) {
            OptionRow(
                if (recording) "Cancel the recording" else "Record on ${g.channelName} at ${formatClock(g.startMs, use24h)}",
                req(), onRecord
            )
        }
        if (g != null && g.stopMs > now && channelCount > 1) OptionRow("Choose the channel ($channelCount of yours show it)", req(), onChooseChannel)
        if (entry.kind == CalendarKind.GAME) {
            OptionRow("Open in Sports", req(), onOpen)
        } else {
            if (entry.contentId.startsWith("tt") || entry.kind == CalendarKind.EPISODE) {
                OptionRow(
                    when {
                        entry.kind == CalendarKind.MOVIE -> "Open movie page"
                        entry.episode != null && !fromAiringToday -> "Open show page at this episode"
                        else -> "Open show page"
                    },
                    req(), onOpen
                )
            }
            if (entry.kind == CalendarKind.EPISODE) OptionRow(if (followed) "Turn off the bell" else "Ring the bell (follow this show)", req(), onBell)
            when {
                airing -> OptionRow("Take it off your Calendar", req(), onBell)
                fromAiringToday -> Unit
                entry.kind == CalendarKind.MOVIE && followed -> OptionRow("Turn off the bell", req(), onBell)
                else -> OptionRow("Hide this ${if (entry.kind == CalendarKind.MOVIE) "movie" else "show"} from the Calendar", req(), onHide)
            }
        }
    }
}

@Composable
private fun OptionsPanel(
    title: String,
    subtitle: String? = null,
    header: String? = null,
    onDismiss: () -> Unit,
    content: @Composable (first: FocusRequester) -> Unit
) {
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // Opened by holding OK: the rest of that press (held repeats, letting go) mustn't also press
    // the first option. Only a fresh press of OK counts.
    var freshPress by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .onPreviewKeyEvent { e ->
                val code = e.nativeKeyEvent.keyCode
                val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER ||
                    code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!ok || freshPress) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { freshPress = true; false } else true
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(18.dp)).background(NuvioTheme.colors.BackgroundElevated).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            header?.let { LiveText(it, size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary) }
            LiveText(title, size = 19.sp, weight = FontWeight.Bold)
            subtitle?.let { LiveText(it, size = 13.sp, color = NuvioTheme.colors.TextSecondary) }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Column { content(first) } } }
        }
    }
}

@Composable
private fun OptionRow(text: String, focus: FocusRequester?, onClick: () -> Unit) {
    LiveFocusRow(modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier), onClick = onClick) { focused ->
        LiveText(text, size = 15.sp, weight = if (focused) FontWeight.SemiBold else FontWeight.Medium, color = focusedTextColor(focused))
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

/** A colour for cards without a picture (games: green like a pitch). */
private fun cardColor(e: CalendarEntry): Color = when (e.kind) {
    CalendarKind.GAME -> Color(0xFF1F3A2C)
    else -> Color(0xFF1C1E26)
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
