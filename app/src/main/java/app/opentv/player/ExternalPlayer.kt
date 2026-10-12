/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.opentv.R
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.shownName

/**
 * Hands a live channel to another installed player (VLC, MX Player, Just Player…).
 *
 * Shared by the channel menu's "Open in external player" and the "Play channels in an external
 * player" setting, so both resolve the stream the same way — including a Stalker portal's
 * per-play `create_link`, which the stored placeholder URL alone can't play.
 */
object ExternalPlayer {

    /**
     * @param chooser true always asks which app to use (the menu entry); false lets Android use
     *   the user's default video player, asking only the first time (the "always external" setting).
     */
    suspend fun play(context: Context, channel: Channel, chooser: Boolean) {
        val graph = ServiceLocator.get(context)
        val source = graph.sourceRepository.byId(channel.sourceId)
        val url = graph.catalogRepository.resolvePlaybackUrl(channel, source)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            putExtra("title", channel.shownName)
            // MX Player / VLC read the User-Agent from this header extra.
            putExtra("headers", arrayOf("User-Agent", source?.userAgent ?: Source.DEFAULT_USER_AGENT))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launch = if (chooser) {
            Intent.createChooser(intent, context.getString(R.string.guide_play_channel_with))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            intent
        }
        runCatching { context.startActivity(launch) }.onFailure {
            Toast.makeText(context, context.getString(R.string.guide_no_external_player), Toast.LENGTH_SHORT).show()
        }
    }
}
