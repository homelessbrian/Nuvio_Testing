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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveFocusRow
import com.nuvio.tv.livetv.ui.LivePosterImage
import com.nuvio.tv.livetv.ui.LiveTag
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.ThemeGlow
import com.nuvio.tv.livetv.ui.focusedTextColor
import com.nuvio.tv.livetv.ui.formatClock
import com.nuvio.tv.livetv.ui.guideBackdrop
import com.nuvio.tv.livetv.ui.guideSurface
import com.nuvio.tv.livetv.ui.liveCellColors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private enum class CalTab { UPCOMING, RECENT, AIRING, FOLLOWING, HIDDEN }
private enum class CalKind { ALL, SHOWS, MOVIES }

/**
 * The Calendar page: your shows' and movies' release dates as a list by day or a month, what's
 * airing today on TV and streaming, and the shows whose bell is rung.
 */
@Composable
fun CalendarScreen(
    /** Opens a show's or movie's page (at the episode, when there is one). */
    onOpenDetail: (id: String, type: String, addonBaseUrl: String?, season: Int?, episode: Int?) -> Unit,
    /** Finds sources for an episode that's out. */
    onFindSources: (CalendarEntry) -> Unit,
    /** Live TV is tuned: open it full screen. */
    onOpenLive: () -> Unit,
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

    var tab by rememberSaveable { mutableStateOf(CalTab.UPCOMING) }
    var kind by rememberSaveable { mutableStateOf(CalKind.ALL) }
    val month = settings.calendarView == "MONTH"
    var options by remember { mutableStateOf<CalendarEntry?>(null) }
    var followOptions by remember { mutableStateOf<CalendarFollow?>(null) }
    val firstItem = remember { FocusRequester() }
    val tabFocus = remember { FocusRequester() }

    LaunchedEffect(tab) { if (tab == CalTab.AIRING) viewModel.loadAiring() }

    fun open(e: CalendarEntry) = onOpenDetail(e.contentId, e.type, e.addonBaseUrl, e.season, e.episode)

    val today = LocalDate.now()
    val filtered = state.entries.filter {
        when (kind) {
            CalKind.ALL -> true
            CalKind.SHOWS -> it.kind == CalendarKind.EPISODE
            CalKind.MOVIES -> it.kind == CalendarKind.MOVIE
        }
    }

    Box(Modifier.fillMaxSize().guideBackdrop(settings.guideBackdrop)) {
        ThemeGlow(Modifier.fillMaxWidth().height(170.dp))
        Column(Modifier.fillMaxSize().padding(start = 64.dp, end = 40.dp, top = 26.dp)) {
            // ---------------------------------------------------------------- header
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveText("Calendar", size = 28.sp, weight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                val sub = when {
                    state.loading -> state.progress?.let { (a, b) -> "Looking up your shows… $a of $b" } ?: "Looking up your shows…"
                    state.updatedAt > 0 -> "Times in your time zone · updated ${agoText(now - state.updatedAt)}"
                    else -> "Times in your time zone"
                }
                LiveText(sub, size = 13.sp, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.weight(1f))
                Pill("List", selected = !month, onClick = { viewModel.setView("LIST") })
                Spacer(Modifier.width(6.dp))
                Pill("Month", selected = month, onClick = { viewModel.setView("MONTH") })
                Spacer(Modifier.width(14.dp))
                IconPill(onClick = { if (tab == CalTab.AIRING) viewModel.loadAiring(force = true) else viewModel.refresh() })
            }
            Spacer(Modifier.height(14.dp))
            // ---------------------------------------------------------------- tabs and filters
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (month) {
                    Pill("My calendar", selected = tab == CalTab.UPCOMING || tab == CalTab.RECENT,
                        modifier = if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) Modifier.focusRequester(tabFocus) else Modifier,
                        onClick = { tab = CalTab.UPCOMING })
                } else {
                    Pill("Upcoming", selected = tab == CalTab.UPCOMING,
                        modifier = if (tab == CalTab.UPCOMING) Modifier.focusRequester(tabFocus) else Modifier, onClick = { tab = CalTab.UPCOMING })
                    Pill("Recently aired", selected = tab == CalTab.RECENT,
                        modifier = if (tab == CalTab.RECENT) Modifier.focusRequester(tabFocus) else Modifier, onClick = { tab = CalTab.RECENT })
                }
                Pill("Airing today", selected = tab == CalTab.AIRING,
                    modifier = if (tab == CalTab.AIRING) Modifier.focusRequester(tabFocus) else Modifier, onClick = { tab = CalTab.AIRING })
                Pill("Following (${follows.size})", selected = tab == CalTab.FOLLOWING,
                    modifier = if (tab == CalTab.FOLLOWING) Modifier.focusRequester(tabFocus) else Modifier, onClick = { tab = CalTab.FOLLOWING })
                if (hidden.isNotEmpty() || tab == CalTab.HIDDEN) {
                    Pill("Hidden (${hidden.size})", selected = tab == CalTab.HIDDEN,
                        modifier = if (tab == CalTab.HIDDEN) Modifier.focusRequester(tabFocus) else Modifier, onClick = { tab = CalTab.HIDDEN })
                }
                if (tab == CalTab.UPCOMING || tab == CalTab.RECENT) {
                    Spacer(Modifier.width(18.dp))
                    Pill("All", selected = kind == CalKind.ALL, onClick = { kind = CalKind.ALL })
                    Pill("Shows", selected = kind == CalKind.SHOWS, onClick = { kind = CalKind.SHOWS })
                    Pill("Movies", selected = kind == CalKind.MOVIES, onClick = { kind = CalKind.MOVIES })
                }
            }
            Spacer(Modifier.height(12.dp))
            // ---------------------------------------------------------------- content
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    CalTab.UPCOMING, CalTab.RECENT -> {
                        if (month) {
                            MonthView(
                                entries = filtered,
                                use24h = use24h,
                                now = now,
                                firstFocus = firstItem,
                                onOpen = ::open,
                                onOptions = { options = it },
                                isRecording = { viewModel.repo.isRecording(it) }
                            )
                        } else {
                            val list = if (tab == CalTab.UPCOMING) filtered.filter { !it.day.isBefore(today) }
                            else filtered.filter { !it.day.isAfter(today) && whenOf(it) <= now }.sortedWith(
                                compareByDescending<CalendarEntry> { it.day }.thenByDescending { it.airMs ?: 0L })
                            if (list.isEmpty()) {
                                EmptyNote(
                                    when {
                                        state.loading && state.entries.isEmpty() -> "Looking up your shows…"
                                        state.showCount == 0 -> "Nothing here yet. Ring the bell on a show's page (or add shows and movies to your Library) and their new episodes and release dates show up here."
                                        tab == CalTab.UPCOMING -> "Nothing coming up in the next four months for your shows and movies."
                                        else -> "Nothing aired in the last month for your shows."
                                    }
                                )
                            } else {
                                DayList(
                                    entries = list,
                                    use24h = use24h,
                                    now = now,
                                    firstFocus = firstItem,
                                    onOpen = ::open,
                                    onOptions = { options = it },
                                    isRecording = { viewModel.repo.isRecording(it) }
                                )
                            }
                        }
                    }
                    CalTab.AIRING -> {
                        when {
                            airing.problem != null && airing.entries.isEmpty() -> EmptyNote(airing.problem!!)
                            airing.entries.isEmpty() && airing.loading -> EmptyNote("Getting today's shows…")
                            airing.entries.isEmpty() -> EmptyNote("No new episodes found for today.")
                            else -> DayList(
                                entries = airing.entries,
                                use24h = use24h,
                                now = now,
                                firstFocus = firstItem,
                                onOpen = ::open,
                                onOptions = { options = it },
                                isRecording = { viewModel.repo.isRecording(it) },
                                header = "TODAY · ${airing.entries.size} SHOWS WITH NEW EPISODES" +
                                    if (settings.calendarSkipNewsTalk) " (NEWS AND TALK SHOWS LEFT OUT)" else ""
                            )
                        }
                    }
                    CalTab.FOLLOWING -> {
                        if (follows.isEmpty()) EmptyNote("No bells rung yet. On a show's page, press the bell to follow it: its new episodes show up here and on Home.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                            items(follows, key = { it.id }) { f ->
                                LiveFocusRow(
                                    modifier = Modifier.fillMaxWidth().then(if (f == follows.first()) Modifier.focusRequester(firstItem) else Modifier),
                                    onClick = { onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null) },
                                    onLongClick = { followOptions = f }
                                ) { focused ->
                                    Poster(f.poster, f.name, Modifier.size(width = 40.dp, height = 58.dp))
                                    Column(Modifier.weight(1f)) {
                                        LiveText(f.name, size = 17.sp, weight = FontWeight.SemiBold, color = focusedTextColor(focused))
                                        val next = state.entries.firstOrNull { it.contentId == (f.imdbId ?: f.id) && whenOf(it) > now }
                                        LiveText(
                                            next?.let { "Next: ${dayWord(it.day, today)}" + (it.episodeCode?.let { c -> " · $c" } ?: "") }
                                                ?: if (f.type == "movie") "Movie" else "No new episodes announced yet",
                                            size = 13.sp, color = focusedTextColor(focused).copy(alpha = 0.7f)
                                        )
                                    }
                                    LiveText("Hold OK for options", size = 11.sp, color = focusedTextColor(focused).copy(alpha = if (focused) 0.6f else 0f))
                                }
                            }
                        }
                    }
                    CalTab.HIDDEN -> {
                        if (hidden.isEmpty()) EmptyNote("No hidden shows.")
                        else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(hidden, key = { it.id }) { h ->
                                LiveFocusRow(
                                    modifier = Modifier.fillMaxWidth().then(if (h == hidden.first()) Modifier.focusRequester(firstItem) else Modifier),
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

        // First open: straight onto the list.
        LaunchedEffect(Unit) {
            delay(250)
            if (runCatching { firstItem.requestFocus() }.isFailure) runCatching { tabFocus.requestFocus() }
        }

        options?.let { e ->
            EntryOptions(
                entry = e,
                now = now,
                use24h = use24h,
                followed = follows.any { it.id == e.contentId || it.imdbId == e.contentId },
                recording = viewModel.repo.isRecording(e),
                onDismiss = { options = null },
                onFindSources = { options = null; onFindSources(e) },
                onWatchLive = { options = null; if (viewModel.watchLive(e)) onOpenLive() },
                onRecord = { options = null; viewModel.record(e) },
                onOpen = { options = null; open(e) },
                onBell = { options = null; viewModel.toggleFollow(e) },
                onHide = { options = null; viewModel.hide(e) }
            )
        }
        followOptions?.let { f ->
            OptionsPanel(title = f.name, onDismiss = { followOptions = null }) { first ->
                OptionRow("Open ${if (f.type == "movie") "movie" else "show"} page", first) {
                    followOptions = null; onOpenDetail(f.id, f.type, f.addonBaseUrl, null, null)
                }
                OptionRow("Turn off the bell", null) { followOptions = null; viewModel.toggleFollow(f) }
            }
        }
    }
}

// -------------------------------------------------------------------- the list by day

@Composable
private fun DayList(
    entries: List<CalendarEntry>,
    use24h: Boolean,
    now: Long,
    firstFocus: FocusRequester,
    onOpen: (CalendarEntry) -> Unit,
    onOptions: (CalendarEntry) -> Unit,
    isRecording: (CalendarEntry) -> Boolean,
    header: String? = null
) {
    val today = LocalDate.now()
    val listState = rememberLazyListState()
    val days = remember(entries) { entries.groupBy { it.day }.toList() }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        var first = true
        days.forEach { (day, list) ->
            item(key = "h$day") {
                val word = when (ChronoUnit.DAYS.between(today, day)) { 0L -> "Today · "; 1L -> "Tomorrow · "; -1L -> "Yesterday · "; else -> "" }
                LiveText(
                    header ?: (word + day.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))).uppercase(),
                    size = 13.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 4.dp)
                )
            }
            list.forEach { e ->
                val isFirst = first
                first = false
                item(key = e.key) {
                    EntryRow(
                        e = e, use24h = use24h, now = now, recording = isRecording(e),
                        modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    e: CalendarEntry,
    use24h: Boolean,
    now: Long,
    recording: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    LiveFocusRow(modifier = modifier.fillMaxWidth(), onClick = onClick, onLongClick = onLongClick) { focused ->
        val text = focusedTextColor(focused)
        Poster(e.poster, e.title, Modifier.size(width = if (compact) 34.dp else 44.dp, height = if (compact) 50.dp else 64.dp))
        if (!compact) {
            Column(Modifier.width(92.dp)) {
                LiveText(timeText(e, use24h), size = 15.sp, weight = FontWeight.SemiBold, color = text)
                if (e.airMs != null && e.airMs <= now && now < e.airMs + (e.runtimeMin ?: 30) * 60_000L) {
                    LiveText("ON NOW", size = 11.sp, weight = FontWeight.Bold, color = LIVE_RED)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveText(e.title, size = if (compact) 14.sp else 17.sp, weight = FontWeight.SemiBold, color = text,
                    modifier = Modifier.weight(1f, fill = false), marquee = focused)
                if (!compact) e.tags.take(2).forEach { TagChip(it) }
                if (e.followed) {
                    androidx.tv.material3.Icon(Icons.Default.Notifications, contentDescription = "Bell on", tint = text.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                }
            }
            val sub = buildString {
                if (compact) append(timeText(e, use24h)).append(" · ")
                if (e.kind == CalendarKind.MOVIE) {
                    append(e.note ?: if (CalendarTag.AT_HOME in e.tags) "Out to watch at home" else "")
                } else {
                    e.episodeCode?.let { append(it) }
                    if (e.spoiler) append(" · title hidden: you're not there yet")
                    else e.episodeTitle?.let { append(" · ").append(it) }
                    if (e.watched) append(" · watched")
                }
            }
            if (sub.isNotBlank()) LiveText(sub, size = 13.sp, color = text.copy(alpha = if (e.spoiler) 0.55f else 0.75f))
            if (compact && CalendarTag.NEW !in e.tags && e.tags.isNotEmpty()) {
                Spacer(Modifier.height(3.dp)); TagChip(e.tags.first())
            }
        }
        if (!compact) {
            e.guide?.let { g ->
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x332B5BFF)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (recording) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(LIVE_RED))
                        Spacer(Modifier.width(6.dp))
                    }
                    LiveText(
                        (if (recording) "Recording · " else "In your guide · ") + g.channelName,
                        size = 12.sp, weight = FontWeight.Medium, color = text, modifier = Modifier.width(170.dp)
                    )
                }
            }
            LiveText(e.network ?: if (e.kind == CalendarKind.MOVIE) "Movie" else "", size = 12.sp, color = text.copy(alpha = 0.7f),
                modifier = Modifier.width(90.dp), align = androidx.compose.ui.text.style.TextAlign.End)
        }
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
    val byDay = remember(entries) { entries.groupBy { it.day } }
    val panelFocus = remember { FocusRequester() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("‹", selected = false, onClick = { shown = shown.minusMonths(1) })
                LiveText(shown.format(DateTimeFormatter.ofPattern("MMMM yyyy")), size = 18.sp, weight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp))
                Pill("›", selected = false, onClick = { shown = shown.plusMonths(1) })
                Spacer(Modifier.weight(1f))
                LiveText("OK on a day shows its list", size = 11.sp, color = NuvioTheme.colors.TextSecondary)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT").forEach {
                    LiveText(it, size = 11.sp, weight = FontWeight.Bold, color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.weight(1f).padding(start = 6.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            val firstDay = shown.atDay(1)
            val start = firstDay.minusDays((firstDay.dayOfWeek.value % 7).toLong())
            val weeks = ((ChronoUnit.DAYS.between(start, shown.atEndOfMonth()) / 7) + 1).toInt()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (w in 0 until weeks) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (d in 0 until 7) {
                            val day = start.plusDays((w * 7 + d).toLong())
                            DayCell(
                                day = day,
                                inMonth = day.month == shown.month,
                                today = day == today,
                                selected = day == selected,
                                entries = byDay[day].orEmpty(),
                                modifier = Modifier.weight(1f).fillMaxHeight().then(if (day == today && shown == YearMonth.now()) Modifier.focusRequester(firstFocus) else Modifier),
                                onFocus = { selected = day },
                                onClick = { if (byDay[day].orEmpty().isNotEmpty()) runCatching { panelFocus.requestFocus() } }
                            )
                        }
                    }
                }
            }
        }
        // The day's list.
        Column(
            Modifier.width(320.dp).fillMaxHeight().padding(bottom = 20.dp)
                .clip(RoundedCornerShape(16.dp)).background(guideSurface()).padding(14.dp)
        ) {
            LiveText("${dayWord(selected, today)} · ${selected.format(DateTimeFormatter.ofPattern("EEE MMM d"))}", size = 17.sp, weight = FontWeight.Bold)
            val list = byDay[selected].orEmpty()
            LiveText(if (list.isEmpty()) "Nothing out" else "${list.size} release${if (list.size == 1) "" else "s"}", size = 12.sp, color = NuvioTheme.colors.TextSecondary)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(list, key = { it.key }) { e ->
                    EntryRow(
                        e = e, use24h = use24h, now = now, recording = isRecording(e), compact = true,
                        modifier = if (e == list.first()) Modifier.focusRequester(panelFocus) else Modifier,
                        onClick = { onOpen(e) }, onLongClick = { onOptions(e) }
                    )
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
    selected: Boolean,
    entries: List<CalendarEntry>,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, if (today) Color.White.copy(alpha = 0.12f) else guideSurface())
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else if (selected) 1.dp else 0.dp, if (focused) colors.border else if (selected) Color.White.copy(alpha = 0.4f) else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        LiveText(
            if (day.dayOfMonth == 1) day.format(DateTimeFormatter.ofPattern("MMM d")) else day.dayOfMonth.toString(),
            size = 12.sp, weight = if (today) FontWeight.Bold else FontWeight.Medium,
            color = focusedTextColor(focused).copy(alpha = if (inMonth) 1f else 0.4f)
        )
        if (entries.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                entries.take(3).forEach { e -> Poster(e.poster, e.title, Modifier.size(width = 15.dp, height = 22.dp), small = true) }
                if (entries.size > 3) LiveText("+${entries.size - 3}", size = 10.sp, weight = FontWeight.Bold, color = focusedTextColor(focused))
            }
        }
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
    onDismiss: () -> Unit,
    onFindSources: () -> Unit,
    onWatchLive: () -> Unit,
    onRecord: () -> Unit,
    onOpen: () -> Unit,
    onBell: () -> Unit,
    onHide: () -> Unit
) {
    val sub = buildString {
        entry.episodeCode?.let { append(it).append(" · ") }
        append(dayWord(entry.day, LocalDate.now()))
        if (entry.airMs != null) append(" ").append(formatClock(entry.airMs, use24h))
        entry.network?.let { append(" · ").append(it) }
    }
    OptionsPanel(title = entry.title, subtitle = sub, onDismiss = onDismiss) { first ->
        var firstUsed = false
        fun req(): FocusRequester? = if (!firstUsed) { firstUsed = true; first } else null
        val g = entry.guide
        if (g != null && now in g.startMs until g.stopMs) OptionRow("Watch live on ${g.channelName}", req(), onWatchLive)
        val out = whenOf(entry) <= now
        if (entry.kind == CalendarKind.EPISODE && entry.videoId != null && out) OptionRow("Find sources for this episode", req(), onFindSources)
        if (g != null && g.stopMs > now) {
            OptionRow(
                if (recording) "Cancel the recording" else "Record on ${g.channelName} at ${formatClock(g.startMs, use24h)}",
                req(), onRecord
            )
        }
        OptionRow(if (entry.kind == CalendarKind.MOVIE) "Open movie page" else if (entry.episode != null) "Open show page at this episode" else "Open show page", req(), onOpen)
        OptionRow(if (followed) "Turn off the bell" else "Ring the bell (new episodes on Home)", req(), onBell)
        OptionRow("Hide this ${if (entry.kind == CalendarKind.MOVIE) "movie" else "show"} from the Calendar", req(), onHide)
    }
}

