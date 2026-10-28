package com.nuvio.tv.livetv.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import com.nuvio.tv.livetv.record.LiveTvNotice
import com.nuvio.tv.livetv.record.LiveTvRecordingScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    val repo: CalendarRepository,
    private val livePrefs: LiveTvPreferences,
    private val playback: LiveTvPlaybackController,
    scheduler: LiveTvRecordingScheduler
) : ViewModel() {
    val state = repo.state
    val airing = repo.airing
    val follows = repo.follows
    val hidden = repo.hiddenShows
    val settings = repo.settings

    /** Recordings set (so Record / Cancel recording stays right). */
    val schedules = scheduler.schedules

    val use24h: StateFlow<Boolean> = livePrefs.settings.map { it.use24HourClock }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now

    init {
        repo.start()
        // Opened: a fresh look if the last one was a while ago.
        if (System.currentTimeMillis() - repo.state.value.updatedAt > 30 * 60_000L) repo.refresh(force = false)
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    fun refresh() = repo.refresh(force = true)

    fun loadAiring(force: Boolean = false) = repo.loadAiring(force = force)

    fun setView(view: String) {
        viewModelScope.launch { livePrefs.updateSettings { it.copy(calendarView = view) } }
    }

    /** Airing Today's "News & talk" filter: leaves news and talk shows out (or puts them back). */
    fun setSkipNewsTalk(skip: Boolean) {
        viewModelScope.launch {
            livePrefs.updateSettings { it.copy(calendarSkipNewsTalk = skip) }
            delay(300) // the saved setting reaches the Calendar first
            repo.loadAiring(force = true)
        }
    }

    fun toggleFollow(e: CalendarEntry) {
        // A movie airing from Live TV: its own bell.
        val airing = repo.follows.value.firstOrNull { it.id == e.key }
        if (airing != null) { toggleFollow(airing); return }
        toggleFollow(
            CalendarFollow(id = e.contentId, type = e.type, name = e.title, poster = e.poster, background = e.background,
                imdbId = e.contentId.takeIf { it.startsWith("tt") }, addonBaseUrl = e.addonBaseUrl)
        )
    }

    fun toggleFollow(f: CalendarFollow) {
        viewModelScope.launch {
            val on = repo.toggleFollow(f)
            LiveTvNotice.show(
                when {
                    f.isAiring && on -> "${f.name} is on your Calendar"
                    f.isAiring -> "${f.name} taken off your Calendar"
                    on -> "Bell on: new episodes of ${f.name} will show on Home and in your Calendar"
                    else -> "Bell turned off for ${f.name}"
                }
            )
        }
    }

    fun hide(e: CalendarEntry) {
        viewModelScope.launch {
            repo.hide(e.contentId, e.title)
            LiveTvNotice.show("${e.title} is hidden from the Calendar (Hidden shows brings it back)")
        }
    }

    fun unhide(h: CalendarHidden) {
        viewModelScope.launch { repo.unhide(h.id); repo.refresh(force = false) }
    }

    fun record(e: CalendarEntry) {
        LiveTvNotice.show(repo.record(e))
    }

    /** The channel remembered for each network. */
    val channelPicks = repo.channelPicks

    fun record(e: CalendarEntry, hit: CalendarGuideHit) {
        LiveTvNotice.show(repo.record(e, hit))
    }

    /** Tunes Live TV to [hit]'s channel. False when it isn't there any more. */
    fun watchLive(hit: CalendarGuideHit): Boolean {
        val ch = repo.channelOf(hit) ?: return false
        playback.play(ch)
        viewModelScope.launch { runCatching { livePrefs.recordWatched(ch.key) } }
        return true
    }

    /** "Always use the channel I pick for FX": remembered (or forgotten with null). */
    fun setChannelPick(network: String?, hit: CalendarGuideHit?) {
        viewModelScope.launch { repo.setChannelPick(network, hit) }
    }

    /** Tunes Live TV to the episode's channel. False when it isn't there. */
    fun watchLive(e: CalendarEntry): Boolean {
        val ch = repo.channelFor(e) ?: return false
        playback.play(ch)
        viewModelScope.launch { runCatching { livePrefs.recordWatched(ch.key) } }
        return true
    }
}
