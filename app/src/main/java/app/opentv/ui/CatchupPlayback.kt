/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import app.opentv.data.model.Channel
import app.opentv.data.model.Programme
import app.opentv.data.model.Source
import app.opentv.data.model.catchupCovers
import app.opentv.data.parser.M3uCatchup
import app.opentv.data.remote.XtreamApi

/**
 * The archive URL for a programme that has already aired.
 *
 * Two shapes of archive, one answer:
 *  - a playlist that describes its own catch-up gives a template, and the provider's own
 *    clock decides the timestamps;
 *  - an Xtream login gives the account, and the timeshift path is built from it.
 *
 * Null when there is no way back to the programme: a channel with no archive, or one the
 * provider has already pruned. Saying so is better than handing the player a link that
 * answers with a 404.
 */
fun catchupUrl(
    source: Source,
    channel: Channel,
    programme: Programme,
    nowUtcMillis: Long,
    xtreamApi: XtreamApi,
): String? {
    if (!channel.catchupCovers(programme.startUtcMillis, nowUtcMillis)) return null

    val template = channel.catchupSource
    return if (template != null) {
        M3uCatchup.buildUrl(
            template = template,
            startUtcMillis = programme.startUtcMillis,
            endUtcMillis = programme.endUtcMillis,
            nowUtcMillis = nowUtcMillis,
            correctionSecs = channel.catchupCorrectionSecs,
            zone = XtreamApi.zoneOf(source.timezone),
        )
    } else {
        val minutes = (programme.durationMillis / 60000).toInt().coerceAtLeast(1)
        xtreamApi.catchupUrl(source, channel.streamId, programme.startUtcMillis, minutes)
    }
}
