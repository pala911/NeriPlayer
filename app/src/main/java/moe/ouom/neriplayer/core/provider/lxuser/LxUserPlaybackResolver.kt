package moe.ouom.neriplayer.core.provider.lxuser

import android.content.Context
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

private const val TAG = "NERI-LxSource"

/** A playable link produced by an LX user source. */
internal data class LxUserResolvedSource(
    val scriptId: String,
    val url: String,
    /** NeriPlayer quality key for the measured bucket, or null when unmeasurable. */
    val qualityKey: String?,
    val rank: Int,
    val bitrateKbps: Int?,
    val contentLength: Long?,
    val mimeType: String?,
)

private data class LxProbeResult(
    val rank: Int,
    val totalBytes: Long?,
    val bitrateKbps: Int?,
)

/**
 * Resolves a Netease song through the LX Music user sources the user imported.
 *
 * The shape of the search follows the lessons learned on the MeloX port:
 *
 * - Tiers loop outside, sources inside, so a better tier at a slower platform beats
 *   a worse tier at a faster one.
 * - The tier a source was *asked* for proves nothing about the file it returns, so a
 *   resolved link is measured (one ranged GET against the CDN) before being accepted.
 * - Every source but `wy` is matched by name, so `wy` - the only one that can be
 *   matched by Netease song id - goes first, and its lossless answer is taken
 *   immediately rather than spending seconds hunting a 24-bit version elsewhere.
 * - Kuwo is absent on purpose: its endpoint answers with 22-60 kbps fragments.
 */
