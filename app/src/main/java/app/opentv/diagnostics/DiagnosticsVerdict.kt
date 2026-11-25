/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.diagnostics

import java.util.Locale

enum class Severity { GOOD, INFO, WARN, BAD }

/** One plain-language conclusion drawn from the measurements, with what to do about it. */
data class Finding(val severity: Severity, val title: String, val detail: String)

/**
 * Turns the raw measurements into answers to the only question a viewer has: *why is it buffering,
 * and whose fault is it?*
 *
 * Everything here is a pure function of recorded sessions and test results, so the reasoning can be
 * unit-tested and never depends on the state of the network at the moment the screen is opened.
 * The rules are deliberately conservative: a finding only blames something when the numbers
 * separate it from the alternatives, because "it's your Wi-Fi" said wrongly sends people off to buy
 * routers they do not need.
 */
object DiagnosticsVerdict {

    fun mbps(bps: Long): String = when {
        bps < 50_000 -> "–"
        bps < 10_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> String.format(Locale.US, "%d Mbps", bps / 1_000_000)
    }

    /**
     * @param sessions recent viewing, newest first
     * @param probes recent test results, newest first
     * @param sourceNames provider display names by source id
     */
    fun findings(
        sessions: List<StreamSession>,
        probes: List<ProbeResult>,
        sourceNames: Map<Long, String>,
    ): List<Finding> {
        val out = ArrayList<Finding>()
        fun nameOf(sourceId: Long) = sourceNames[sourceId] ?: "this provider"

        // ---- The internet connection itself ------------------------------------------------
        val internet = probes.firstOrNull { it.kind == ProbeResult.KIND_INTERNET }
        var internetHealthy = false
        if (internet != null) {
            when {
                !internet.ok -> out += Finding(
                    Severity.BAD, "This device could not reach the internet",
                    "The speed test failed: ${internet.error ?: "no response"}. Nothing will stream until that is fixed — " +
                        "check the network cable or Wi-Fi on this device.",
                )
                internet.throughputBps < SLOW_INTERNET_BPS -> out += Finding(
                    Severity.BAD, "Your internet connection is slow (${mbps(internet.throughputBps)})",
                    "HD channels need a steady 8 Mbps and 4K needs about 25. At this speed buffering is expected " +
                        "whatever the provider does. Lower-quality versions of a channel will play more smoothly.",
                )
                internet.throughputBps < MODEST_INTERNET_BPS -> out += Finding(
                    Severity.WARN, "Your internet is fast enough for HD, tight for 4K (${mbps(internet.throughputBps)})",
                    "HD should be fine. 4K streams need about 25 Mbps to themselves, so they may buffer when anything " +
                        "else in the house is using the connection.",
                )
                else -> internetHealthy = true
            }
            if (internet.ok && (internet.jitterMillis > HIGH_JITTER_MILLIS || internet.latencyMillis > HIGH_LATENCY_MILLIS)) {
                internetHealthy = false
                val wifi = internet.transport == "wifi"
                out += Finding(
                    Severity.WARN,
                    "The connection is unsteady (${internet.latencyMillis} ms, ±${internet.jitterMillis} ms)",
                    if (wifi) {
                        "Response times are swinging, which on Wi-Fi usually means a weak or crowded signal. Live " +
                            "streams cope badly with that. A network cable to this device, or moving it nearer the " +
                            "router, is the fix that works."
                    } else {
                        "Response times are swinging even though this device is not on Wi-Fi. That points at the " +
                            "line itself or something else in the house saturating it."
                    },
                )
            }
        }

        // ---- Each provider's record --------------------------------------------------------
        val byProvider = sessions.filter { it.sourceId != 0L }.groupBy { it.sourceId }
            .mapValues { HealthSummary.of(it.value) }
            .filterValues { it.confident }
        val poor = byProvider.filterValues { gradeOf(it.score()) == HealthGrade.POOR }
        val good = byProvider.filterValues { gradeOf(it.score()) == HealthGrade.GOOD }
        for ((sourceId, summary) in poor) {
            // Counts, not hourly rates: a rate worked out from a few minutes of viewing reads as
            // far worse (or better) than what the viewer actually sat through.
            val minutes = (summary.watchedMillis / 60_000).coerceAtLeast(1)
            val record = buildString {
                append("it stopped to buffer ${summary.stallCount} ${if (summary.stallCount == 1) "time" else "times"}")
                append(" (${summary.stallMillis / 1000} s of waiting)")
                if (summary.reconnects > 0) append(" and dropped the connection ${summary.reconnects} ${if (summary.reconnects == 1) "time" else "times"}")
                if (summary.failedStarts > 0) append(", and ${summary.failedStarts} ${if (summary.failedStarts == 1) "channel" else "channels"} never started")
                append(" in $minutes ${if (minutes == 1L) "minute" else "minutes"} of viewing")
            }
            val detail = when {
                good.isNotEmpty() ->
                    "Over the same period ${nameOf(good.keys.first())} played cleanly on this same device and " +
                        "network, so the problem is on ${nameOf(sourceId)}'s side: an overloaded server or a poor " +
                        "route to it. Nothing in this house will fix that."
                internetHealthy ->
                    "Your internet tested healthy (${mbps(internet!!.throughputBps)}), so the bottleneck is between " +
                        "your ISP and the provider's server, or the server itself. Try another version of the " +
                        "channel, or run the VPN comparison below to see whether a different route helps."
                else ->
                    "Run the speed test below to find out whether this is the provider or your own connection."
            }
            out += Finding(Severity.BAD, "${nameOf(sourceId)} is buffering", "With ${nameOf(sourceId)}, $record. $detail")
        }

        // ---- "It plays, then jumps back a few seconds" -------------------------------------
        val reconnects = sessions.sumOf { it.reconnects }
        val jumps = sessions.sumOf { it.backwardJumps }
        if (reconnects >= MIN_RECONNECTS_TO_REPORT) {
            val worstHost = sessions.filter { it.reconnects > 0 }.groupBy { it.host }
                .maxByOrNull { e -> e.value.sumOf { it.reconnects } }?.key.orEmpty()
            out += Finding(
                Severity.WARN,
                "The picture jumping back a few seconds: found the cause",
                "The provider's server closed the stream mid-programme $reconnects times recently" +
                    (if (worstHost.isNotEmpty()) " (mostly $worstHost)" else "") +
                    ". Each time, the app reconnects by itself within a second or two, and the server starts the " +
                    "new connection slightly behind where the old one ended — so you see the last few seconds again" +
                    (if (jumps > 0) " ($jumps replays were measured directly)" else "") +
                    ". It is the provider dropping connections, usually because the server is overloaded or the " +
                    "account is being used on a second device.",
            )
        }

        // ---- Stream tests ------------------------------------------------------------------
        val streamProbes = probes.filter { it.kind == ProbeResult.KIND_STREAM }.distinctBy { it.channelId to it.vpn }
        for (p in streamProbes.filter { it.ok && it.bitrateBps > 0 && it.throughputBps > 0 }.take(3)) {
            val headroom = p.throughputBps.toDouble() / p.bitrateBps
            if (headroom < MIN_HEADROOM) {
                val blame = if (internetHealthy && internet!!.throughputBps > p.throughputBps * 3) {
                    "Your internet managed ${mbps(internet.throughputBps)} in the same test, so it is the provider's " +
                        "server that cannot send this stream fast enough."
                } else {
                    "There is no margin, so any dip becomes a stall."
                }
                out += Finding(
                    Severity.BAD,
                    "${p.label} arrives too slowly to play smoothly",
                    "The stream needs ${mbps(p.bitrateBps)} and ${p.host} delivered ${mbps(p.throughputBps)}. $blame",
                )
            }
        }

        // ---- Provider servers --------------------------------------------------------------
        val portals = probes.filter { it.kind == ProbeResult.KIND_PORTAL }.distinctBy { it.sourceId to it.vpn }
        for (p in portals.filter { !it.vpn }.ifEmpty { portals }) {
            when {
                !p.ok -> out += Finding(
                    Severity.BAD, "${p.label}'s server did not answer",
                    "${p.error ?: "No response"}. If the channels of this provider will not start at all, this is why.",
                )
                p.dnsMillis > SLOW_DNS_MILLIS -> out += Finding(
                    Severity.WARN, "Looking up ${p.label}'s address is slow (${p.dnsMillis} ms)",
                    "That delay is added to every channel change. Setting this device's or your router's DNS to " +
                        "1.1.1.1 or 8.8.8.8 usually removes it.",
                )
                p.ttfbMillis > SLOW_TTFB_MILLIS -> out += Finding(
                    Severity.WARN, "${p.label}'s server is slow to respond (${p.ttfbMillis} ms)",
                    "A healthy server answers in well under half a second. Slow answers make channels slow to start " +
                        "and are a sign the server is busy.",
                )
            }
        }

        // ---- Damaged at source ---------------------------------------------------------------
        val gaps = sessions.sumOf { it.streamGaps }
        val gapHours = sessions.sumOf { it.watchedMillis } / 3_600_000.0
        if (gaps >= MIN_STREAM_GAPS && gapHours > 0 && gaps / gapHours >= STREAM_GAPS_PER_HOUR) {
            val worst = sessions.filter { it.streamGaps > 0 }.groupBy { it.channelName }
                .maxByOrNull { (_, list) -> list.sumOf { it.streamGaps } }?.key.orEmpty()
            out += Finding(
                Severity.WARN, "Streams are arriving with pieces missing",
                "$gaps times the sound skipped ahead because a slice of the stream never arrived" +
                    (if (worst.isNotEmpty()) ", most often on $worst" else "") + ". The stream is broken before it " +
                    "leaves the provider, so neither a faster connection nor a VPN will fix it — another copy " +
                    "of the channel, or another provider, will.",
            )
        }

        // ---- Does a VPN help? --------------------------------------------------------------
        vpnFinding(sessions, probes)?.let { out += it }

        // ---- The box itself ----------------------------------------------------------------
        val watchedMinutes = sessions.sumOf { it.watchedMillis } / 60_000.0
        val dropped = sessions.sumOf { it.droppedFrames }
        if (watchedMinutes >= 10 && dropped / watchedMinutes > DROPPED_FRAMES_PER_MINUTE) {
            out += Finding(
                Severity.WARN, "This device is struggling to decode the video",
                "It dropped $dropped frames in the last ${watchedMinutes.toInt()} minutes of viewing. That looks like " +
                    "stutter rather than buffering, and comes from the device, not the network. A lower-quality " +
                    "version of the channel (HD instead of 4K, or 25 fps instead of 50) will play smoothly.",
            )
        }

        if (out.isEmpty()) {
            out += if (internet == null && byProvider.isEmpty()) {
                Finding(
                    Severity.INFO, "Nothing measured yet",
                    "Run the speed test, or just watch some TV: every channel you watch is measured in the " +
                        "background and the results appear here.",
                )
            } else {
                Finding(
                    Severity.GOOD, "Everything measured looks healthy",
                    "No sign of trouble with your connection or your providers in what has been measured so far.",
                )
            }
        }
        return out.sortedByDescending { it.severity.ordinal }
    }

