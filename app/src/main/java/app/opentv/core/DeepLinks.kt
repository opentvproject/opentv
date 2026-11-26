/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLDecoder

/**
 * Links other apps can open OpenTV with. One for now:
 *
 *     opentv://search?q=the bear
 *
 * opens Search with the text already typed — what a voice-search bridge (a Fire TV remote's mic
 * button, an assistant routine, `adb shell am start`) needs to hand a spoken query over. Parsing is
 * plain string work rather than `android.net.Uri` so it is forgiving about a query that arrives
 * with raw spaces, and so it can be unit-tested off-device.
 */
object DeepLinks {
    const val SCHEME = "opentv"
    const val HOST_SEARCH = "search"

    /** Longer than anyone types with a remote, short enough that a hostile link can't flood the UI. */
    const val MAX_QUERY = 80

    /**
     * The search text carried by [link], or null when it is not a search link. A search link with
     * no text ("opentv://search") is an empty string: open Search, type nothing.
     */
    fun searchQuery(link: String?): String? {
        val raw = link?.trim().orEmpty()
        val prefix = "$SCHEME://$HOST_SEARCH"
        if (!raw.startsWith(prefix, ignoreCase = true)) return null
        val rest = raw.substring(prefix.length)
        // "opentv://searching" is some other host, not ours.
        if (rest.isNotEmpty() && rest[0] != '?' && rest[0] != '/' && rest[0] != '#') return null
        val query = rest.substringAfter('?', "").substringBefore('#')
        val value = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { (name, _) -> name.equals("q", true) || name.equals("query", true) }
            ?.second
            .orEmpty()
        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        return decoded
            .filter { !it.isISOControl() }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_QUERY)
    }
}

/**
 * A one-shot "open Search with this text" request, set from an incoming [DeepLinks] intent and
 * consumed by the nav graph — the same hand-off shape as [PlayRequests].
 */
object SearchRequests {
    private val _query = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = _query.asStateFlow()

    fun request(text: String) {
        _query.value = text
    }

    fun consume() {
        _query.value = null
    }
}
