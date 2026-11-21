/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.sync

import java.security.MessageDigest

/**
 * Turns a stream URL into the opaque key that travels in a [SyncBundle].
 *
 * ## Why
 *
 * An Xtream stream URL is `http://host:port/live/<USERNAME>/<PASSWORD>/<id>.ts` — the account
 * credentials are *in* the URL, not beside it. The sync bundle is built from exactly those URLs,
 * so before this existed a device-to-device sync over plain HTTP on the user's wifi put the
 * provider username and password on the wire in cleartext, and into the JSON a peer wrote to its
 * own storage. The bundle's own documentation says credentials never sync; this is the piece
 * that makes that statement true.
 *
 * ## How
 *
 * Each URL is replaced by `sha256(url)` truncated to 32 hex characters (128 bits). Both devices
 * hash the same URL the same way, so an item still matches across devices — the receiver builds
 * a `hash -> local url` index from its own catalogue and never needs to see the peer's URL. A
 * key that does not resolve simply does not match, which is the correct outcome: that item is
 * not in this device's catalogue.
 *
 * Truncating to 128 bits is not a compromise here: an attacker who captured a bundle would have
 * to guess the provider's address, username and password to reproduce a key, and a collision
 * between two *legitimately* synced URLs would require 2^64 distinct URLs on one account.
 */
object SyncKeys {

    /** Hex characters kept from the SHA-256 digest (32 chars = 128 bits). */
    private const val KEY_LENGTH = 32

    /**
     * The wire identifier for [url]. Case-sensitive on purpose — URLs are, and normalising them
     * here would risk matching two genuinely different streams.
     */
    fun key(url: String): String {
        if (url.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(KEY_LENGTH)
        for (i in 0 until KEY_LENGTH / 2) {
            val b = digest[i].toInt() and 0xff
            out.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
