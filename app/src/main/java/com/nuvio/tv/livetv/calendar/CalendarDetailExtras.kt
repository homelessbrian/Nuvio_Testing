package com.nuvio.tv.livetv.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The bell on a show's or movie's page: on = it's on your Calendar, with new episodes on Home. */
data class CalendarBell(val on: Boolean, val toggle: () -> Unit)

private fun repoOf(context: android.content.Context): CalendarRepository? = runCatching {
    EntryPointAccessors.fromApplication(context.applicationContext, CalendarEntryPoint::class.java).calendarRepository()
}.getOrNull()

private fun Meta.isSeriesMeta() = CalendarRepository.isSeries(apiType)
private fun Meta.isMovieMeta() = apiType.equals("movie", ignoreCase = true)
private fun Meta.imdbOrNull() = imdbId?.takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") }

@Composable
fun rememberCalendarBell(meta: Meta): CalendarBell? = when {
    meta.isSeriesMeta() -> rememberShowBell(meta.id, meta.imdbOrNull(), meta.name, meta.poster, meta.background)
    meta.isMovieMeta() -> rememberMovieBell(meta)
    else -> null
}

/**
 * The bell on a movie's page: only while it isn't out to watch at home yet (in theaters, or not
 * released). When it comes out at home, a card in Continue Watching says so.
 */
@Composable
private fun rememberMovieBell(meta: Meta): CalendarBell? {
    val context = LocalContext.current
    val repo = remember { repoOf(context) } ?: return null
    val follows by repo.follows.collectAsState()
    val settings by repo.settings.collectAsState()
    if (SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.sideMenuHidden)) return null
    val imdb = meta.imdbOrNull()
    val existing = follows.firstOrNull { !it.isAiring && (it.id == meta.id || (imdb != null && (it.imdbId == imdb || it.id == imdb))) }
    var notOut by remember(meta.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(meta.id) {
        val today = LocalDate.now()
        val year = CalendarRepository.yearOf(meta.releaseInfo) ?: CalendarRepository.yearOf(meta.released)
        val d = repo.movieDates(meta.id, imdb)
        notOut = when {
            d == null -> year != null && year >= today.year
            d.digital != null -> d.digital.isAfter(today)
            // In theaters (or coming), with no date yet to watch at home.
            d.theater != null -> !d.theater.isBefore(today.minusDays(240))
            else -> year != null && year >= today.year
        }
    }
    // Already out at home: nothing to wait for (unless its bell was rung before: it can be turned off).
    if (existing == null && notOut != true) return null
    val scope = rememberCoroutineScope()
    return CalendarBell(on = existing != null, toggle = {
        val follow = existing ?: CalendarFollow(id = imdb ?: meta.id, type = "movie", name = meta.name, poster = meta.poster,
            background = meta.background, imdbId = imdb)
        scope.launch {
            val on = repo.toggleFollow(follow)
            LiveTvNotice.show(
                if (on) "Bell on: you'll get a card on Home when ${meta.name} is out to watch at home"
                else "Bell turned off for ${meta.name}"
            )
        }
    })
}

/**
 * The bell for a TV show (its page, a search result's hold-OK menu): on = followed, its new
 * episodes on the Calendar and on Home. Null when the Calendar is switched off.
 */
@Composable
fun rememberShowBell(id: String, imdbId: String?, name: String, poster: String?, background: String?): CalendarBell? {
    val context = LocalContext.current
    val repo = remember { repoOf(context) } ?: return null
    val follows by repo.follows.collectAsState()
    val settings by repo.settings.collectAsState()
    // The Calendar switched off (Settings → Menu & Features): no bell.
    if (SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.sideMenuHidden)) return null
    val imdb = imdbId?.takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") }
    val existing = follows.firstOrNull { it.id == id || (imdb != null && (it.imdbId == imdb || it.id == imdb)) }
    val scope = rememberCoroutineScope()
    return CalendarBell(on = existing != null, toggle = {
        val follow = existing ?: CalendarFollow(id = imdb ?: id, type = "series", name = name, poster = poster, background = background, imdbId = imdb)
        scope.launch {
            val on = repo.toggleFollow(follow)
            LiveTvNotice.show(if (on) "Bell on: new episodes of $name will show on Home and in your Calendar" else "Bell turned off for $name")
        }
    })
}

/**
 * Under a show's buttons: when its next episode airs ("Next episode: S2 · E5 · Fri 9:00 PM ·
 * FX"), with Record when it's in your Live TV guide. Movies: in theaters and at home dates.
 */
