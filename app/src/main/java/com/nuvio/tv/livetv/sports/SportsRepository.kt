package com.nuvio.tv.livetv.sports

import android.content.Context
import android.util.Log
import com.nuvio.tv.livetv.data.EpgDatabase
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.data.LiveTvRepository
import com.nuvio.tv.livetv.model.LiveChannel
import com.nuvio.tv.livetv.model.LiveTvLoadReport
import com.nuvio.tv.livetv.parental.ParentalControls
import com.nuvio.tv.livetv.player.LiveTvPlaybackController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything behind the Sports page: today's games from ESPN (refreshed every 30 seconds while
 * a game is on and you're looking, much less otherwise), sports found in your guide, and which
 * of your channels show each one. Kept on the device so the page opens straight away.
 */
@Singleton
class SportsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val liveRepo: LiveTvRepository,
    private val channelStore: LiveTvChannelStore,
    private val guideDb: EpgDatabase,
    private val playback: LiveTvPlaybackController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val feeds = SportsFeeds()
    private val matcher = SportsMatcher(guideDb)
    private val dir = File(context.filesDir, "sports").apply { mkdirs() }

    val settings: StateFlow<SportsSettings> = prefs.sportsSettingsJson
        .map { SportsSettings.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsSettings())

    val follows: StateFlow<SportsFollows> = prefs.sportsMineJson
        .map { SportsFollows.fromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, SportsFollows())

    private class LeagueData(val events: List<SportsEvent>, val fetchedAt: Long, val error: String?)

    private val leagueData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    private val fixtureData = MutableStateFlow<Map<String, LeagueData>>(emptyMap())
    /** Shows found in the guide, by day (the day's start). */
    private val guideData = MutableStateFlow<Map<Long, List<SportsEvent>>>(emptyMap())

    /** Every game and event we know of, around today (yesterday to tomorrow). */
    val events: StateFlow<List<SportsEvent>> = combine(leagueData, fixtureData, guideData) { l, f, g ->
        val guide = g.values.flatten()
        // A sport's dates from sporting-events.org only show when the guide has nothing for it that day.
        val guideDays = guide.map { it.leagueId to dayOf(it.startMs) }.toSet()
        val fixtures = f.values.flatMap { it.events }.filter { (it.leagueId to dayOf(it.startMs)) !in guideDays }
        (l.values.flatMap { it.events } + fixtures + guide).distinctBy { it.id }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _matches = MutableStateFlow<Map<String, List<ChannelMatch>>>(emptyMap())
    /** Event id -> the channels showing it, best first. */
    val matches: StateFlow<Map<String, List<ChannelMatch>>> = _matches.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _matching = MutableStateFlow(false)
    /** Looking through your channels and guide (the first time takes a few seconds). */
    val matching: StateFlow<Boolean> = _matching.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    /** Something went wrong getting scores (shown quietly on the page). */
    val problem: StateFlow<String?> = _problem.asStateFlow()
    private val _updatedAt = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = _updatedAt.asStateFlow()

    private val viewers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)
    private val kick = MutableStateFlow(0)
    @Volatile private var started = false

    /** Starts the background updates (once). Called by the app as it starts. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            loadCache()
            runCatching { updateLoop() }.onFailure { Log.w(TAG, "Sports updates stopped", it) }
        }
        scope.launch { matchLoop() }
    }

    /** The Sports page (or a game) is on screen: keep scores fresh. */
    fun setViewing(on: Boolean) {
        viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }
        if (on) {
            liveRepo.ensureLoaded(updateCheckDelayMs = 20_000)
            kick.value++
        }
    }

    fun setForeground(on: Boolean) {
        foreground.value = on
        if (on) kick.value++
    }

    /** Update now (the page's refresh, a day picked). */
    fun refreshNow() {
        forceAll = true
        kick.value++
    }

    @Volatile private var forceAll = false

    // ------------------------------------------------------------------ which leagues

    /** Leagues on the Sports page, plus any league you follow a team or the whole league in. */
    fun activeLeagues(s: SportsSettings = settings.value, f: SportsFollows = follows.value): List<League> {
        val mine = f.teams.map { it.leagueId }.toSet() + f.leagues
        return SportsCatalog.ordered(s).filter { SportsCatalog.shown(it, s) || it.id in mine }
    }

    private fun isMine(l: League, f: SportsFollows) = l.id in f.leagues || f.teams.any { it.leagueId == l.id }

    // ------------------------------------------------------------------ updates

    private suspend fun updateLoop() {
        var lastKick = -1
        var lastFixtures = 0L
        while (true) {
            val active = foreground.value && (settings.value.showInSidebar || viewers.value > 0)
            if (active) runCatching { updateOnce(System.currentTimeMillis()) }.onFailure { Log.w(TAG, "Sports update failed", it) }
            if (active && System.currentTimeMillis() - lastFixtures > 12 * HOUR) {
                lastFixtures = System.currentTimeMillis()
                runCatching { updateFixtures() }
            }
            val wait = if (viewers.value > 0) 10_000L else 30_000L
            lastKick = kick.value
            withTimeoutOrNull(wait) { kick.first { it != lastKick } }
        }
    }

    private suspend fun updateOnce(now: Long) {
        val s = settings.value
        val f = follows.value
        val viewing = viewers.value > 0
        val force = forceAll.also { forceAll = false }
        val data = leagueData.value
        val playingKey = playback.state.value.channelKey
        val playingLeagues = if (playingKey == null) emptySet() else _matches.value
            .filter { (_, list) -> list.any { it.channel.key == playingKey && it.sure } }
            .keys.mapNotNull { id -> events.value.firstOrNull { it.id == id && it.state == GameState.LIVE }?.leagueId }.toSet()
        val due = activeLeagues(s, f).filter { l ->
            if (l.espnPath == null) return@filter false
            val d = data[l.id]
            val age = now - (d?.fetchedAt ?: 0L)
            val live = d?.events?.any { it.state == GameState.LIVE || (it.state == GameState.PRE && it.startMs in (now - 15 * MINUTE)..(now + 10 * MINUTE)) } == true
            val mine = isMine(l, f)
            when {
                force -> age > 15_000
                viewing -> age > (if (live) 30_000 else 5 * MINUTE)
                l.id in playingLeagues && s.scoreOnInfoBar -> age > 60_000
                mine && live -> age > (if (s.scoringAlerts) 45_000 else 90_000)
                mine -> age > 30 * MINUTE
                // Not looking and not yours: only so the page isn't empty when it opens.
                d == null -> SportsCatalog.shown(l, s) && age > 6 * HOUR
                else -> false
            }
        }
        if (due.isEmpty()) return
        _loading.value = true
        try {
            val (from, to) = espnRange(now)
            val days = espnDays(now)
            val gate = Semaphore(4)
            val failed = ArrayList<String>()
            val reasons = LinkedHashSet<String>()
            coroutineScopeFetch(due) { l ->
                gate.withPermit {
                    val r = runCatching { fetchScoreboard(l, from, to, days) }
                    r.onSuccess { raws ->
                        val parsed = raws.flatMap { raw -> runCatching { SportsFeeds.parseScoreboard(l, raw) }.getOrElse { emptyList() } }
                            .distinctBy { it.id }
                        val before = leagueData.value[l.id]?.events
                        leagueData.update { it + (l.id to LeagueData(parsed, System.currentTimeMillis(), null)) }
                        runCatching { File(dir, "sb_${safe(l.id)}.json").writeText("${System.currentTimeMillis()}\n" + raws.joinToString(RAW_SEP)) }
                        if (before != null) noticeScoring(l, before, parsed)
                    }.onFailure { e ->
                        synchronized(failed) { failed += l.name; reasons += describe(e) }
                        leagueData.update { m ->
                            val old = m[l.id]
                            m + (l.id to LeagueData(old?.events.orEmpty(), old?.fetchedAt ?: (System.currentTimeMillis() - 4 * MINUTE), e.message))
                        }
                    }
                }
            }
            val why = reasons.take(2).joinToString(", ")
            _problem.value = if (failed.size >= due.size && due.isNotEmpty()) "Couldn't reach the scores service ($why). Showing what was saved." else null
            if (failed.isNotEmpty()) {
                val line = "Sports: couldn't get scores for ${failed.take(6).joinToString(", ")}${if (failed.size > 6) " and ${failed.size - 6} more" else ""} ($why)"
                if (line != lastFailLine) { lastFailLine = line; LiveTvLoadReport.add(line) }
            } else lastFailLine = null
            _updatedAt.value = System.currentTimeMillis()
            rebuildLiveScores()
        } finally {
            _loading.value = false
        }
    }

    private var lastFailLine: String? = null

    /** Leagues whose feed wouldn't take a range of days (asked one day at a time from then on). */
    private val dayByDay: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /**
     * A league's games for the three days around today: one request for the whole range; if the
     * feed won't take that, one request per day; failing that, whatever day the feed picks.
     */
    private fun fetchScoreboard(l: League, from: String, to: String, days: List<String>): List<String> {
        var firstError: Throwable? = null
        if (l.id !in dayByDay) {
            val r = runCatching { feeds.scoreboardRaw(l, from, to) }
            r.getOrNull()?.let { return listOf(it) }
            firstError = r.exceptionOrNull()
        }
        val singles = days.mapNotNull { d ->
            runCatching { feeds.scoreboardDayRaw(l, d) }.onFailure { if (firstError == null) firstError = it }.getOrNull()
        }
        if (singles.isNotEmpty()) {
            if (l.id !in dayByDay) {
                dayByDay += l.id
                LiveTvLoadReport.add("Sports: ${l.name} doesn't take a range of days (${firstError?.let { describe(it) }}); asking one day at a time")
            }
            return singles
        }
        runCatching { feeds.scoreboardDefaultRaw(l) }.getOrNull()?.let { return listOf(it) }
        throw firstError ?: java.io.IOException("no answer")
    }

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "no internet or the address can't be found"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed: ${e.message?.take(60)}"
        else -> e.message?.take(80)?.ifBlank { null } ?: e.javaClass.simpleName
    }

    private suspend fun coroutineScopeFetch(list: List<League>, block: suspend (League) -> Unit) {
        kotlinx.coroutines.coroutineScope { list.map { l -> async { block(l) } }.awaitAll() }
    }

    private suspend fun updateFixtures() {
        val s = settings.value
        val leagues = activeLeagues(s).filter { it.fixtureSlug != null }
        for (l in leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            val raw = runCatching { feeds.fixturesRaw(slug) }.onSuccess { runCatching { file.writeText(it) } }.getOrNull()
                ?: runCatching { file.readText() }.getOrNull() ?: continue
            val list = runCatching { SportsFeeds.parseFixtures(l, raw) }.getOrDefault(emptyList())
            fixtureData.update { it + (l.id to LeagueData(list, System.currentTimeMillis(), null)) }
        }
    }

    private fun loadCache() {
        val now = System.currentTimeMillis()
        val m = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            if (l.espnPath == null) continue
            val file = File(dir, "sb_${safe(l.id)}.json")
            if (!file.exists()) continue
            runCatching {
                val text = file.readText()
                val nl = text.indexOf('\n')
                val at = text.substring(0, nl).toLong()
                if (now - at > 2 * DAY) return@runCatching
                // Saved a while ago: counts as old, so it's checked again when wanted.
                m[l.id] = LeagueData(
                    text.substring(nl + 1).split(RAW_SEP).flatMap { SportsFeeds.parseScoreboard(l, it) }.distinctBy { it.id }, at, null
                )
            }
        }
        val fx = HashMap<String, LeagueData>()
        for (l in SportsCatalog.leagues) {
            val slug = l.fixtureSlug ?: continue
            val file = File(dir, "fx_$slug.json")
            if (!file.exists()) continue
            runCatching { fx[l.id] = LeagueData(SportsFeeds.parseFixtures(l, file.readText()), 0L, null) }
        }
        leagueData.value = m
        fixtureData.value = fx
    }

    // ------------------------------------------------------------------ matching channels

    @OptIn(FlowPreview::class)
    private suspend fun matchLoop() {
        // Run again when the games, your channels, Sports settings or Live TV's state change…
        val trigger = combine(
            events.map { list -> list.filter { it.source != EventSource.GUIDE }.map { "${it.id}@${it.startMs}:${it.broadcasts.size}" } }.distinctUntilChanged(),
            channelStore.displayChannels.map { it.size to it.firstOrNull()?.key }.distinctUntilChanged(),
            settings.map { listOf(it.networkChannels, it.eventChannels, it.blocked, it.bestChannelFirst, it.hiddenLeagues, it.region) }.distinctUntilChanged(),
            follows.map { it.words to it.leagues }.distinctUntilChanged(),
            liveRepo.status.map { it.loading }.distinctUntilChanged()
        ) { a, b, c, d, e -> listOf(a, b, c, d, e) }
        var lastRun = 0L
        scope.launch {
            // …and every 15 minutes (shows move on; new guide listings).
            while (true) {
                delay(15 * MINUTE)
                if (foreground.value && System.currentTimeMillis() - lastRun > 10 * MINUTE) matchKick.value++
            }
        }
        combine(trigger, matchKick) { t, k -> t to k }.debounce(1_500).collect {
            if (!foreground.value && viewers.value == 0) return@collect
            if (!settings.value.showInSidebar && viewers.value == 0) return@collect
            if (liveRepo.status.value.loading && !liveRepo.status.value.quiet && _matches.value.isNotEmpty()) return@collect
            lastRun = System.currentTimeMillis()
            runCatching { runMatch() }.onFailure { Log.w(TAG, "Sports matching failed", it) }
        }
    }

    private val matchKick = MutableStateFlow(0)
    private var lastSummary: String? = null

    private suspend fun runMatch() = withContext(Dispatchers.Default) {
        val live = prefs.settings.first()
        val user = prefs.userState.first()
        val channels: List<LiveChannel> = channelStore.displayChannels.value.filter { ch ->
            ch.key !in user.hiddenChannels && ch.groupId !in user.hiddenGroups &&
                !ParentalControls.isLocked(ch.groupId, ch.group, live, user.lockedGroups) &&
                !(ParentalControls.enabled(live) && live.lockAdultContent && ParentalControls.isAdult(ch.name))
        }
        if (channels.isEmpty()) return@withContext
        _matching.value = true
        try {
            val now = System.currentTimeMillis()
            val shift = live.epgOffsetMinutes * MINUTE
            val s = settings.value
            val f = follows.value
            // Sports ESPN doesn't cover, and names you follow, from the guide: yesterday to tomorrow.
            val guideLeagues = activeLeagues(s, f).filter { it.guideOnly }
            val keys = channels.mapTo(HashSet()) { it.key }
            val days = HashMap<Long, List<SportsEvent>>()
            for (offset in -1..1) {
                val start = dayStart(offset)
                days[start] = runCatching {
                    matcher.guideEvents(start, dayStart(offset + 1), guideLeagues, f.words, keys, shift, now)
                }.getOrDefault(emptyList())
            }
            guideData.value = days
            val result = matcher.match(
                SportsMatcher.Input(
                    events = events.value,
                    channels = channels,
                    quality = user.channelQuality,
                    shiftMs = shift,
                    settings = s,
                    now = now
                )
            )
            _matches.value = result.matches
            if (result.summary != lastSummary) {
                lastSummary = result.summary
                LiveTvLoadReport.add(result.summary)
            }
            rebuildLiveScores()
        } finally {
            _matching.value = false
        }
    }

    // ------------------------------------------------------------------ scores elsewhere in the app

    /** Channel key -> the live game it's showing (sure matches only), for the Live TV info bar. */
    private fun rebuildLiveScores() {
        val evs = events.value.associateBy { it.id }
        val out = HashMap<String, SportsEvent>()
        for ((id, list) in _matches.value) {
            val ev = evs[id] ?: continue
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            list.filter { it.sure }.forEach { m -> out.putIfAbsent(m.channel.key, ev) }
        }
        SportsLive.byChannel.value = out
    }

    /** A team you follow scored: tell the app (Scoring Alerts). */
    private fun noticeScoring(league: League, before: List<SportsEvent>, after: List<SportsEvent>) {
        if (!settings.value.scoringAlerts) return
        val f = follows.value
        val old = before.associateBy { it.id }
        for (ev in after) {
            if (ev.state != GameState.LIVE || !ev.hasScore) continue
            val prev = old[ev.id] ?: continue
            if (!prev.hasScore) continue
            for (side in ev.sides) {
                if (!f.followsTeam(league.id, side.team.id) && league.id !in f.leagues) continue
                val was = prev.sides.firstOrNull { it.team.id == side.team.id }?.score?.toDoubleOrNull() ?: continue
                val nowScore = side.score?.toDoubleOrNull() ?: continue
                if (nowScore > was) SportsLive.alert(SportsAlert(ev, side.team, league))
            }
        }
    }

    // ------------------------------------------------------------------ teams, details

    private val teamCache = HashMap<String, List<TeamInfo>>()

    /** A league's teams (for Pick Your Teams): saved for a week, then fetched again. */
    suspend fun teams(league: League): List<TeamInfo> = withContext(Dispatchers.IO) {
        synchronized(teamCache) { teamCache[league.id] }?.let { return@withContext it }
        if (league.espnPath == null) return@withContext emptyList()
        val file = File(dir, "teams_${safe(league.id)}.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * DAY
        val raw = (if (fresh) runCatching { file.readText() }.getOrNull() else null)
            ?: runCatching { feeds.teamsRaw(league).also { runCatching { file.writeText(it) } } }.getOrNull()
            ?: runCatching { file.readText() }.getOrNull()
            ?: return@withContext emptyList()
        val list = runCatching { SportsFeeds.parseTeams(league, raw) }.getOrDefault(emptyList())
            .sortedBy { (it.location ?: it.fullName).lowercase(Locale.ROOT) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[league.id] = list }
        list
    }

    private val playsCache = HashMap<String, Pair<Long, List<ScoringPlay>>>()

    /** The game's scoring plays (touchdowns, goals…), when ESPN has them. */
    suspend fun scoringPlays(ev: SportsEvent): List<ScoringPlay> = withContext(Dispatchers.IO) {
        val league = SportsCatalog.league(ev.leagueId) ?: return@withContext emptyList()
        val id = ev.espnId ?: return@withContext emptyList()
        if (ev.state == GameState.PRE || league.kind != LeagueKind.TEAMS) return@withContext emptyList()
        synchronized(playsCache) { playsCache[ev.id] }?.let { (at, list) ->
            if (System.currentTimeMillis() - at < (if (ev.state == GameState.LIVE) 30_000 else 30 * MINUTE)) return@withContext list
        }
        val list = runCatching { feeds.summary(league, id) }.getOrNull() ?: return@withContext synchronized(playsCache) { playsCache[ev.id]?.second }.orEmpty()
        synchronized(playsCache) { playsCache[ev.id] = System.currentTimeMillis() to list }
        list
    }

    // ------------------------------------------------------------------ your choices

    fun updateSettings(transform: (SportsSettings) -> SportsSettings) {
        scope.launch { prefs.editSportsSettings { raw -> transform(SportsSettings.fromJson(raw)).toJson() } }
    }

    fun updateFollows(transform: (SportsFollows) -> SportsFollows) {
        scope.launch {
            prefs.editSportsMine { raw -> transform(SportsFollows.fromJson(raw)).toJson() }
            kick.value++
        }
    }

    fun toggleTeam(team: TeamInfo) = updateFollows { f ->
        if (f.followsTeam(team.leagueId, team.id)) f.copy(teams = f.teams.filterNot { it.leagueId == team.leagueId && it.teamId == team.id })
        else f.copy(teams = f.teams + FollowedTeam(team.leagueId, team.id, team.name, team.abbr, team.color, team.logo), setUp = true)
    }

    fun toggleLeague(leagueId: String) = updateFollows { f ->
        f.copy(leagues = if (leagueId in f.leagues) f.leagues - leagueId else f.leagues + leagueId, setUp = true)
    }

    fun addWord(word: String) {
        val w = word.trim()
        if (w.length < 3) return
        updateFollows { f -> if (f.words.any { it.equals(w, true) }) f else f.copy(words = f.words + w, setUp = true) }
    }

    fun removeWord(word: String) = updateFollows { f -> f.copy(words = f.words.filterNot { it.equals(word, true) }) }

    fun markSetUp() = updateFollows { it.copy(setUp = true) }

    fun isReminded(ev: SportsEvent): Boolean = follows.value.reminders.any { it.eventId == ev.id }

    fun toggleReminder(ev: SportsEvent) = updateFollows { f ->
        if (f.reminders.any { it.eventId == ev.id }) f.copy(reminders = f.reminders.filterNot { it.eventId == ev.id })
        else f.copy(reminders = (f.reminders.filter { it.startMs > System.currentTimeMillis() - 3 * HOUR } + SportsReminder(ev.id, ev.title, ev.startMs)))
    }

    fun dropReminder(eventId: String) = updateFollows { f -> f.copy(reminders = f.reminders.filterNot { it.eventId == eventId }) }

    /**
     * "This is the right channel" for [ev]. With [forNetworks], also for every game the same
     * network has from now on (FOX on your FOX 4K channel, say).
     */
    fun pickChannel(ev: SportsEvent, channelKey: String, forNetworks: Boolean) = updateSettings { s ->
        val keep = s.eventChannels.filterValues { v -> (v.substringAfter('|').toLongOrNull() ?: 0L) > System.currentTimeMillis() - 3 * DAY }
        s.copy(
            eventChannels = keep + (ev.id to "$channelKey|${ev.startMs}"),
            networkChannels = if (forNetworks) s.networkChannels + ev.broadcasts.associate { SportsMatcher.networkKey(it) to channelKey } else s.networkChannels,
            blocked = s.blocked - "${ev.id}|$channelKey"
        )
    }

    /** "Not this channel" for [ev] (and it stops being used for its network). */
    fun blockChannel(ev: SportsEvent, channelKey: String) = updateSettings { s ->
        s.copy(
            blocked = (s.blocked + "${ev.id}|$channelKey").toList().takeLast(400).toSet(),
            eventChannels = s.eventChannels.filterNot { (id, v) -> id == ev.id && v.substringBefore('|') == channelKey },
            networkChannels = s.networkChannels.filterNot { (n, key) -> key == channelKey && ev.broadcasts.any { SportsMatcher.networkKey(it) == n } }
        )
    }

    fun clearFixes() = updateSettings { it.copy(networkChannels = emptyMap(), eventChannels = emptyMap(), blocked = emptySet()) }

    /** The channel to tune for a reminder or "watch now": the best match, if any. */
    fun bestChannel(eventId: String): ChannelMatch? = _matches.value[eventId]?.firstOrNull()

    fun channelsForPicker(): List<LiveChannel> = channelStore.displayChannels.value

    // ------------------------------------------------------------------ days

    fun dayStart(offset: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    private fun dayOf(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** ESPN groups games by US Eastern days: yesterday to tomorrow there covers the page's three days. */
    private fun espnRange(now: Long): Pair<String, String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return f.format(java.util.Date(now - DAY)) to f.format(java.util.Date(now + DAY))
    }

    /** The ESPN (US Eastern) dates for yesterday, today and tomorrow. */
    private fun espnDays(now: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return listOf(now - DAY, now, now + DAY).map { f.format(java.util.Date(it)) }
    }

    private fun safe(id: String) = id.replace(Regex("[^a-zA-Z0-9]+"), "_")

    companion object {
        private const val TAG = "Sports"
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        /** Between saved answers when a league was asked one day at a time. */
        private const val RAW_SEP = "\n\u0001\n"
    }
}

/** A team you follow just scored (shown for a few seconds wherever you are). */
data class SportsAlert(val event: SportsEvent, val team: TeamInfo, val league: League, val id: Long = System.nanoTime())

/** Sports data the rest of the app shows: scores on Live TV's info bar, scoring alerts. */
object SportsLive {
    val byChannel = MutableStateFlow<Map<String, SportsEvent>>(emptyMap())
    val alerts = MutableStateFlow<SportsAlert?>(null)
    fun alert(a: SportsAlert) { alerts.value = a }
}
