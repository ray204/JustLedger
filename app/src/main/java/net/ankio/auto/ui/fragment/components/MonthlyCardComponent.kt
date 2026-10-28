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

package net.ankio.auto.ui.fragment.components

import android.view.View
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import net.ankio.auto.utils.LedgerChanges
import net.ankio.auto.BuildConfig
import net.ankio.auto.R
import net.ankio.auto.databinding.CardMonthlyBinding
import net.ankio.auto.http.api.BillAPI
import net.ankio.auto.ui.api.BaseComponent
import net.ankio.auto.utils.BillTool
import net.ankio.auto.utils.PrefManager

/** Monthly totals only: no generated reports or spending advice. */
class MonthlyCardComponent(binding: CardMonthlyBinding) : BaseComponent<CardMonthlyBinding>(binding) {
    private var onNavigateToBills: (() -> Unit)? = null
    private var refreshJob: Job? = null
    private var totals: Map<String, Double>? = null

    fun setOnNavigateToBills(callback: () -> Unit) = apply {
        onNavigateToBills = callback
    }

    override fun onComponentCreate() {
        super.onComponentCreate()
        binding.privacyToggle.setOnClickListener {
            PrefManager.homePrivacyMode = !PrefManager.homePrivacyMode
            renderAmounts()
            binding.privacyToggle.announceForAccessibility(context.getString(
                if (PrefManager.homePrivacyMode) R.string.ledger_privacy_hidden
                else R.string.ledger_privacy_visible
            ))
        }
        binding.btnBills.setOnClickListener { onNavigateToBills?.invoke() }
        binding.btnSync.setOnClickListener { BillTool.syncBills() }
    }

    private fun renderAmounts() {
        val stats = totals
        fun amount(value: Double): String = when {
            PrefManager.homePrivacyMode -> "••••"
            stats == null -> "—"
            else -> BillTool.formatAmount(value)
        }
        val income = stats?.get("income") ?: 0.0
        val expense = stats?.get("expense") ?: 0.0
        binding.tvIncomeAmount.text = amount(income)
        binding.tvExpenseAmount.text = amount(expense)
        val totalExpense = stats?.get("totalExpense") ?: expense
        val occasional = stats?.get("occasionalExpense") ?: 0.0
        binding.totalExpenseHint.text = "含偶发总支出 ${amount(totalExpense)} · 偶发 ${amount(occasional)}"
        binding.totalExpenseHint.visibility = if (stats != null) View.VISIBLE else View.GONE
        binding.tvBalanceAmount.text = amount(income - totalExpense)
        binding.privacyToggle.isSelected = PrefManager.homePrivacyMode
    }

    override fun onComponentResume() {
        super.onComponentResume()
        binding.btnSync.visibility = if (PrefManager.bookApp != BuildConfig.APPLICATION_ID) View.VISIBLE else View.GONE
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        binding.monthLabel.text = context.getString(R.string.ledger_month, year, month) + " · " + PrefManager.baseCurrency
        refreshJob?.cancel()
        totals = null
        renderAmounts()
        binding.loadStatus.visibility = View.GONE
        refreshJob = componentScope.launch {
            LedgerChanges.changes.collectLatest {
                totals = BillAPI.getMonthlyStats(year, month)
                renderAmounts()
                binding.loadStatus.visibility = if (totals == null) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onComponentStop() {
        refreshJob?.cancel()
        super.onComponentStop()
    }

    override fun onComponentDestroy() {
        refreshJob?.cancel()
        onNavigateToBills = null
        super.onComponentDestroy()
    }
}
