/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up (archive) URLs for M3U channels.
 *
 * An Xtream login tells us the archive is there — `tv_archive` per channel — and OpenTV builds the
 * timeshift URL from the credentials. A plain M3U playlist has no such field: the provider
 * describes the archive on the playlist itself, with the tags Kodi's IPTV Simple Client defined and
 * a large part of the market now emits. Those are `catchup`, `catchup-source`, `catchup-days` and
 * `catchup-correction`, and the tokens inside `catchup-source` (`{utc}`, `{duration:60}`,
 * `{offset:1}`...) are a contract between provider and player, not something to invent.
 *
 * So the mode names and the URL shapes below follow that reference implementation deliberately
 * (pvr.iptvsimple `Channel::ConfigureCatchupMode` and `CatchupController::FormatDateTime`): a
 * provider that works in Kodi has to work here, and a provider that does not cannot be helped by
 * guessing.
 *
 * Pure: no Android, no IO, no clock of its own — every instant is a parameter. Exercised by
 * `M3uCatchupTest`, because there is no way to test this against a real provider from CI.
 */
object M3uCatchup {

    /** `tvArchiveDays` when the provider offers the whole archive (Kodi's VOD mode). */
    const val IGNORE_CATCHUP_DAYS = -1

    /** How the provider wants the archive URL built. `TIMESHIFT` is SIPTV's older `timeshift="3"`. */
    enum class Mode(val tag: String) {
        DEFAULT("default"),
        APPEND("append"),
        SHIFT("shift"),
        FLUSSONIC("flussonic"),
        XTREAM_CODES("xc"),
        VOD("vod"),
        TIMESHIFT("timeshift"),
    }

