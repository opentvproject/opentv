/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.player

import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.Locale

/**
 * Prefers the software decoder over the hardware one.
 *
 * Some Android TV boxes ship a hardware decoder that mangles a particular codec: the
 * picture stutters, or turns green, and the app gets blamed for it. The software decoder is
 * slower but correct on those boxes, so it goes first — and the hardware one stays in the
 * list behind it, because ExoPlayer tries the decoders in the order it is given and falls
 * back when the first refuses to start.
 *
 * A secure or tunnelled stream has to use the platform's own decoder, so the order is left
 * alone for those.
 */
internal class SoftwareFirstCodecSelector : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunneling: Boolean,
    ): List<MediaCodecInfo> {
        // The platform's own query, so the filtering it does — what the box actually has,
        // what the stream needs — is not re-implemented here.
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunneling)
        if (requiresSecureDecoder || requiresTunneling) return all
        val (software, hardware) = all.partition { isSoftware(it) }
        return software + hardware
    }

    /**
     * Whether a codec is the software one.
     *
     * The platform's software decoders are named after the AOSP project that wrote them
     * (`c2.android.*`, `OMX.google.*`); the hardware ones carry the chipmaker's name. There
     * is no API for "is this decoder in software" — this is the naming convention every
     * Android box follows, and it is only ever used to *reorder* a list, so a wrong guess
     * costs nothing but the preference not taking effect.
     */
    private fun isSoftware(info: MediaCodecInfo): Boolean {
        val name = info.name.lowercase(Locale.ROOT)
        return name.contains("google") || name.contains("c2.android") || name.contains("omx.google")
    }
}
