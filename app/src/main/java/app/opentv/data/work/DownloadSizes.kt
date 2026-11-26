/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.work

import java.util.Locale

/**
 * The small pure pieces behind "how big is this download?": reading a size out of a provider's
 * response headers, and printing it. Kept free of Android and network types so they can be tested.
 */
object DownloadSizes {

    /** The total from a `Content-Range: bytes 0-0/1234567` header, or null when absent or unknown (`/` followed by a star). */
    fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** "1.4 GB", "700 MB", "512 KB" — decimal units, the way storage is sold and shown in Settings. */
    fun format(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format(Locale.US, "%d MB", bytes / 1_000_000L)
        bytes >= 1_000L -> String.format(Locale.US, "%d KB", bytes / 1_000L)
        else -> "$bytes B"
    }

    /**
     * A season's rough size from one measured episode: episodes of a season are near enough the same
     * length and encode for this to tell the user whether it is 3 GB or 30.
     */
    fun seasonEstimate(sampleBytes: Long?, episodeCount: Int): Long? =
        sampleBytes?.takeIf { it > 0 && episodeCount > 0 }?.let { it * episodeCount }

    /**
     * The most distinctive word of a title, for a cheap `LIKE` that fetches a film's sibling copies
     * (the same film listed again in 4K, HD, SD) without loading the whole catalogue.
     */
    fun nameFragment(name: String): String? =
        Regex("[\\p{L}\\p{N}]+").findAll(name).map { it.value }
            .filterNot { it.length == 4 && it.toIntOrNull() in 1900..2100 }
            .maxByOrNull { it.length }
            ?.takeIf { it.length >= 3 }
}
