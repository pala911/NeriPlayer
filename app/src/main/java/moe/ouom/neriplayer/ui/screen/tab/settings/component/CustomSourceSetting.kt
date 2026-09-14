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
 * File: moe.ouom.neriplayer.ui.screen.tab.settings/component/CustomSourceSetting
 */

package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.core.provider.lxuser.CustomSourceRuntime
import moe.ouom.neriplayer.core.provider.lxuser.LxUserSourceRecord
import moe.ouom.neriplayer.core.provider.lxuser.LxUserSourceStore
import moe.ouom.neriplayer.data.config.LimitedTextReader
import moe.ouom.neriplayer.data.settings.CustomSourceSettings

private const val CUSTOM_SOURCE_TAG = "NERI-CustomSource"
private const val MAX_SCRIPT_BYTES = 9_000_000L

/**
 * 「播放源」设置分组里的自定义音源区块：开关 + 导入脚本 + 已导入列表。
 *
 * 开关本身是标准的 [AutoSettingSpec]（见 [CustomSourceSettings]），这里只额外做两件事：
 * 变更后让 [CustomSourceRuntime] 的缓存失效（下次解析重读），以及脚本的导入/删除。
 */
@Composable
internal fun CustomSourceSetting(
    modifier: Modifier = Modifier,
    highlightTargetId: String? = null,
    highlightPulse: Int = 0,
    onHighlightFinished: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf(LxUserSourceStore.list(context)) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val text = LimitedTextReader.readUtf8(context, uri, MAX_SCRIPT_BYTES)
                    LxUserSourceStore.import(context, text)
                }.onFailure { error ->
                    NPLogger.w(CUSTOM_SOURCE_TAG, "import script failed", error)
                }.getOrNull()
            }
            if (imported == null) {
                // 失败只记日志：脚本文件可能是任意内容，这里不弹窗打断用户，
                // 列表刷新后没有新条目本身就是反馈。
                NPLogger.w(CUSTOM_SOURCE_TAG, "import rejected: not a valid LX source script")
            }
            scripts = LxUserSourceStore.list(context)
        }
    }

    Column(modifier = modifier) {
        AutoSettingSpecSwitchItem(
            setting = CustomSourceSettings.enabled,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished,
            afterCheckedChange = { CustomSourceRuntime.invalidate() }
        )
        AutoSettingSpecListItem(
            setting = CustomSourceSettings.importScript,
            highlightTargetId = highlightTargetId,
            highlightPulse = highlightPulse,
            onHighlightFinished = onHighlightFinished,
            onClick = { importLauncher.launch(arrayOf("*/*")) }
        )

        if (scripts.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_custom_source_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        } else {
            Text(
                text = stringResource(R.string.settings_custom_source_imported, scripts.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
            scripts.forEach { record ->
                CustomSourceScriptItem(
                    record = record,
                    onRemove = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { LxUserSourceStore.remove(context, record.id) }
                                    .onFailure { error ->
                                        NPLogger.w(CUSTOM_SOURCE_TAG, "remove script failed id=${record.id}", error)
                                    }
                            }
                            scripts = LxUserSourceStore.list(context)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CustomSourceScriptItem(
    record: LxUserSourceRecord,
    onRemove: () -> Unit
) {
    val metadata = record.metadata
    val title = metadata.name?.takeIf { it.isNotBlank() } ?: record.id
    val details = listOfNotNull(
        metadata.version?.takeIf { it.isNotBlank() },
        metadata.author?.takeIf { it.isNotBlank() }
    ).joinToString(separator = " · ")
    val supporting: (@Composable () -> Unit)? = if (details.isBlank()) {
        null
    } else {
        { Text(details) }
    }

    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting,
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.settings_custom_source_remove),
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
