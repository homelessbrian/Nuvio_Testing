package com.nuvio.tv.livetv.sports

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** A goal, touchdown or basket worth listing on the game page. */
data class ScoringPlay(val clock: String, val teamAbbr: String?, val text: String, val awayScore: String?, val homeScore: String?)

/**
 * The free data sources: ESPN's public scoreboard feed (scores, clocks, logos, team colors and
 * US networks; no key) and sporting-events.org (dates of events ESPN doesn't cover).
 */
internal class SportsFeeds {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw java.io.IOException("empty answer")
        }
    }

    // ------------------------------------------------------------------ ESPN

    /** Raw scoreboard for [league] over the days [fromDay]..[toDay] ("yyyyMMdd", US Eastern days). */
    fun scoreboardRaw(league: League, fromDay: String, toDay: String): String =
        get("$ESPN${league.espnPath}/scoreboard?dates=$fromDay-$toDay&limit=1000${league.espnQuery}")

    /** One day only (for leagues whose feed won't take a range of days). */
    fun scoreboardDayRaw(league: League, day: String): String =
        get("$ESPN${league.espnPath}/scoreboard?dates=$day&limit=1000${league.espnQuery}")

    /** The feed's own pick of days (no date asked for): the last resort. */
    fun scoreboardDefaultRaw(league: League): String =
        get("$ESPN${league.espnPath}/scoreboard?limit=1000${league.espnQuery}")

    fun teamsRaw(league: League): String = get("$ESPN${league.espnPath}/teams?limit=1000")

    fun summary(league: League, espnId: String): List<ScoringPlay> =
        parseSummary(JSONObject(get("$ESPN${league.espnPath}/summary?event=$espnId")))

    // ------------------------------------------------------------------ sporting-events.org

    fun fixturesRaw(slug: String): String = get("https://sporting-events.org/data/$slug.json")

    companion object {
        private const val ESPN = "https://site.api.espn.com/apis/site/v2/sports/"

        private val iso = listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        fun parseIso(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            for (p in iso) {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
                runCatching { return f.parse(s)?.time }
            }
            return null
        }

        fun hexColor(s: String?): Long? {
            val h = s?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { 0xFF000000L or it }
        }

        private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }

        fun team(league: League, t: JSONObject?, athlete: JSONObject? = null): TeamInfo? {
            if (t != null) {
                val logo = t.str("logo") ?: t.optJSONArray("logos")?.let { pickLogo(it) }
                return TeamInfo(
                    leagueId = league.id,
                    id = t.str("id") ?: return null,
                    name = t.str("shortDisplayName") ?: t.str("name") ?: t.str("displayName") ?: return null,
                    fullName = t.str("displayName") ?: t.str("name") ?: "",
                    abbr = t.str("abbreviation") ?: "",
                    location = t.str("location"),
                    color = hexColor(t.str("color")),
                    altColor = hexColor(t.str("alternateColor")),
                    logo = logo,
                    record = t.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.str("summary")
                )
            }
            if (athlete != null) {
                val name = athlete.str("displayName") ?: athlete.str("fullName") ?: return null
                return TeamInfo(
                    leagueId = league.id,
                    id = athlete.str("id") ?: name,
                    name = athlete.str("shortName") ?: name,
                    fullName = name,
                    abbr = name.split(' ').lastOrNull()?.take(3)?.uppercase() ?: "",
                    location = null,
                    color = null,
                    altColor = null,
                    logo = athlete.optJSONObject("headshot")?.str("href") ?: athlete.optJSONObject("flag")?.str("href")
                )
            }
            return null
        }

        /** The normal logo (not the one made for dark backgrounds: ours sit on a light disc). */
        private fun pickLogo(logos: JSONArray): String? {
            var first: String? = null
            for (i in 0 until logos.length()) {
                val l = logos.optJSONObject(i) ?: continue
                val href = l.str("href") ?: continue
                if (first == null) first = href
                val rel = l.optJSONArray("rel").strings()
                if ("default" in rel && "dark" !in rel) return href
            }
            return first
        }

        private val CLOCK_FIRST = Regex("""^(\d{1,2}:\d{2}(?:\.\d)?)\s*-\s*(.+)$""")

        fun parseTeams(league: League, raw: String): List<TeamInfo> {
            val teams = JSONObject(raw).optJSONArray("sports")?.optJSONObject(0)
                ?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
            return (0 until teams.length()).mapNotNull { i -> team(league, teams.optJSONObject(i)?.optJSONObject("team")) }
                .filter { it.fullName.isNotBlank() }
        }

        fun parseScoreboard(league: League, raw: String): List<SportsEvent> {
            val events = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                runCatching { parseEvent(league, ev) }.getOrNull()?.let { out += it }
            }
            return out
        }

        private fun parseEvent(league: League, ev: JSONObject): SportsEvent? {
            val espnId = ev.str("id") ?: return null
            val start = parseIso(ev.str("date")) ?: return null
            val end = parseIso(ev.str("endDate"))
            val comp = ev.optJSONArray("competitions")?.optJSONObject(0)
            val st = comp?.optJSONObject("status") ?: ev.optJSONObject("status")
            val type = st?.optJSONObject("type")
            val stateRaw = type?.str("state")
            val name = type?.str("name").orEmpty()
            val state = when {
                Regex("POSTPONED|CANCEL|SUSPENDED|FORFEIT|ABANDON").containsMatchIn(name) -> GameState.OFF
                stateRaw == "in" -> GameState.LIVE
                stateRaw == "post" -> GameState.POST
                else -> GameState.PRE
            }
            val short = type?.str("shortDetail") ?: type?.str("detail")
            val status = when (state) {
                GameState.LIVE -> short?.let { s -> CLOCK_FIRST.matchEntire(s)?.let { "${it.groupValues[2]} · ${it.groupValues[1]}" } ?: s }
                GameState.POST -> short ?: "Final"
                GameState.OFF -> type?.str("description") ?: "Postponed"
                GameState.PRE -> null
            }
            val period = st?.optInt("period", 0) ?: 0
            val clock = st?.optDouble("clock", Double.NaN) ?: Double.NaN
            val progress = if (state == GameState.LIVE) progressOf(league, period, clock, st?.str("displayClock")) else null

            val broadcasts = LinkedHashSet<String>()
            comp?.optJSONArray("broadcasts")?.let { a ->
                for (b in 0 until a.length()) broadcasts += a.optJSONObject(b)?.optJSONArray("names").strings()
            }
            comp?.optJSONArray("geoBroadcasts")?.let { a ->
                for (b in 0 until a.length()) a.optJSONObject(b)?.optJSONObject("media")?.str("shortName")?.let { broadcasts += it }
            }
            val venue = comp?.optJSONObject("venue")?.str("fullName") ?: ev.optJSONObject("circuit")?.str("fullName")
            val week = ev.optJSONObject("week")?.optInt("number", 0)?.takeIf { it > 0 }?.let { "Week $it" }
            val headline = comp?.optJSONArray("notes")?.optJSONObject(0)?.str("headline")

            if (league.kind == LeagueKind.EVENT) {
                val multiDay = end != null && end - start > 20 * 3_600_000L
                return SportsEvent(
                    id = "espn:${league.id}:$espnId",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = !multiDay,
                    state = state,
                    title = ev.str("name") ?: ev.str("shortName") ?: league.name,
                    subtitle = listOfNotNull(headline, venue).joinToString(" · ").ifBlank { null },
                    status = status,
                    away = null, home = null,
                    broadcasts = broadcasts.toList(),
                    espnId = espnId,
                    endMs = end,
                    note = if (multiDay && end != null) "Until " + SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(java.util.Date(end)) else null
                )
            }

            val competitors = comp?.optJSONArray("competitors") ?: return null
            var home: Side? = null
            var away: Side? = null
            for (c in 0 until competitors.length()) {
                val co = competitors.optJSONObject(c) ?: continue
                val team = team(league, co.optJSONObject("team"), co.optJSONObject("athlete")) ?: continue
                val scoreAny = co.opt("score")
                val score = when (scoreAny) {
                    is JSONObject -> scoreAny.str("displayValue")
                    is String -> scoreAny.takeIf { it.isNotBlank() }
                    is Number -> scoreAny.toInt().toString()
                    else -> null
                }?.takeIf { state != GameState.PRE }
                val periods = co.optJSONArray("linescores")?.let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        val l = a.optJSONObject(k) ?: return@mapNotNull null
                        l.str("displayValue") ?: l.optDouble("value", Double.NaN).takeIf { !it.isNaN() }?.toInt()?.toString()
                    }
                }.orEmpty()
                val side = Side(
                    team = team,
                    score = score,
                    record = co.optJSONArray("records")?.optJSONObject(0)?.str("summary"),
                    winner = co.optBoolean("winner", false),
                    home = co.str("homeAway") == "home",
                    periods = periods
                )
                if (side.home && home == null) home = side else if (away == null) away = side else if (home == null) home = side
            }
            val h: Side = home ?: return null
            val a: Side = away ?: return null

            val situation = comp?.optJSONObject("situation")?.let { s ->
                val dd = s.str("downDistanceText") ?: s.str("shortDownDistanceText")
                val pos = s.str("possession")
                val who = listOf(h, a).firstOrNull { it.team.id == pos }?.team?.abbr
                when {
                    dd != null && who != null -> "$who ball · $dd"
                    dd != null -> dd
                    else -> null
                }
            }?.takeIf { state == GameState.LIVE }

            val sep = if (league.sport.homeFirst) " v " else " at "
            val title = if (league.sport.homeFirst) "${h.team.name}$sep${a.team.name}" else "${a.team.name}$sep${h.team.name}"
            return SportsEvent(
                id = "espn:${league.id}:$espnId",
                leagueId = league.id,
                startMs = start,
                hasTime = true,
                state = state,
                title = title,
                subtitle = listOfNotNull(headline, week, venue).joinToString(" · ").ifBlank { null },
                status = status,
                away = a,
                home = h,
                broadcasts = broadcasts.toList(),
                situation = situation,
                progress = progress,
                espnId = espnId
            )
        }

        /** How far through the game the clock is (0..1), from the period and the clock. */
        private fun progressOf(league: League, period: Int, clockSec: Double, displayClock: String?): Float? {
            if (period <= 0) return null
            fun countdown(periods: Int, len: Double): Float? {
                if (clockSec.isNaN()) return null
                val done = (period - 1).coerceAtMost(periods) * len + (len - clockSec.coerceIn(0.0, len))
                return (done / (periods * len)).toFloat().coerceIn(0f, 1f)
            }
            return when (league.sport) {
                Sport.AMERICAN_FOOTBALL -> countdown(4, 900.0)
                Sport.BASKETBALL -> when (league.id) {
                    "basketball/nba" -> countdown(4, 720.0)
                    "basketball/mens-college-basketball" -> countdown(2, 1200.0)
                    else -> countdown(4, 600.0)
                }
                Sport.HOCKEY -> countdown(3, 1200.0)
                Sport.BASEBALL -> ((period - 0.5f) / 9f).coerceIn(0f, 1f)
                Sport.FOOTBALL -> displayClock?.let { Regex("""^(\d+)""").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
                    ?.let { (it / 90f).coerceIn(0f, 1f) }
                else -> null
            }
        }

        fun parseSummary(root: JSONObject): List<ScoringPlay> {
            val out = ArrayList<ScoringPlay>()
            root.optJSONArray("scoringPlays")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    val period = p.optJSONObject("period")?.optInt("number", 0) ?: 0
                    val clock = p.optJSONObject("clock")?.str("displayValue")
                    val type = p.optJSONObject("scoringType")?.str("abbreviation") ?: p.optJSONObject("type")?.str("abbreviation")
                    out += ScoringPlay(
                        clock = listOfNotNull(if (period > 0) periodLabel(period) else null, clock).joinToString(" "),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = listOfNotNull(type, p.str("text")).joinToString(" · "),
                        awayScore = p.opt("awayScore")?.toString(),
                        homeScore = p.opt("homeScore")?.toString()
                    )
                }
            }
            if (out.isEmpty()) root.optJSONArray("keyEvents")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.optJSONObject(i) ?: continue
                    val goal = p.optBoolean("scoringPlay", false) || p.optJSONObject("type")?.str("text")?.contains("Goal", true) == true
                    if (!goal) continue
                    out += ScoringPlay(
                        clock = p.optJSONObject("clock")?.str("displayValue").orEmpty(),
                        teamAbbr = p.optJSONObject("team")?.let { it.str("abbreviation") ?: it.str("displayName") },
                        text = p.str("text") ?: p.optJSONObject("type")?.str("text") ?: "Goal",
                        awayScore = null,
                        homeScore = null
                    )
                }
            }
            return out
        }

        private fun periodLabel(p: Int): String = when (p) {
            1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${p}th"
        }

        /** sporting-events.org: date-only (or dated and timed) events for a sport ESPN doesn't cover. */
        fun parseFixtures(league: League, raw: String): List<SportsEvent> {
            val arr = JSONObject(raw).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<SportsEvent>()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val date = e.str("date_utc") ?: continue
                val time = e.str("time_utc")
                val name = e.str("fixture") ?: continue
                val start: Long = if (time != null) {
                    parseIso("${date}T${time.take(5)}Z") ?: continue
                } else {
                    // A date only: that day on the TV's own calendar.
                    val p = date.split('-').mapNotNull { it.toIntOrNull() }
                    if (p.size != 3) continue
                    Calendar.getInstance().apply { clear(); set(p[0], p[1] - 1, p[2], 0, 0, 0) }.timeInMillis
                }
                val home = e.str("home_team")
                val awayTeam = e.str("away_team")
                out += SportsEvent(
                    id = "fx:${league.id}:${e.optString("match", i.toString())}:$date",
                    leagueId = league.id,
                    startMs = start,
                    hasTime = time != null,
                    state = if (e.str("status")?.contains("finish", true) == true) GameState.POST else GameState.PRE,
                    title = if (home != null && awayTeam != null) "$home v $awayTeam" else name,
                    subtitle = listOfNotNull(e.str("city"), e.str("country")).joinToString(", ").ifBlank { null },
                    status = null,
                    away = null, home = null,
                    broadcasts = listOfNotNull(e.str("broadcast")),
                    source = EventSource.FIXTURES,
                    note = if (time == null) "Starts today" else null
                )
            }
            return out
        }
    }
}