internal class LxUserPlaybackResolver(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Cheap check so callers can skip the whole stage when nothing is imported. */
    fun hasSources(): Boolean = LxUserSourceStore.list(appContext).isNotEmpty()

    /**
     * @param hasPlayableFallback true when the caller already holds a complete stream
     *   it can fall back to. Hunting for a better tier is then a bonus, so the search
     *   gets a short leash; when the only alternative is a 30 s trial clip it is worth
     *   waiting much longer.
     * @param urgent false for background work such as prefetching, so a track the user
     *   actually tapped is not queued behind upcoming ones.
     */
    fun resolve(
        song: SongItem,
        requestedQualityKey: String,
        hasPlayableFallback: Boolean,
        urgent: Boolean = true,
    ): LxUserResolvedSource? {
        val title = (song.originalName ?: song.name).trim()
        val artist = (song.originalArtist ?: song.artist).trim()
        if (title.isBlank() || artist.isBlank()) return null

        val requestedQuality = lxQualityForQualityKey(requestedQualityKey)
        // `wy` can be matched by Netease song id, which is by far the most reliable
        // route; the rest are name searches and only help when the id lookup fails.
        val candidateSources = LX_SOURCES

        NPLogger.i(TAG, "resolve start songId=${song.id} quality=$requestedQuality title=${title.take(40)} artist=${artist.take(40)}")

        for (record in LxUserSourceStore.list(appContext)) {
            val script = LxUserSourceStore.script(appContext, record.id) ?: continue
            var phase = "load"
            val result: LxUserResolvedSource? = runCatching {
                LxUserRuntimeSession.withRuntime(record.id, script, urgent) { runtime ->
                    phase = "request"
                    val start = SystemClock.elapsedRealtime()
                    val deadline = start + if (hasPlayableFallback) {
                        RESOLVE_BUDGET_WITH_FALLBACK_MS
                    } else {
                        RESOLVE_BUDGET_MS
                    }
                    var best: LxUserResolvedSource? = null
                    var bestRank = LX_RANK_UNKNOWN
                    var probes = 0
                    // A source that already handed back a link has shown us its best;
                    // asking again one tier lower almost always returns the same file.
                    val answered = mutableSetOf<String>()

                    for (requestedTier in lxQualityFallbacks(requestedQuality)) {
                        val need = lxQualityRank(requestedTier)
                        for (source in candidateSources) {
                            if (source in answered) continue
                            if (!runtime.supports(source, "musicUrl")) continue
                            // Once something playable is in hand, stop hunting much
                            // sooner: the user is waiting on a song, not on a tier.
                            val softDeadline = if (best == null) deadline else start + FALLBACK_BUDGET_MS
                            if (SystemClock.elapsedRealtime() > softDeadline) {
                                NPLogger.w(TAG, "budget exhausted script=${record.id} tier=$requestedTier source=$source best=$bestRank")
                                return@withRuntime best
                            }
                            LxUserRuntimeSession.awaitRequestSlot(MIN_REQUEST_GAP_MS)
                            val sourceQuality = runtime.qualityFor(source, requestedTier)
                            val musicInfo = buildMusicInfo(song, source, requestedTier)
                            // Give the action whatever is left of this song's budget
                            // rather than a fixed slice: a source that answers slowly
                            // still gets its answer in, but may not overshoot the time
                            // the user is willing to wait.
                            val actionBudget = (softDeadline - SystemClock.elapsedRealtime())
                                .coerceAtLeast(MIN_ACTION_TIMEOUT_MS)
                            NPLogger.d(TAG, "candidate script=${record.id} source=$source requested=$requestedTier actual=$sourceQuality")

                            val value = runCatching {
                                runtime.callAction(
                                    "musicUrl",
                                    musicInfo + mapOf(
                                        "source" to source,
                                        "type" to sourceQuality,
                                        "musicInfo" to musicInfo,
                                    ),
                                    actionBudget,
                                )
                            }.onFailure {
                                NPLogger.w(TAG, "candidate failed script=${record.id} source=$source quality=$sourceQuality", it)
                            }.getOrNull()

                            val url = when (value) {
                                is String -> value
                                is Map<*, *> -> value["url"]?.toString()
                                else -> null
                            }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            // Most scripts hand back the raw API body, so the tier that
                            // actually came back is usually one of these fields.
                            val reported = (value as? Map<*, *>)?.let { body ->
                                sequenceOf("quality", "type", "level", "br", "bitrate")
                                    .mapNotNull { body[it]?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                                    .firstOrNull()
                            }

                            var contentLength: Long? = null
                            var bitrateKbps: Int? = null
                            val reportedRank = lxQualityRank(reported)
                            val rank = if (url == null) {
                                LX_RANK_UNKNOWN
                            } else if (reportedRank >= 0) {
                                reportedRank
                            } else if (song.durationMs > 0L && probes < MAX_PROBES) {
                                // The scripts never report a tier, so measure the real
                                // one: a single ranged GET against the final CDN (not the
                                // rate-limited LX API) yields the total size, and the
                                // track duration turns that into a bitrate.
                                probes++
                                probeQuality(url, song.durationMs).also { probe ->
                                    contentLength = probe.totalBytes
                                    bitrateKbps = probe.bitrateKbps
                                }.rank
                            } else {
                                LX_RANK_UNKNOWN
                            }
                            NPLogger.d(
                                TAG,
                                "candidate result script=${record.id} source=$source quality=$sourceQuality " +
                                    "reported=$reported rank=$rank need=$need link=${url != null} bytes=$contentLength bitrateKbps=$bitrateKbps",
                            )
                            if (url == null) continue
                            answered += source
                            val resolved = LxUserResolvedSource(
                                scriptId = record.id,
                                url = url,
                                qualityKey = lxRankToQualityKey(rank),
                                rank = rank,
                                bitrateKbps = bitrateKbps,
                                contentLength = contentLength,
                                mimeType = lxMimeTypeForUrl(url),
                            )
                            // Only accept a link that is at least as good as the tier
                            // being tried, but keep the best miss around in case nothing
                            // reaches the bar. Lossless from `wy` is taken straight away:
                            // these mirrors rarely hold a 24-bit master of a track the
                            // official API only serves as a clip.
                            if (rank < 0 || rank >= need || (source == "wy" && rank >= LX_RANK_LOSSLESS)) {
                                return@withRuntime resolved
                            }
                            if (rank > bestRank) {
                                bestRank = rank
                                best = resolved
                            }
                        }
                    }
                    best
                }
            }.onFailure { error ->
                NPLogger.w(TAG, "failed script=${record.id} phase=$phase songId=${song.id}", error)
            }.getOrNull()

            if (result != null) {
                NPLogger.i(TAG, "resolved songId=${song.id} script=${result.scriptId} quality=${result.qualityKey} bitrateKbps=${result.bitrateKbps}")
                return result
            }
            NPLogger.w(TAG, "exhausted songId=${song.id} script=${record.id}")
        }

        NPLogger.w(TAG, "unresolved songId=${song.id} sources=${LxUserSourceStore.list(appContext).size}")
        return null
    }

    /**
     * Asks the CDN for its total size with a `Range: bytes=0-0` GET, which returns the
     * full length in `Content-Range` without downloading the audio, and divides by the
     * track duration. Returns an unknown rank when the size cannot be determined.
     */
    private fun probeQuality(url: String, durationMs: Long): LxProbeResult {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-0")
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            connection.connect()
            val total = run {
                val contentRange = connection.getHeaderField("Content-Range")
                if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
                    contentRange.substring(contentRange.lastIndexOf('/') + 1).toLongOrNull()
                } else {
                    connection.contentLengthLong.takeIf { it > 0L }
                }
            }
            connection.disconnect()
            if (total == null || total <= 0L) return LxProbeResult(LX_RANK_UNKNOWN, null, null)
            val seconds = (durationMs.coerceAtLeast(1L) / 1000L).coerceAtLeast(1L)
            val bitrateKbps = ((total * 8L) / seconds / 1000L).toInt()
            NPLogger.d(TAG, "probe total=$total bitrateKbps=$bitrateKbps rank=${lxRankForBitrateKbps(bitrateKbps.toLong())}")
            return LxProbeResult(lxRankForBitrateKbps(bitrateKbps.toLong()), total, bitrateKbps)
        } catch (error: Throwable) {
            NPLogger.d(TAG, "probe failed: ${error.javaClass.simpleName}")
            return LxProbeResult(LX_RANK_UNKNOWN, null, null)
        }
    }

    private companion object {
        /**
         * Sources to try, in order, for a Netease song. `wy` first because it is the
         * only one that can be matched by Netease song id; Kuwo is deliberately absent
         * because its endpoint answers with 22-60 kbps fragments.
         */
        val LX_SOURCES = listOf("wy", "kg", "tx", "mg")

        /**
         * Cap while the only alternative is the official trial clip. A source costs
         * 1-4 s because the scripts call a telemetry endpoint before their real
         * request, so this has to cover a couple of attempts. Giving up early here
         * leaves the user on a 30 s clip, which is worth waiting to avoid.
         */
        const val RESOLVE_BUDGET_MS = 10_000L
        /**
         * Cap when the caller already holds a complete stream. Chasing a higher tier is
         * then a bonus, and the user should not wait long for it.
         */
        const val RESOLVE_BUDGET_WITH_FALLBACK_MS = 3_000L
        /** Shorter cap once a third-party link exists and we are only chasing a better tier. */
        const val FALLBACK_BUDGET_MS = 3_000L
        /**
         * Pause between actions. Each action fires two to three upstream requests of
         * its own, so the sources' own "no more than 4 requests per 2 s" rule needs a
         * wider gap than the action count suggests; at 400 ms the mirror answered
         * `429 请求过于频繁` and the affected songs fell back to the trial clip.
         */
        const val MIN_REQUEST_GAP_MS = 800L
        /** Floor for one action so a nearly exhausted budget still lets a request finish. */
        const val MIN_ACTION_TIMEOUT_MS = 1_500L
        /** At most this many CDN probes per resolve; each one is a single ranged GET. */
        const val MAX_PROBES = 4
        /** Give up on a probe after this long so a slow CDN cannot stall the shared thread. */
        const val PROBE_TIMEOUT_MS = 2000
    }
}

