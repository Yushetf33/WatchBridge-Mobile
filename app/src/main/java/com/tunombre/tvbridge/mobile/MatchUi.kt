package com.tunombre.tvbridge.mobile

import android.content.Context
import android.text.format.DateFormat

/** Textos de un partido en directo, compartidos por el panel del EPG
 * (IptvChannelsFragment) y el panel de pantalla completa (IptvActivity) para
 * que los dos digan lo mismo del mismo partido. */
object MatchUi {

    data class Headline(val score: String, val status: String, val live: Boolean)

    /** "2 - 1" + minuto/estado si ya empezó; "vs" + hora de inicio si no. */
    fun headline(context: Context, state: SportsDbClient.MatchState?, startMs: Long): Headline {
        val started = state != null && state.phase != SportsDbClient.MatchPhase.NOT_STARTED &&
            state.score1 != null && state.score2 != null
        if (!started) {
            // Ya ha empezado pero no hay marcador (la API no cubre el deporte o no responde): se dice que esta en
            // directo en vez de "Empieza a las ..." con una hora ya pasada.
            if (startMs <= System.currentTimeMillis()) {
                return Headline(context.getString(R.string.iptv_sports_vs), context.getString(R.string.iptv_match_live_no_score), true)
            }
            val time = DateFormat.format("HH:mm", startMs).toString()
            return Headline(context.getString(R.string.iptv_sports_vs), context.getString(R.string.iptv_match_kickoff, time), false)
        }
        val status = when (state!!.phase) {
            SportsDbClient.MatchPhase.LIVE ->
                state.minute?.let { context.getString(R.string.iptv_match_minute, it) } ?: state.label ?: ""
            SportsDbClient.MatchPhase.HALF_TIME -> context.getString(R.string.iptv_match_halftime)
            else -> context.getString(R.string.iptv_match_final)
        }
        return Headline("${state.score1} - ${state.score2}", status, state.phase == SportsDbClient.MatchPhase.LIVE)
    }

    /** "⚽ 23'  Sučić · Real Sociedad" */
    fun eventLine(context: Context, item: SportsDbClient.TimelineItem, team1: SportsDbClient.Team, team2: SportsDbClient.Team): String {
        val icon = when (item.kind) {
            SportsDbClient.TimelineKind.GOAL -> "⚽"
            SportsDbClient.TimelineKind.YELLOW -> "🟨"
            SportsDbClient.TimelineKind.RED -> "🟥"
        }
        val lower = item.detail.lowercase()
        val suffix = when {
            item.kind != SportsDbClient.TimelineKind.GOAL -> ""
            lower.contains("own") -> " " + context.getString(R.string.iptv_match_own_goal)
            lower.contains("penalty") -> " " + context.getString(R.string.iptv_match_penalty)
            else -> ""
        }
        val teamName = when (item.teamId) {
            team1.id -> team1.name
            team2.id -> team2.name
            else -> ""
        }
        val tail = if (teamName.isNotEmpty()) " · $teamName" else ""
        return "$icon ${item.minute}'  ${item.player}$suffix$tail"
    }
}
