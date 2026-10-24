/*
 * Copyright (C) 2023 ankio(ankio@ankio.net)
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


package net.ankio.auto.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.navigation.fragment.findNavController
import net.ankio.auto.R
import net.ankio.auto.databinding.FragmentSettingBinding
import net.ankio.auto.ui.api.BaseFragment
import net.ankio.auto.ui.activity.MainActivity
import net.ankio.auto.utils.PrefManager

class SettingFragment : BaseFragment<FragmentSettingBinding>() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val routes = mapOf(
            binding.settingRecording to R.id.action_settingFragment_to_recordingPreferenceFragment,
            binding.settingInteraction to R.id.action_settingFragment_to_interactionPreferenceFragment,
            binding.settingDataManagement to R.id.action_settingFragment_to_dataManagementPreferenceFragment,
            binding.settingSystem to R.id.action_settingFragment_to_systemPreferenceFragment,
            binding.settingAbout to R.id.action_settingFragment_to_aboutPreferenceFragment,
            binding.settingRules to R.id.ruleFragment
        )
        routes.forEach { (item, route) -> item.setOnClickListener { findNavController().navigate(route) } }
        binding.settingBooks.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("分类与账户").setItems(arrayOf("分类", "账户", "账本")) { _, index ->
                    when (index) {
                        0 -> net.ankio.auto.ui.api.BaseSheetDialog.create<net.ankio.auto.ui.dialog.BookSelectorDialog>(requireContext())
                            .setShowSelect(false).setCallback { book, _ ->
                                findNavController().navigate(R.id.categoryFragment, Bundle().apply { putString(CategoryFragment.ARG_BOOK_MODEL, com.google.gson.Gson().toJson(book)) })
                            }.show()
                        1 -> findNavController().navigate(R.id.assetFragment)
                        else -> findNavController().navigate(R.id.bookFragment)
                    }
                }.show()
        }
        binding.settingCapture.setOnClickListener {
            PrefManager.introIndex = net.ankio.auto.ui.adapter.IntroPagerAdapter.IntroPage.PERMISSION.ordinal
            startActivity(Intent(requireContext(), MainActivity::class.java))
        }
    }
}