@Composable
fun CalendarNextLine(meta: Meta, onFocused: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { repoOf(context) } ?: return
    val settings by repo.settings.collectAsState()
    if (SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.sideMenuHidden)) return
    val series = meta.isSeriesMeta()
    if (!series && !meta.isMovieMeta()) return
    var line by remember(meta.id) { mutableStateOf<String?>(null) }
    var entry by remember(meta.id) { mutableStateOf<CalendarEntry?>(null) }
    var recording by remember(meta.id) { mutableStateOf(false) }
    LaunchedEffect(meta.id) {
        val imdb = meta.imdbOrNull()
        if (series) {
            val info = repo.showInfo(meta.id, imdb, meta.apiType) ?: return@LaunchedEffect
            val next = info.next
            entry = next
            recording = next?.let { repo.isRecording(it) } == true
            line = next?.let { describe(it) } ?: info.status
        } else {
            val d = repo.movieDates(meta.id, imdb) ?: return@LaunchedEffect
            val today = LocalDate.now()
            fun fmt(x: LocalDate) = x.format(DateTimeFormatter.ofPattern(if (x.year == today.year) "MMM d" else "MMM d, yyyy"))
            val parts = buildList {
                d.theater?.let { add((if (it.isAfter(today)) "In theaters " else "Was in theaters ") + fmt(it)) }
                when {
                    d.digital != null -> add((if (d.digital.isAfter(today)) "Out to watch at home " else "Out at home since ") + fmt(d.digital))
                    d.disc != null -> add((if (d.disc.isAfter(today)) "On disc " else "On disc since ") + fmt(d.disc))
                    d.theater != null && !d.theater.isBefore(today.minusDays(120)) -> add("No date yet to watch at home")
                }
            }
            // Only worth a line while it's new.
            val recent = listOfNotNull(d.theater, d.digital, d.disc).any { !it.isBefore(today.minusDays(45)) }
            line = if (recent && parts.isNotEmpty()) parts.joinToString(" · ") else null
        }
    }
    val text = line ?: return
    // On a dark backing of its own: it sits over the show's picture or trailer, which can be anything.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(start = 6.dp, end = 14.dp, top = 5.dp, bottom = 5.dp)
    ) {
        // Record first: Down from the page's buttons lands on it, right under Play.
        val e = entry
        val g = e?.guide
        if (e != null && g != null && g.stopMs > System.currentTimeMillis()) {
            GuideChip(
                text = if (recording) "Recording on ${g.channelName}" else "Record on ${g.channelName}",
                onFocused = onFocused,
                onClick = {
                    LiveTvNotice.show(repo.record(e))
                    recording = repo.isRecording(e)
                }
            )
            Spacer(Modifier.width(10.dp))
        } else Spacer(Modifier.width(6.dp))
        androidx.tv.material3.Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        androidx.tv.material3.Text(text, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideChip(text: String, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            // Solid, so it reads on any picture: white with dark text when highlighted.
            .background(if (focused) Color.White else Color(0xFF2A2A30))
            .border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.35f), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE53935)))
        Spacer(Modifier.width(7.dp))
        androidx.tv.material3.Text(
            text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = if (focused) Color(0xFF111111) else Color.White, maxLines = 1
        )
    }
}

private fun describe(e: CalendarEntry): String {
    val today = LocalDate.now()
    val day = when (ChronoUnit.DAYS.between(today, e.day)) {
        0L -> "Today"
        1L -> "Tomorrow"
        in 2L..6L -> e.day.format(DateTimeFormatter.ofPattern("EEEE"))
        else -> e.day.format(DateTimeFormatter.ofPattern(if (e.day.year == today.year) "EEE MMM d" else "EEE MMM d, yyyy"))
    }
    val time = e.airMs?.takeIf { !e.streaming }?.let {
        DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
    }
    val lead = when {
        CalendarTag.SERIES_PREMIERE in e.tags -> "Series premiere"
        CalendarTag.SEASON_PREMIERE in e.tags -> "Season ${e.season} premieres"
        CalendarTag.SEASON_FINALE in e.tags || CalendarTag.SERIES_FINALE in e.tags -> "Next episode (finale)"
        else -> "Next episode"
    }
    return buildString {
        append(lead).append(": ")
        if (CalendarTag.SEASON_PREMIERE !in e.tags) e.episodeCode?.let { append(it).append(" · ") }
        append(day)
        time?.let { append(", ").append(it) }
        e.network?.let { append(" · ").append(it) }
    }
}