@Composable
private fun OptionsPanel(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable (first: FocusRequester) -> Unit
) {
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(470.dp).clip(RoundedCornerShape(18.dp)).background(NuvioTheme.colors.BackgroundElevated).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiveText(title, size = 19.sp, weight = FontWeight.Bold)
            subtitle?.let { LiveText(it, size = 13.sp, color = NuvioTheme.colors.TextSecondary) }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 380.dp)) { content(first) }
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
    val colors = liveCellColors(focused, if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.07f))
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        LiveText(text, size = 13.sp, weight = if (selected) FontWeight.Bold else FontWeight.Medium, color = colors.text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconPill(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val colors = liveCellColors(focused, Color.White.copy(alpha = 0.07f))
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(colors.background)
            .border(if (focused) 2.dp else 0.dp, colors.border, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.tv.material3.Icon(Icons.Default.Refresh, contentDescription = null, tint = colors.text, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        LiveText("Refresh", size = 13.sp, color = colors.text)
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
    }
    LiveTag(tag.label.uppercase(), bg, fg)
}

@Composable
private fun Poster(url: String?, name: String, modifier: Modifier, small: Boolean = false) {
    Box(modifier.clip(RoundedCornerShape(if (small) 3.dp else 6.dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) LivePosterImage(url, Modifier.fillMaxSize())
        else if (!small) LiveText(name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase(), size = 14.sp, weight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 40.dp), contentAlignment = Alignment.TopCenter) {
        LiveText(text, size = 16.sp, color = NuvioTheme.colors.TextSecondary, maxLines = 4,
            align = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(560.dp))
    }
}

private fun whenOf(e: CalendarEntry): Long =
    e.airMs ?: e.day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun timeText(e: CalendarEntry, use24h: Boolean): String = when {
    e.kind == CalendarKind.MOVIE -> if (CalendarTag.IN_THEATERS in e.tags) "Theaters" else if (CalendarTag.ON_DISC in e.tags) "Disc" else "At home"
    e.airMs != null && !e.streaming -> formatClock(e.airMs, use24h)
    e.streaming -> "Streaming"
    else -> "All day"
}

private fun dayWord(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
    0L -> "Today"
    1L -> "Tomorrow"
    -1L -> "Yesterday"
    in 2L..6L -> day.format(DateTimeFormatter.ofPattern("EEEE"))
    else -> day.format(DateTimeFormatter.ofPattern("EEE MMM d"))
}

private fun agoText(ms: Long): String = when {
    ms < 90_000L -> "just now"
    ms < 3_600_000L -> "${ms / 60_000L} min ago"
    ms < 86_400_000L -> "${ms / 3_600_000L} h ago"
    else -> "${ms / 86_400_000L} days ago"
}
