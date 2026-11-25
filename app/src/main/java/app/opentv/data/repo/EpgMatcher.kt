/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.parser.ChannelNameNormalizer

/**
 * Joins provider channels to guide channels when nobody gave us a join key.
 *
 * The provider says `UK| BBC ONE FHD`. The guide says `BBC One` (id `bbc1.uk`). The only
 * bridge between them is the name, so both sides are pushed through
 * [ChannelNameNormalizer.groupKeyOf] and matched on the result.
 *
 * ## The design rule: prefer no match to a wrong match
 *
 * A missing guide entry reads as "provider doesn't do EPG" and can be fixed with a manual
 * override. A *wrong* one shows EastEnders against a sports channel, and the user reasonably
 * concludes the whole guide is broken. So the fuzzy tier only accepts a prefix match when it
 * is **unambiguous** — exactly one guide channel fits. `bbcone` will claim `BBC One` when
 * that is the only candidate, but if the guide carries `BBC One London` *and* `BBC One
 * Wales` and nothing plain, we refuse to guess between regions.
 *
 * ## Why the index is sorted
 *
 * A big provider ships 20k+ channels and only a few hundred get an exact hit; the rest fall
 * through to the prefix tier. Scanning every guide alias for each of those — O(channels ×
 * aliases) — is hundreds of millions of comparisons on a TV box, which pins a core for minutes
 * and reads as a frozen "loading". So the keys are sorted once and the two prefix relations are
 * answered without a full scan: "a guide key extends the provider key" is a contiguous range
 * found by binary search; "the provider key extends a guide key" is at most `key.length` exact
 * lookups (one per prefix). Per channel that is ~O(log A + matches) instead of O(A).
 */
object EpgMatcher {

    /** One guide channel, pre-normalised. */
    data class Alias(
        val epgId: String,
        /** Normalised display-name or id: `bbcone`, `bbc1uk`. */
        val key: String,
    )

    class Index internal constructor(
        private val exact: Map<String, String>,
        /** Distinct normalised keys, sorted ascending, for binary-searched prefix ranges. */
        private val sortedKeys: List<String>,
        /** Every epg id seen for a given key (usually one; more than one is the ambiguous case). */
        private val byKey: Map<String, Set<String>>,
    ) {
        /** Returns the epg id for a provider channel's group key, or null. */
        fun match(groupKey: String): String? {
            if (groupKey.length < MIN_KEY_LENGTH) return null

            exact[groupKey]?.let { return it }

            // Prefix tier: the provider name extends the guide name or vice versa
            // ("skysportsmainevent" vs "skysportsmainevt"; "bbcone" vs "bbconelondon").
            // Accepted only when every candidate agrees on a single epg id. Collecting the two
            // relations below and demanding a single distinct epg id reproduces the old full-scan
            // result exactly — just without visiting every alias.
            var found: String? = null

            // `false` means "two different epg ids seen" — the caller must give up (ambiguous).
            fun consider(epgId: String): Boolean {
                if (found == null) {
                    found = epgId
                    return true
                }
                return found == epgId
            }

            // Relation 1: a guide key *is a prefix of* the provider key (groupKey startsWith key).
            // There are at most groupKey.length such prefixes; test each against the exact key set.
            for (len in MIN_KEY_LENGTH..groupKey.length) {
                val ids = byKey[groupKey.substring(0, len)] ?: continue
                for (id in ids) if (!consider(id)) return null
            }

            // Relation 2: a guide key *extends* the provider key (key startsWith groupKey). Sorted
            // keys with that prefix are contiguous, so walk from the first key >= groupKey while it
            // still starts with groupKey.
            var i = lowerBound(sortedKeys, groupKey)
            while (i < sortedKeys.size && sortedKeys[i].startsWith(groupKey)) {
                val ids = byKey[sortedKeys[i]]
                if (ids != null) for (id in ids) if (!consider(id)) return null
                i++
            }

            return found
        }

        /** First index whose key is >= [key] (standard lower-bound binary search). */
        private fun lowerBound(keys: List<String>, key: String): Int {
            var lo = 0
            var hi = keys.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (keys[mid] < key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    /**
     * Builds the lookup from every `<channel>` element seen across every enabled guide.
     *
     * Both the display-name (`BBC One`) and the raw id (`bbc1.uk`) are indexed — providers
     * that *do* fill in `epg_channel_id` often use the id form, and it costs nothing to
     * accept either.
     */
    fun buildIndex(aliases: Iterable<Pair<String, String>>): Index {
        val exact = HashMap<String, String>()
        val ambiguous = HashSet<String>()
        val byKey = HashMap<String, MutableSet<String>>()

        for ((epgId, name) in aliases) {
            for (candidate in listOf(name, epgId)) {
                // Full normalise, not bare groupKeyOf: free guides append 'HD' to display
                // names ('BBC One East HD'), which would otherwise never key-match a
                // provider's 'BBC ONE EAST'. normalize() strips the quality token.
                val key = ChannelNameNormalizer.normalize(candidate).groupKey
                if (key.length < MIN_KEY_LENGTH) continue
                byKey.getOrPut(key) { HashSet() }.add(epgId)
                val existing = exact.putIfAbsent(key, epgId)
                if (existing != null && existing != epgId) {
                    // Two different guide channels normalise to the same key. Neither can
                    // be trusted for exact matching; drop the key rather than pick a side.
                    ambiguous += key
                }
            }
        }
        ambiguous.forEach(exact::remove)

        return Index(exact, byKey.keys.sorted(), byKey)
    }

    /**
     * Below this, keys are too generic to mean anything — `e4` is real, but `tv`, `hd` and
     * single letters match everything. 2 keeps `e4`/`5usa`-style names alive.
     */
    private const val MIN_KEY_LENGTH = 2
}