    /**
     * Compares like with like — the same provider watched with and without a VPN — and only speaks
     * when both sides have enough viewing behind them to mean something.
     */
    private fun vpnFinding(sessions: List<StreamSession>, probes: List<ProbeResult>): Finding? {
        val on = HealthSummary.of(sessions.filter { it.vpn })
        val off = HealthSummary.of(sessions.filter { !it.vpn })
        if (on.confident && off.confident) {
            val delta = on.score() - off.score()
            if (delta >= VPN_SCORE_DELTA) {
                return Finding(
                    Severity.INFO, "Streams play better with your VPN on",
                    "Viewing scored ${on.score()}/100 through the VPN against ${off.score()}/100 without it. When a VPN " +
                        "improves things, the usual reason is that your internet provider slows down or poorly routes " +
                        "IPTV traffic, and the VPN hides it. Keeping the VPN on for TV is worthwhile.",
                )
            }
            if (delta <= -VPN_SCORE_DELTA) {
                return Finding(
                    Severity.INFO, "Your VPN is making streams worse",
                    "Viewing scored ${on.score()}/100 through the VPN against ${off.score()}/100 without it. The VPN " +
                        "server is adding a detour or is itself congested. Try a VPN location nearer to you, or " +
                        "leave it off for TV.",
                )
            }
        }
        // No viewing on both sides yet: fall back to comparing the same stream test either way.
        val tests = probes.filter { it.kind == ProbeResult.KIND_STREAM && it.ok && it.throughputBps > 0 }
        for (withVpn in tests.filter { it.vpn }) {
            val without = tests.firstOrNull { !it.vpn && it.channelId == withVpn.channelId } ?: continue
            val ratio = withVpn.throughputBps.toDouble() / without.throughputBps
            // Server-paced streams arrive at the stream's own bitrate either way; only a clear gap
            // says anything.
            if (ratio >= VPN_SPEED_RATIO) {
                return Finding(
                    Severity.INFO, "The VPN route to ${withVpn.host} is faster",
                    "${withVpn.label} arrived at ${mbps(withVpn.throughputBps)} through the VPN and " +
                        "${mbps(without.throughputBps)} without. That suggests your internet provider is limiting this " +
                        "traffic; the VPN is worth keeping on for TV.",
                )
            }
            if (ratio <= 1 / VPN_SPEED_RATIO) {
                return Finding(
                    Severity.INFO, "The VPN route to ${withVpn.host} is slower",
                    "${withVpn.label} arrived at ${mbps(withVpn.throughputBps)} through the VPN and " +
                        "${mbps(without.throughputBps)} without. The VPN is costing speed here.",
                )
            }
        }
        return null
    }

    private const val SLOW_INTERNET_BPS = 10_000_000L
    private const val MODEST_INTERNET_BPS = 25_000_000L
    private const val HIGH_JITTER_MILLIS = 30L
    private const val HIGH_LATENCY_MILLIS = 150L
    private const val MIN_RECONNECTS_TO_REPORT = 3
    private const val MIN_HEADROOM = 1.3
    private const val SLOW_DNS_MILLIS = 400L
    private const val SLOW_TTFB_MILLIS = 1_500L
    private const val DROPPED_FRAMES_PER_MINUTE = 30.0
    private const val MIN_STREAM_GAPS = 5
    private const val STREAM_GAPS_PER_HOUR = 6.0
    private const val VPN_SCORE_DELTA = 12
    private const val VPN_SPEED_RATIO = 1.4
}