/**
 * The `musicInfo` shape LX user sources expect. Scripts read both the flat fields and
 * the nested `meta`, so both are filled in; `source` and `quality` describe the source
 * and tier currently being tried, otherwise the script keeps asking for the top tier
 * and the fallbacks never happen.
 */
private fun buildMusicInfo(song: SongItem, source: String, quality: String): Map<String, Any?> {
    val title = (song.originalName ?: song.name).trim()
    val artist = (song.originalArtist ?: song.artist).trim()
    val songId = song.id.toString()
    val durationSeconds = song.durationMs.takeIf { it > 0L }?.div(1_000L)
    val interval = durationSeconds?.let { seconds ->
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L)
    }
    val meta = mapOf(
        "songId" to songId,
        "albumId" to song.albumId.toString(),
        "albumName" to song.album,
        "qualitys" to listOf(quality),
        "_qualitys" to emptyMap<String, Any?>(),
        "picUrl" to song.coverUrl.orEmpty(),
    )
    return mapOf(
        "id" to songId,
        "songId" to songId,
        "songmid" to songId,
        "mid" to songId,
        "hash" to songId,
        "name" to title,
        "title" to title,
        "singer" to artist,
        "artist" to artist,
        "source" to source,
        "interval" to interval,
        "duration" to durationSeconds,
        "durationMs" to song.durationMs,
        "quality" to quality,
        "albumName" to song.album,
        "picUrl" to song.coverUrl,
        "meta" to meta,
    )
}
