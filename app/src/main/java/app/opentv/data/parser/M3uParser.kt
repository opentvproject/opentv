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
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
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
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
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
                        val streamId = attributes["tvg-id"]
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "tvg:$it:${stableHash(line)}" }
                            ?: "url:${stableHash(line)}"

                        // Drops true identical duplicate streams rather than letting them collide on
                        // the unique index. Distinct streams sharing a tvg-id (e.g. SD and HD) are
                        // both preserved.
                        if (seenStreamIds.add(streamId)) {
                            channels += Channel(
                                sourceId = sourceId,
                                streamId = streamId,
                                name = name,
                                categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() },
                                logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])
                                    ?.takeIf { it.isNotBlank() },
                                epgChannelId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                                number = pendingNumber,
                                streamUrl = line,
                                sortIndex = index++,
                            )
                        }
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        return Result(channels, declaredEpgUrl, skipped)
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
