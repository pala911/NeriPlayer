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
 * File: moe.ouom.neriplayer.data/settings/CustomSourceSettings
 */

package moe.ouom.neriplayer.data.settings

import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.ksp.annotations.AutoSettingIcon
import moe.ouom.neriplayer.ksp.annotations.AutoSettingSpec
import moe.ouom.neriplayer.ksp.annotations.autoSwitchSetting

/**
 * 自定义音源（LX Music 用户脚本）的开关。
 *
 * 这里用字面量 [AutoSettingSpec]（`AutoSettingSpecSwitchItem` 会直接读写同一个 DataStore），
 * 而不是登记进 `AutoSettingsSchema`：该开关不需要进自动备份与设置搜索索引，而改动登记表会牵动
 * KSP 生成物与 `AutoSettingsGeneratedTest` 的精确断言，收益不成比例。
 */
object CustomSourceSettings {

    val enabled: AutoSettingSpec<Boolean> = autoSwitchSetting(
        key = "custom_source_enabled",
        defaultValue = false,
        titleRes = R.string.settings_custom_source_enabled,
        descriptionRes = R.string.settings_custom_source_enabled_desc,
        icon = AutoSettingIcon.Public
    )

    /**
     * 仅用于渲染「导入音源脚本」这一行（点击即打开文件选择器），不会被真正读写。
     * 复用 [AutoSettingSpec] 只是为了拿到与其它设置项一致的标题/描述/图标版式。
     */
    val importScript: AutoSettingSpec<Boolean> = autoSwitchSetting(
        key = "custom_source_import_action",
        defaultValue = false,
        titleRes = R.string.settings_custom_source_import,
        descriptionRes = R.string.settings_custom_source_import_desc,
        icon = AutoSettingIcon.Download
    )
}
