package moe.ouom.neriplayer.core.provider.lxuser

import java.util.Locale

/**
 * How good a stream actually is, in four coarse buckets:
 *
 * 1 = 128k-ish, 2 = 320k-ish, 3 = lossless (16-bit), 4 = hi-res (24-bit or better).
 *
 * A bucket of `-1` means "no idea", which callers treat as acceptable so an
 * unknown-but-playable link is never thrown away.
 *
 * NeriPlayer's own quality keys are Netease's (`standard` / `higher` / `exhigh` /
 * `lossless` / `hires` / `jyeffect` / `sky` / `jymaster`). Every LX source is a
 * Netease substitute, so the measured bucket is reported back through those keys.
 */
internal const val LX_RANK_UNKNOWN = -1
internal const val LX_RANK_STANDARD = 1
internal const val LX_RANK_HIGH = 2
internal const val LX_RANK_LOSSLESS = 3
internal const val LX_RANK_HIRES = 4

/** The quality key the player should show for a measured bucket. */
internal fun lxRankToQualityKey(rank: Int): String? = when (rank) {
    LX_RANK_STANDARD -> "standard"
    LX_RANK_HIGH -> "exhigh"
    LX_RANK_LOSSLESS -> "lossless"
    LX_RANK_HIRES -> "hires"
    else -> null
}

/** The bucket a requested quality key is asking for. */
internal fun lxRankForQualityKey(qualityKey: String): Int =
    when (qualityKey.trim().lowercase(Locale.ROOT)) {
        "standard", "higher" -> LX_RANK_STANDARD
        "exhigh" -> LX_RANK_HIGH
        "lossless" -> LX_RANK_LOSSLESS
        "hires", "jyeffect", "sky", "jymaster" -> LX_RANK_HIRES
        else -> LX_RANK_HIRES
    }

/** The LX quality string to ask a source for, given a NeriPlayer quality key. */
internal fun lxQualityForQualityKey(qualityKey: String): String =
    when (lxRankForQualityKey(qualityKey)) {
        LX_RANK_STANDARD -> "128k"
        LX_RANK_HIGH -> "320k"
        LX_RANK_LOSSLESS -> "flac"
        else -> "flac24bit"
    }

/**
 * Tiers to try, from the requested one downwards.
 *
 * Public mirrors routinely accept a high tier and quietly serve something lower,
 * so a miss at one tier is worth retrying one step down before giving up.
 */
internal fun lxQualityFallbacks(requestedQuality: String): List<String> = when (requestedQuality) {
    "flac24bit" -> listOf("flac24bit", "flac", "320k", "128k")
    "flac" -> listOf("flac", "320k", "128k")
    "320k" -> listOf("320k", "128k")
    else -> listOf(requestedQuality)
}

/** Coarse bucket for whatever a source claims about its own answer, or a bitrate. */
internal fun lxQualityRank(raw: String?): Int {
    val value = raw?.lowercase(Locale.ROOT) ?: return LX_RANK_UNKNOWN
    if (value.isEmpty() || value == "null" || value == "undefined") return LX_RANK_UNKNOWN
    if (value.all(Char::isDigit)) {
        // Scripts that do report a number use bits per second (`br`), not kbps.
        val bitrate = value.toLongOrNull() ?: return LX_RANK_UNKNOWN
        return when {
            bitrate < 200_000L -> LX_RANK_STANDARD
            bitrate < 500_000L -> LX_RANK_HIGH
            bitrate < 1_000_000L -> LX_RANK_LOSSLESS
            else -> LX_RANK_HIRES
        }
    }
    return when (value) {
        "128k", "128", "standard", "l", "pq" -> LX_RANK_STANDARD
        "320k", "320", "exhigh", "high", "h", "hq" -> LX_RANK_HIGH
        "flac", "lossless", "sq", "999", "999k" -> LX_RANK_LOSSLESS
        "flac24bit", "hires", "hi-res", "hr", "atmos", "atmos_plus",
        "master", "sky", "jyeffect", "jymaster", "zq", "super",
        -> LX_RANK_HIRES
        else -> LX_RANK_UNKNOWN
    }
}

/** Bucket for a measured bitrate in kbps. */
internal fun lxRankForBitrateKbps(bitrateKbps: Long): Int = when {
    bitrateKbps <= 0L -> LX_RANK_UNKNOWN
    bitrateKbps < 200L -> LX_RANK_STANDARD
    bitrateKbps < 500L -> LX_RANK_HIGH
    bitrateKbps < 1_000L -> LX_RANK_LOSSLESS
    else -> LX_RANK_HIRES
}

/** Maps a resolved URL's extension onto a MIME type, for the player and the cache. */
internal fun lxMimeTypeForUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
    val extension = path.substringAfterLast('.', "")
    return when (extension) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "ogg", "oga" -> "audio/ogg"
        "aac" -> "audio/aac"
        "wav" -> "audio/wav"
        else -> null
    }
}
