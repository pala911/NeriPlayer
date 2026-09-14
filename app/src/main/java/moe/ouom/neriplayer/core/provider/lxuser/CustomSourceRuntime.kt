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
 * File: moe.ouom.neriplayer.core.provider.lxuser/CustomSourceRuntime
 */

package moe.ouom.neriplayer.core.provider.lxuser

import android.content.Context
import kotlinx.coroutines.flow.first
import moe.ouom.neriplayer.data.settings.AutoSettingSpecRepository
import moe.ouom.neriplayer.data.settings.CustomSourceSettings

/**
 * 自定义音源阶段的进程级状态：开关的同步读取入口 + 解析器单例。
 *
 * 开关的真身是 DataStore 里的设置项（见 [CustomSourceSettings]）。播放解析路径在线程池里跑，
 * 不适合每次解析都挂起去读 DataStore，所以这里缓存一份；设置项变更时由 UI 调用 [invalidate]
 * 让下次解析重新读取，保证只有 DataStore 一个事实来源（备份恢复也不会和缓存不一致）。
 */
internal object CustomSourceRuntime {

    @Volatile
    private var enabledCache: Boolean? = null

    private var resolverRef: LxUserPlaybackResolver? = null

    /** 读取开关；首次调用会挂起读一次 DataStore，之后走缓存。 */
    suspend fun isEnabled(context: Context): Boolean {
        enabledCache?.let { return it }
        val appContext = context.applicationContext
        val value = runCatching {
            AutoSettingSpecRepository(appContext).flow(CustomSourceSettings.enabled).first()
        }.getOrDefault(CustomSourceSettings.enabled.defaultValue)
        enabledCache = value
        return value
    }

    /** 设置项变更后调用，让下次解析重新读 DataStore。 */
    fun invalidate() {
        enabledCache = null
    }

    fun resolver(context: Context): LxUserPlaybackResolver =
        resolverRef ?: synchronized(this) {
            resolverRef ?: LxUserPlaybackResolver(context.applicationContext).also { resolverRef = it }
        }
}