    /**
     * The `catchup` tag as a mode. Some providers spell the tag `catchup-type`; both are read before
     * this is called. An unknown word is not a mode — better a channel with no catch-up than a URL
     * assembled from something we did not understand.
     */
    fun modeOf(raw: String?): Mode? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "default" -> Mode.DEFAULT
        "append" -> Mode.APPEND
        "shift" -> Mode.SHIFT
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> Mode.FLUSSONIC
        "xc" -> Mode.XTREAM_CODES
        "vod" -> Mode.VOD
        // SIPTV's older spelling, which reaches us as a `timeshift` tag rather than a `catchup` one.
        "timeshift" -> Mode.TIMESHIFT
        else -> null
    }

    /** `flussonic-ts` and `fs` name a TS archive; plain `flussonic`/`flussonic-hls` name an HLS one. */
    fun isTsStream(raw: String?): Boolean =
        raw?.trim()?.lowercase(Locale.ROOT) in setOf("flussonic-ts", "fs")

    /**
     * The channel's catch-up template: the URL with its `{...}` tokens still in it, ready for
     * [buildUrl] once a programme is picked. Null when the playlist gives us nothing usable, and the
     * channel then has no catch-up at all.
     *
     * `queryFormat` stands for Kodi's "query format string" setting, which a provider sometimes
     * needs when it ships no `catchup-source`; OpenTV has no such setting yet, so it is null and
     * those channels get no catch-up rather than a made-up query.
     */
    fun sourceFor(
        mode: Mode,
        declaredSource: String?,
        streamUrl: String,
        queryFormat: String? = null,
        flussonicTs: Boolean = false,
    ): String? {
        val declared = declaredSource?.takeIf { it.isNotBlank() }
        return when (mode) {
            // The playlist hands over the whole URL; without one, the live URL takes a query.
            Mode.DEFAULT, Mode.VOD -> declared ?: append(streamUrl, queryFormat)

            // SIPTV's plain query form: the start and the current time, appended to the live URL.
            Mode.APPEND -> declared?.let { streamUrl + it } ?: append(streamUrl, queryFormat)
            Mode.SHIFT, Mode.TIMESHIFT ->
                streamUrl + if (streamUrl.contains('?')) "&utc={utc}&lutc={lutc}" else "?utc={utc}&lutc={lutc}"

            Mode.FLUSSONIC -> flussonic(streamUrl, flussonicTs)
            Mode.XTREAM_CODES -> xtreamCodes(streamUrl)
        }
    }

    private fun append(streamUrl: String, queryFormat: String?): String? =
        queryFormat?.takeIf { it.isNotBlank() }?.let { streamUrl + it }

    /**
     * Flussonic keeps the archive on the same host and channel path. Two shapes are in use: the
     * stream named `<channel>/index.m3u8` / `<channel>/mono.m3u8` / `<channel>/.../mpegts`, and
     * everything else, where the server accepts any directory name after the channel id.
     */
    private fun flussonic(url: String, tsStream: Boolean): String? {
        NESTED_FLUSSONIC.find(url)?.let { match ->
            val host = match.groupValues[1]
            val channel = match.groupValues[2]
            val listType = match.groupValues[3]
            val streamType = match.groupValues[4]
            val query = match.groupValues[5]
            return when {
                streamType == "mpegts" -> "$host/$channel/timeshift_abs-\${start}.ts$query"
                listType == "index" -> "$host/$channel/timeshift_rel-{offset:1}.m3u8$query"
                else -> "$host/$channel/$listType-timeshift_rel-{offset:1}.m3u8$query"
            }
        }
        ANY_FLUSSONIC.find(url)?.let { match ->
            val host = match.groupValues[1]
            val channel = match.groupValues[2]
            val query = match.groupValues[4]
            return if (tsStream) "$host/$channel/timeshift_abs-\${start}.ts$query"
            else "$host/$channel/timeshift_rel-{offset:1}.m3u8$query"
        }
        return null
    }

    /**
     * Xtream's timeshift path, from a playlist that carries the account in the stream URL
     * (`http://list.tv:8080/my@account.xc/my_password/1477`, with or without a `live/` segment and
     * with or without an extension).
     */
    private fun xtreamCodes(url: String): String? {
        val match = XTREAM_STREAM.find(url) ?: return null
        val host = match.groupValues[1]
        val username = match.groupValues[2]
        val password = match.groupValues[3]
        val channelId = match.groupValues[4]
        val extension = match.groupValues[5].ifEmpty { ".ts" }
        return "$host/timeshift/$username/$password/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$channelId$extension"
    }

    /**
     * Fills a template in for one programme.
     *
     * `correctionSecs` is the provider's own time correction (`catchup-correction`, written in hours
     * on the playlist). It is *subtracted* from the programme start, which is what Kodi does and
     * what providers expect: an archive that runs four hours off its own guide is fixed with
     * `catchup-correction="-4.0"`, and the value is only ever about the URL, never the guide.
     *
     * `zone` is the clock the provider keeps its archive on — the same reason the Xtream
     * timeshift URL needs the panel's timezone. A template that spells the time out in parts
     * (`{Y}-{m}-{d}:{H}-{M}`) is written on that clock; null means the device's own, which is
     * what a local provider's server expects.
     *
     * Unknown tokens (`{catchup-id}`, which needs an XMLTV programme id we do not model) are left
     * in the URL. A visible failure is better than a link that quietly plays the wrong thing.
     */
    fun buildUrl(
        template: String,
        startUtcMillis: Long,
        endUtcMillis: Long,
        nowUtcMillis: Long,
        correctionSecs: Int = 0,
        zone: TimeZone? = null,
    ): String {
        val shift = correctionSecs * 1000L
        val start = startUtcMillis - shift
        val end = maxOf(endUtcMillis, startUtcMillis) - shift
        val durationSecs = ((endUtcMillis - startUtcMillis) / 1000L).coerceAtLeast(0L)
        val offsetSecs = ((nowUtcMillis - start) / 1000L).coerceAtLeast(0L)

        return TOKEN.replace(template) { match ->
            val rawName = match.groupValues[1].ifEmpty { match.groupValues[3] }
            val argument = match.groupValues[2].ifEmpty { match.groupValues[4] }
            // The single-letter parts are case-sensitive, exactly as in Kodi: {m} is the month, {M}
            // the minute. Only the spelled-out names are matched case-insensitively.
            PART_FIELDS[rawName]?.let { (field, digits) -> return@replace partOf(start, field, digits, zone) }
            when (rawName.lowercase(Locale.ROOT)) {
                "utc", "start" -> time(start, argument, zone)
                "utcend", "end" -> time(end, argument, zone)
                "lutc", "now", "timestamp" -> time(nowUtcMillis, argument, zone)
                "duration" -> units(durationSecs, argument)
                "offset" -> units(offsetSecs, argument)
                else -> match.value
            }
        }
    }

    /**
     * `{utc}` is seconds since the epoch; `{utc:Ymd-HM}` (and its `${start:...}` spelling) is that
     * instant written out with the format string, on the provider's clock — the same choice the
     * player makes for the guide, and the reason `catchup-correction` exists at all.
     */
    private fun time(millis: Long, format: String, zone: TimeZone?): String =
        if (format.isBlank()) (millis / 1000L).toString() else formatted(millis, format, zone)

    /** `{duration}` and `{offset}` are seconds; `{duration:60}` is minutes, `{offset:1}` seconds. */
    private fun units(seconds: Long, divider: String): String {
        if (divider.isBlank()) return seconds.toString()
        val by = divider.toIntOrNull()?.coerceAtLeast(1) ?: return seconds.toString()
        return (seconds / by).toString()
    }

    private fun formatted(millis: Long, format: String, zone: TimeZone?): String {
        val calendar = Calendar.getInstance(zone ?: TimeZone.getDefault())
        calendar.timeInMillis = millis
        val out = StringBuilder()
        for (character in format) {
            when (character) {
                'Y' -> out.append(pad(calendar.get(Calendar.YEAR), 4))
                'm' -> out.append(pad(calendar.get(Calendar.MONTH) + 1, 2))
                'd' -> out.append(pad(calendar.get(Calendar.DAY_OF_MONTH), 2))
                'H' -> out.append(pad(calendar.get(Calendar.HOUR_OF_DAY), 2))
                'M' -> out.append(pad(calendar.get(Calendar.MINUTE), 2))
                'S' -> out.append(pad(calendar.get(Calendar.SECOND), 2))
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    private fun partOf(millis: Long, field: Int, digits: Int, zone: TimeZone?): String {
        val calendar = Calendar.getInstance(zone ?: TimeZone.getDefault())
        calendar.timeInMillis = millis
        val value = if (field == Calendar.MONTH) calendar.get(field) + 1 else calendar.get(field)
        return pad(value, digits)
    }

    private fun pad(value: Int, digits: Int): String = value.toString().padStart(digits, '0')

    /** `{name}`, `{name:arg}` and their `${...}` spellings, in one pass so order never matters. */
    private val TOKEN = Regex(
        """\${'$'}\{([A-Za-z][\w-]*)(?::([^}]*))?\}|\{([A-Za-z][\w-]*)(?::([^}]*))?\}""",
    )

    /** The date parts, with the case Kodi gives them: {Y}{m}{d}{H}{M}{S}, and how wide each one is. */
    private val PART_FIELDS = mapOf(
        "Y" to (Calendar.YEAR to 4),
        "m" to (Calendar.MONTH to 2),
        "d" to (Calendar.DAY_OF_MONTH to 2),
        "H" to (Calendar.HOUR_OF_DAY to 2),
        "M" to (Calendar.MINUTE to 2),
        "S" to (Calendar.SECOND to 2),
    )

    private val NESTED_FLUSSONIC = Regex("""^(https?://[^/]+)/(.*)/([^/]*)(mpegts|\.m3u8)(\?.+=.+)?${'$'}""")
    private val ANY_FLUSSONIC = Regex("""^(https?://[^/]+)/(.*)/([^?]*)(\?.+=.+)?${'$'}""")
    private val XTREAM_STREAM = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/.]+)(\.m3u8?)?${'$'}""")
}

/**
 * The catch-up tags a playlist carries, read the way Kodi's IPTV Simple Client reads them.
 *
 * They live in two places: on the `#EXTM3U` header, where they describe the whole playlist, and
 * on the `#EXTINF` line, where they describe one channel. A channel's own tag wins; anything it
 * does not say keeps the playlist's answer. This is the same precedence the reference
 * implementation applies, and it is why a provider can put the archive scheme on the header once
 * instead of repeating it on ten thousand channels.
 */
data class CatchupDefaults(
    /** The `catchup` tag verbatim — the mode word, or `timeshift` for SIPTV's older spelling. */
    val modeTag: String? = null,
    /** The `catchup-source` template, with its `{...}` tokens still in it. */
    val source: String? = null,
    /** `catchup-days`: how far back the archive goes. Null when the playlist says nothing. */
    val days: Int? = null,
    /** `catchup-correction`, already in seconds. The playlist writes it in hours. */
    val correctionSecs: Int = 0,
) {
    /**
     * Merges two sets of tags, with [other] winning. A tag that is absent does not erase the
     * other set's answer — that is what "the channel does not say" means, not "the channel says
     * no".
     */
    fun mergedWith(other: CatchupDefaults): CatchupDefaults = CatchupDefaults(
        modeTag = other.modeTag ?: modeTag,
        source = other.source ?: source,
        days = other.days ?: days,
        correctionSecs = if (other.correctionSecs != 0) other.correctionSecs else correctionSecs,
    )
}

/**
 * The catch-up tags in effect for one channel: its own where it has them, the playlist's
 * everywhere else. Null when the channel has no catch-up at all.
 */
data class CatchupTags(
    val mode: M3uCatchup.Mode,
    /** The `catchup-source` template, when the playlist gave one. */
    val declaredSource: String?,
    /** How many days back the archive goes, or [M3uCatchup.IGNORE_CATCHUP_DAYS] for all of it. */
    val days: Int,
    val correctionSecs: Int,
    /** The mode word named a TS archive (`flussonic-ts` / `fs`) rather than an HLS one. */
    val tsStream: Boolean,
)

/**
 * The catch-up tags on a `#EXTM3U` header, if it carries any.
 *
 * @param attributes the header's attributes, keys already lower-cased.
 */
fun catchupDefaultsOf(attributes: Map<String, String>): CatchupDefaults = CatchupDefaults(
    modeTag = modeTagOf(attributes),
    source = attributes["catchup-source"]?.takeIf { it.isNotBlank() },
    days = attributes["catchup-days"]?.trim()?.toIntOrNull(),
    correctionSecs = correctionOf(attributes),
)

/**
 * The catch-up tags in effect for a channel: the ones on its `#EXTINF` line over the ones the
 * playlist declared. Null when neither says anything, and null when the mode word is not one we
 * know — a URL assembled from a mode we did not understand is worse than no catch-up at all.
 *
 * @param attributes the `#EXTINF` line's attributes, keys already lower-cased.
 * @param defaults the playlist-wide tags, from the header.
 */
fun catchupTagsOf(attributes: Map<String, String>, defaults: CatchupDefaults): CatchupTags? {
    val modeTag = modeTagOf(attributes) ?: defaults.modeTag
    val source = attributes["catchup-source"]?.takeIf { it.isNotBlank() } ?: defaults.source
    if (modeTag == null && source == null) return null

    // A source with no mode word of its own is Kodi's "default" mode: the template is used as
    // it stands. A mode word we do not know, though, is an invitation to guess — and a guessed
    // URL plays the wrong programme without saying so.
    val mode = M3uCatchup.modeOf(modeTag)
        ?: if (modeTag == null) M3uCatchup.Mode.DEFAULT else return null

    val days = attributes["catchup-days"]?.trim()?.toIntOrNull() ?: defaults.days
    return CatchupTags(
        mode = mode,
        declaredSource = source,
        days = when {
            // The VOD mode is the whole archive, not a window into it.
            mode == M3uCatchup.Mode.VOD -> M3uCatchup.IGNORE_CATCHUP_DAYS
            // Kodi reads `catchup-days="0"` as "no limit" rather than "no archive".
            days == 0 -> M3uCatchup.IGNORE_CATCHUP_DAYS
            else -> days ?: 0
        },
        correctionSecs = correctionOf(attributes).takeIf { it != 0 } ?: defaults.correctionSecs,
        tsStream = M3uCatchup.isTsStream(modeTag),
    )
}

/**
 * The mode word a set of attributes asks for, in the spellings providers actually use: the
 * `catchup` tag, the `catchup-type` some panels emit instead, and SIPTV's older `timeshift="3"`.
 */
private fun modeTagOf(attributes: Map<String, String>): String? {
    attributes["catchup"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    attributes["catchup-type"]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val timeshift = attributes["timeshift"]?.trim()
    if (!timeshift.isNullOrEmpty() && timeshift != "0") return "timeshift"
    return null
}

/**
 * The provider's own time correction, in seconds. The playlist writes it in hours and allows
 * fractions of one (`catchup-correction="-3.5"`), so it is read as a decimal and not as an int.
 */
private fun correctionOf(attributes: Map<String, String>): Int =
    attributes["catchup-correction"]?.trim()?.toDoubleOrNull()
        ?.let { (it * SECONDS_PER_HOUR).toInt() }
        ?: 0

private const val SECONDS_PER_HOUR = 3600