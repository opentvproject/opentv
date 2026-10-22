/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream

/**
 * Streaming M3U/M3U8 playlist parser.
 *
 * Deliberately reads line-by-line rather than loading the whole playlist into memory:
 * real provider playlists routinely run to tens of megabytes and 50,000+ entries, and
 * loading that as a single String is a reliable way to OOM a cheap Android TV box.
 *
 * Tolerant by design. A malformed entry is skipped, not fatal — one bad line in a
 * 40,000-line playlist must never cost the user their entire channel list.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    data class Result(
        val channels: List<Channel>,
        /** Value of `url-tvg`/`x-tvg-url` on the #EXTM3U header, if the playlist declares one. */
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result =
        input.bufferedReader().use { parse(it, sourceId) }

    fun parse(text: String, sourceId: Long): Result =
        parse(text.reader().buffered(), sourceId)

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val channels = ArrayList<Channel>()
        val seenStreamIds = HashSet<String>()
        val seenIdentities = HashSet<String>()
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        var pendingCatchup: CatchupTags? = null
        // Playlist-wide catch-up defaults, from the #EXTM3U header (Kodi reads them there too).
        var catchupDefaults = CatchupDefaults()
        // #EXTVLCOPT / #EXTHTTP lines that apply to the next URL.
        var index = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                    catchupDefaults = catchupDefaults.mergedWith(catchupDefaultsOf(attributes))
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                    pendingCatchup = catchupTagsOf(pendingAttributes, catchupDefaults)
                }

                // Any other directive: ignore, but keep the pending EXTINF alive.
                line.startsWith("#") -> Unit

                else -> {
                    val name = pendingName
                    if (name.isNullOrBlank()) {
                        // A URL with no preceding #EXTINF. Nothing sensible to label it with.
                        skipped++
                    } else {
                        val attributes = pendingAttributes
                        val tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
                        val categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() }

                        val streamId = when {
                            tvgId == null -> {
                                // No id: the URL is the identity.
                                val candidate = "url:${stableHash(line)}"
                                candidate.takeIf { seenStreamIds.add(it) }
                            }

                            else -> {
                                // A tvg-id identifies a channel to the *guide*, not a stream:
                                // providers use one id for every quality variant of a channel,
                                // and sometimes for two different channels as well. Deduplicating
                                // on the id alone threw those away — the user's list came out
                                // shorter than everyone else's, and the quality switch had
                                // nothing to switch between.
                                val identity = identityOf(tvgId, name, categoryId)
                                if (seenIdentities.add(identity)) {
                                    // The first entry with a given id keeps the plain id, so the
                                    // favourites, renames and guide matches a user already has
                                    // survive the change.
                                    val plain = "tvg:$tvgId"
                                    if (seenStreamIds.add(plain)) plain
                                    else "tvg:$tvgId:${stableHash(identity)}"
                                } else {
                                    // The same channel, at the same quality, listed twice.
                                    null
                                }
                            }
                        }

                        if (streamId != null) {
                            val tags = pendingCatchup
                            val catchupSource = tags?.let {
                                M3uCatchup.sourceFor(it.mode, it.declaredSource, line, flussonicTs = it.tsStream)
                            }
                            channels += Channel(
                                sourceId = sourceId,
                                streamId = streamId,
                                name = name,
                                categoryId = categoryId,
                                logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])
                                    ?.takeIf { it.isNotBlank() },
                                epgChannelId = tvgId,
                                tvArchive = catchupSource != null,
                                tvArchiveDays = tags?.days ?: 0,
                                catchupSource = catchupSource,
                                catchupMode = tags?.mode?.tag?.takeIf { catchupSource != null },
                                catchupCorrectionSecs = tags?.correctionSecs ?: 0,
                                number = pendingNumber,
                                streamUrl = line,
                                sortIndex = index++,
                            )
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                    pendingCatchup = null
                }
            }
        }

        return Result(channels, declaredEpgUrl, skipped)
    }

    /**
     * The identity of a *stream*: which channel, at which quality, in which category.
     *
     * A tvg-id alone is not that. The guide's id is a channel's name in the provider's
     * catalogue, and providers reuse it — for every quality tier of a channel, for the same
     * channel listed in two categories, and sometimes for two channels that have nothing to
     * do with each other. Two entries that share an id are only the same stream when they
     * are also the same channel at the same quality, and that is what this says.
     *
     * The parts are the ones the app already uses to decide that two rows are the same
     * channel: the normalised name and the quality label. A stable hash of the result goes
     * into the stream id, so a user's favourites and renames survive a refresh.
     */
    private fun identityOf(tvgId: String, name: String, categoryId: String?): String {
        val normalized = ChannelNameNormalizer.normalize(name)
        return "$tvgId|${normalized.groupKey}|${normalized.qualityLabel.lowercase()}|${categoryId.orEmpty()}"
    }

    /**
     * The display name is whatever follows the last comma on the #EXTINF line. Falling back
     * to `tvg-name` matters because a fair number of playlists emit `#EXTINF:-1 ...,` with
     * nothing after the comma.
     */
    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }

    /** FNV-1a. Stable across processes and platforms, unlike [String.hashCode] guarantees. */
    private fun stableHash(value: String): String {
        var hash = 0xcbf29ce484222325uL.toLong() // FNV-1a 64-bit offset basis
        for (byte in value.encodeToByteArray()) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L // FNV-1a 64-bit prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
