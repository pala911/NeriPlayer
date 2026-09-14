/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.core.player.resolver.netease/PlayerManagerNeteaseCustomSourceSwitch
 */

package moe.ouom.neriplayer.core.player.resolver.netease

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.model.PlaybackAudioInfo
import moe.ouom.neriplayer.core.player.model.PlaybackAudioSource
import moe.ouom.neriplayer.core.player.model.SongUrlResult
import moe.ouom.neriplayer.core.player.model.deriveCodecLabel
import moe.ouom.neriplayer.core.player.url.qualityLabelForNetease
import moe.ouom.neriplayer.core.provider.lxuser.CustomSourceRuntime
import moe.ouom.neriplayer.core.provider.lxuser.LxUserResolvedSource
import moe.ouom.neriplayer.core.provider.lxuser.lxRankForQualityKey
import moe.ouom.neriplayer.data.model.SongItem

private const val CUSTOM_SOURCE_TAG = "NERI-CustomSource"
private val customSourceCacheKeyUnsafeRegex = Regex("[^A-Za-z0-9_.-]+")

/**
 * 用用户导入的 LX 自定义音源解析网易云歌曲。
 *
 * 返回值语义与其它回退阶段一致：`null` 表示「开关关闭 / 没有脚本 / 全部失败」，调用方继续回退。
 * 该阶段排在 B 站自动源**之前**：B 站拿到的多是转录/翻录视频，音质明显不如音源站的直链。
 */
internal suspend fun PlayerManager.tryResolveNeteaseCustomSource(
    song: SongItem,
    requestedQualityKey: String,
    hasPlayableFallback: Boolean,
    urgent: Boolean = true
): SongUrlResult? {
    val appContext = application
    if (!CustomSourceRuntime.isEnabled(appContext)) return null
    val resolver = CustomSourceRuntime.resolver(appContext)
    if (!resolver.hasSources()) return null

    val resolved = withContext(Dispatchers.IO) {
        runCatching {
            resolver.resolve(
                song = song,
                requestedQualityKey = requestedQualityKey,
                hasPlayableFallback = hasPlayableFallback,
                urgent = urgent
            )
        }.onFailure { error ->
            NPLogger.w(CUSTOM_SOURCE_TAG, "custom source resolve failed songId=${song.id}", error)
        }.getOrNull()
    } ?: return null

    return buildCustomSourceSuccess(song, requestedQualityKey, resolved)
}

private fun PlayerManager.buildCustomSourceSuccess(
    song: SongItem,
    requestedQualityKey: String,
    resolved: LxUserResolvedSource
): SongUrlResult.Success {
    // 码率只能判断到"是不是 24bit 级"，分不出超清母带 / 沉浸环绕声 / 高清环绕声 / Hi-Res
    // 这些同属 24bit 的档位。所以实测档位证实达到了所选档位时，档位键就用所选档位，
    // 免得设了母带却显示 Hi-Res 让人以为拿错了；达不到所选档位时如实显示实测档位
    // （例如设母带但源站只有 16bit → 显示"无损"）。
    val displayQualityKey = if (resolved.rank >= lxRankForQualityKey(requestedQualityKey)) {
        requestedQualityKey
    } else {
        resolved.qualityKey
    }
    val mimeType = resolved.mimeType
    NPLogger.w(
        CUSTOM_SOURCE_TAG,
        "custom source selected: song=${song.name}, script=${resolved.scriptId}, " +
            "requested=$requestedQualityKey, measured=${resolved.qualityKey}, displayed=$displayQualityKey, " +
            "rank=${resolved.rank}, bitrateKbps=${resolved.bitrateKbps}, bytes=${resolved.contentLength}"
    )
    return SongUrlResult.Success(
        url = resolved.url,
        durationMs = song.durationMs.takeIf { it > 0L },
        mimeType = mimeType,
        expectedContentLength = resolved.contentLength,
        audioInfo = PlaybackAudioInfo(
            source = PlaybackAudioSource.CUSTOM,
            qualityKey = displayQualityKey,
            qualityLabel = displayQualityKey?.let { key ->
                qualityLabelForNetease(key) { getLocalizedString(it) }
            },
            codecLabel = deriveCodecLabel(mimeType),
            mimeType = mimeType,
            bitrateKbps = resolved.bitrateKbps
        ),
        // 缓存键与表示指纹仍用实测值：它们描述的是"实际这条流"，与显示用哪个档位名无关。
        representationIdentity = buildCustomSourceRepresentationIdentity(resolved),
        cacheKeyOverride = buildCustomSourceCacheKey(resolved)
    )
}

/**
 * 缓存键要带上脚本 id 与实测档位，否则换了脚本或档位后，旧字节会被当成同一份资源命中。
 */
internal fun buildCustomSourceCacheKey(resolved: LxUserResolvedSource): String {
    val scriptPart = sanitizeCustomSourceCacheKeyPart(resolved.scriptId)
    val qualityPart = sanitizeCustomSourceCacheKeyPart(resolved.qualityKey ?: "unknown")
    return "lx-$scriptPart-$qualityPart"
}

internal fun buildCustomSourceRepresentationIdentity(resolved: LxUserResolvedSource): String {
    return listOf(
        resolved.scriptId,
        resolved.rank.toString(),
        resolved.mimeType.orEmpty().lowercase(),
        resolved.bitrateKbps?.toString().orEmpty()
    ).joinToString(separator = "|")
}

private fun sanitizeCustomSourceCacheKeyPart(value: String): String {
    return customSourceCacheKeyUnsafeRegex
        .replace(value.trim(), "_")
        .trim('_')
        .ifBlank { "unknown" }
}
