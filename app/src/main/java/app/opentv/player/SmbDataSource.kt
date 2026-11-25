/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.opentv.core.AppSettings
import app.opentv.recording.RecordingStorage
import app.opentv.recording.SmbClient
import app.opentv.recording.SmbReadHandle

/**
 * A Media3 data source that reads a recording or a downloaded film straight off a NAS over SMB, at
 * arbitrary offsets — which is what makes a NAS file seekable in the app's own player rather than
 * needing a copy pulled down first. Only `smb://` URIs come here; everything else routes to the
 * default source.
 *
 * ## Read-ahead
 *
 * Every SMB read is a network round-trip. An extractor opening an mp4/mkv walks the file's header
 * and seek index with *hundreds* of tiny reads (box/EBML headers are a handful of bytes each), and
 * one round-trip per tiny read is what made a NAS film take an age to start and then buffer. So this
 * source never hands ExoPlayer's small reads straight to the wire: it fills a [BLOCK_SIZE] buffer in
 * one shot and serves subsequent reads from memory, collapsing those hundreds of round-trips into a
 * few. Sequential playback and the initial index parse both become a handful of big reads instead of
 * a storm of small ones; a seek outside the buffer just refills it at the new offset.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val settings: AppSettings) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var handle: SmbReadHandle? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false

    // Read-ahead buffer. `blockStart` is the absolute file offset of block[0]; `blockLen` the number
    // of valid bytes. blockStart = -1 means "nothing cached" (fresh open or post-seek).
    private val block = ByteArray(BLOCK_SIZE)
    private var blockStart: Long = -1
    private var blockLen: Int = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val handle = SmbClient.openForRead(RecordingStorage.smbConfig(settings), dataSpec.uri.toString())
        this.handle = handle
        position = dataSpec.position
        bytesRemaining =
            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (handle.length - dataSpec.position).coerceAtLeast(0)
        // Invalidate the read-ahead buffer — a new open may be at a different offset.
        blockStart = -1
        blockLen = 0
        opened = true
        Log.d(TAG, "open pos=${dataSpec.position} len=$bytesRemaining size=${handle.length} uri=${dataSpec.uri}")
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Refill when the requested position isn't inside the cached block.
        if (blockStart < 0 || position < blockStart || position >= blockStart + blockLen) {
            if (!fillBlock()) return C.RESULT_END_OF_INPUT
        }

        val inBlock = (position - blockStart).toInt()
        val availInBlock = blockLen - inBlock
        val toCopy = minOf(minOf(length.toLong(), bytesRemaining), availInBlock.toLong()).toInt()
        System.arraycopy(block, inBlock, buffer, offset, toCopy)
        position += toCopy
        bytesRemaining -= toCopy
        bytesTransferred(toCopy)
        return toCopy
    }

    /** Fills [block] with up to [BLOCK_SIZE] bytes starting at [position], looping SMB reads to fill. */
    private fun fillBlock(): Boolean {
        if (handle == null) return false
        val want = minOf(BLOCK_SIZE.toLong(), bytesRemaining).toInt()
        if (want <= 0) return false
        var got = 0
        while (got < want) {
            val n = readOnce(position + got, got, want - got)
            if (n <= 0) break
            got += n
        }
        if (got <= 0) return false
        blockStart = position
        blockLen = got
        return true
    }

    /**
     * One SMB read that heals a dropped connection in place.
     *
     * A consumer NAS routinely closes the SMB session mid-film — an idle-session timeout, the disk
     * spinning back up, a Wi-Fi blip — and the first read after that throws "DiskShare has already
     * been closed". Left alone that surfaces to ExoPlayer as a load error, and its retry-with-backoff
     * is exactly the stall the viewer sees. So we catch it, drop the dead cached connection, re-open a
     * fresh handle at the same offset and retry once. Playback rides straight over the drop. If the
     * retry also fails (the NAS is actually gone), the exception propagates and ExoPlayer's policy
     * takes it from there.
     */
    private fun readOnce(fileOffset: Long, bufferOffset: Int, len: Int): Int {
        val h = handle ?: return -1
        return try {
            h.readAt(block, fileOffset, bufferOffset, len)
        } catch (t: Throwable) {
            Log.w(TAG, "read failed at $fileOffset — reconnecting once", t)
            runCatching { h.close() }
            SmbClient.releaseReadCache()
            val fresh = SmbClient.openForRead(
                RecordingStorage.smbConfig(settings),
                uri?.toString() ?: throw t,
            )
            handle = fresh
            fresh.readAt(block, fileOffset, bufferOffset, len)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { handle?.close() }
        handle = null
        blockStart = -1
        blockLen = 0
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    class Factory(private val settings: AppSettings) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(settings)
    }

    private companion object {
        const val TAG = "SmbDataSource"

        /**
         * One SMB fetch serves this many bytes of ExoPlayer's reads. Big enough that an mp4/mkv
         * header-and-index parse is a few round-trips instead of hundreds; small enough that a seek
         * that lands mid-buffer wastes little. 1 MiB is a good fit for a 2-hour film over a LAN.
         */
        const val BLOCK_SIZE = 1 shl 20
    }
}

/**
 * Routes by URI scheme: each scheme in [bySchemes] (e.g. `smb://` for a NAS recording, `optvrec://`
 * for one that's still being written) goes to its own source; everything else (http, https, file,
 * content) to the normal source. Lets one player handle live streams, VOD, internal recordings,
 * NAS recordings and in-progress captures without the caller caring which is which.
 */
@OptIn(UnstableApi::class)
class RoutingDataSourceFactory(
    private val default: DataSource.Factory,
    private val bySchemes: Map<String, DataSource.Factory>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RoutingDataSource(
            default.createDataSource(),
            bySchemes.mapValues { (_, factory) -> factory.createDataSource() },
        )
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val default: DataSource,
    private val byScheme: Map<String, DataSource>,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        default.addTransferListener(transferListener)
        byScheme.values.forEach { it.addTransferListener(transferListener) }
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase().orEmpty()
        val source = byScheme[scheme] ?: default
        android.util.Log.d(
            "SmbRoute",
            "scheme='$scheme' -> ${if (byScheme.containsKey(scheme)) "custom" else "DEFAULT(http)"} uri=${dataSpec.uri}",
        )
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }
}
