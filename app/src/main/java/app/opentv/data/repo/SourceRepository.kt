/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled()

    suspend fun byId(id: Long): Source? = dao.byId(id)

    suspend fun save(source: Source): Long = withContext(Dispatchers.IO) {
        val normalised = source.copy(url = normaliseUrl(source.url, source.kind))
        if (source.id == 0L) dao.insert(normalised)
        else {
            dao.update(normalised)
            source.id
        }
    }

    /** Checks the details work before the user is committed to them. */
    suspend fun test(source: Source): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                SourceKind.XTREAM -> {
                    val info = api.authenticate(
                        source.copy(url = normaliseUrl(source.url, source.kind)),
                    )
                    buildString {
                        append("Connected")
                        info.username?.let { append(" as $it") }
                        info.maxConnections?.let { append(" · $it connection(s)") }
                        info.expiryMillis?.let {
                            append(" · expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}")
                        }
                    }
                }
                SourceKind.M3U -> {
                    val local = localFile(source.url)
                    when {
                        // A network playlist is still only checked on first sync: fetching it
                        // here would download the whole thing twice to learn nothing new.
                        local == null ->
                            "Playlist address looks valid. It will be checked on first sync."
                        !local.exists() -> error("No file at ${local.path}")
                        !local.isFile -> error("${local.path} is a folder, not a playlist file")
                        !local.canRead() -> error(
                            "${local.path} cannot be read. A file under " +
                                "Android/data/app.opentv/files needs no permission; elsewhere " +
                                "on shared storage it does.",
                        )
                        local.length() == 0L -> error("${local.name} is empty")
                        else -> "Found ${local.name} (${local.length()} bytes)."
                    }
                }
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(source.copy(url = normaliseUrl(source.url, source.kind)))
                    "Portal accepted the MAC address. Loading channels…"
                }
            }
        }
    }

    companion object {
        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            // A playlist can sit on the device itself — generated on a NAS and copied over, or
            // merged from other lists on a PC. Those have no server to be fetched from, and
            // prefixing a scheme turns file:///sdcard/x.m3u into http://file:///sdcard/x.m3u,
            // which no loader can make sense of.
            if (kind == SourceKind.M3U && isLocal(url)) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            if (kind == SourceKind.M3U) return url
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }

        /**
         * Whether a playlist address is one the device can open by itself, with no network
         * and no server anywhere.
         *
         * Only `file://` for now. It is the scheme that costs nothing: a playlist under the
         * app's own external files directory — `Android/data/app.opentv/files/`, where a file
         * copied over ADB or by a file manager naturally lands — is readable with no runtime
         * permission at all, on every Android version.
         */
        fun isLocal(url: String): Boolean = url.trim().startsWith("file://", ignoreCase = true)

        /**
         * The file behind a `file://` playlist address, or null if the address is not one.
         *
         * Tries the strict URI form first so `%20` decodes, then falls back to the raw path:
         * `URI()` rejects an unescaped space, and a filename is perfectly entitled to contain
         * one. Nothing here touches the disk, so a caller still has to check [File.exists].
         */
        fun localFile(url: String): File? {
            if (!isLocal(url)) return null
            val trimmed = url.trim()
            runCatching { File(URI(trimmed)) }.getOrNull()?.let { return it }
            // isLocal already matched the prefix, case-insensitively, so its length is fixed.
            val path = trimmed.substring("file://".length)
            if (path.isEmpty()) return null
            return File(if (path.startsWith("/")) path else "/$path")
        }
    }
}
