/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package net.ankio.auto.ui.fragment.settings

import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import net.ankio.auto.BuildConfig
import net.ankio.auto.R
import net.ankio.auto.ui.api.BasePreferenceFragment
import net.ankio.auto.utils.CustomTabsHelper

class AboutPreferenceFragment : BasePreferenceFragment() {
    override fun getTitleRes() = R.string.setting_title_about
    override fun getPreferencesRes() = R.xml.settings_about
    override fun createDataStore() = object : PreferenceDataStore() {}
    override fun setupPreferences() {
        findPreference<Preference>("ledgerAbout")?.summary = "版本 ${BuildConfig.VERSION_NAME} · 数据保存在本机"
        findPreference<Preference>("openSourceCode")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(getString(R.string.setting_open_source_code_url)); true
        }
    }
}
