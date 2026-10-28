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
fun rememberCalendarBell(meta: Meta): CalendarBell? {
    if (!meta.isSeriesMeta() && !meta.isMovieMeta()) return null
    val context = LocalContext.current
    val repo = remember { repoOf(context) } ?: return null
    val follows by repo.follows.collectAsState()
    val settings by repo.settings.collectAsState()
    // The Calendar switched off (Settings → Menu & Features): no bell.
    if (SideMenuPages.CALENDAR in SideMenuPages.hidden(settings.sideMenuHidden)) return null
    val imdb = meta.imdbOrNull()
    val existing = follows.firstOrNull { it.id == meta.id || (imdb != null && (it.imdbId == imdb || it.id == imdb)) }
    val scope = rememberCoroutineScope()
    return CalendarBell(on = existing != null, toggle = {
        val follow = existing ?: CalendarFollow(
            id = imdb ?: meta.id,
            type = if (meta.isMovieMeta()) "movie" else "series",
            name = meta.name,
            poster = meta.poster,
            background = meta.background,
            imdbId = imdb
        )
        scope.launch {
            val on = repo.toggleFollow(follow)
            LiveTvNotice.show(
                when {
                    !on -> "Bell turned off for ${meta.name}"
                    meta.isMovieMeta() -> "Bell on: ${meta.name} is on your Calendar"
                    else -> "Bell on: new episodes of ${meta.name} will show on Home and in your Calendar"
                }
            )
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        androidx.tv.material3.Icon(Icons.Default.DateRange, contentDescription = null, tint = NuvioTheme.colors.TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        androidx.tv.material3.Text(text, color = NuvioTheme.colors.TextSecondary, fontSize = 14.sp, maxLines = 1)
        val e = entry
        val g = e?.guide
        if (e != null && g != null && g.stopMs > System.currentTimeMillis()) {
            Spacer(Modifier.width(12.dp))
            GuideChip(
                text = if (recording) "● Recording on ${g.channelName}" else "● Record on ${g.channelName}",
                onFocused = onFocused,
                onClick = {
                    LiveTvNotice.show(repo.record(e))
                    recording = repo.isRecording(e)
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideChip(text: String, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) NuvioTheme.colors.Secondary else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0f else 0.18f), shape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        androidx.tv.material3.Text(text, fontSize = 13.sp, color = if (focused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary)
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
