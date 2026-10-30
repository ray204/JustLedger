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
    private var incomeVisible = false
    private var balanceVisible = false

    fun setOnNavigateToBills(callback: () -> Unit) = apply {
        onNavigateToBills = callback
    }

    override fun onComponentCreate() {
        super.onComponentCreate()
        binding.privacyToggle.setOnClickListener {
            PrefManager.homePrivacyMode = !PrefManager.homePrivacyMode
            renderAmounts()
        }
        binding.incomePrivacyToggle.setOnClickListener {
            incomeVisible = !incomeVisible
            renderAmounts()
        }
        binding.balancePrivacyToggle.setOnClickListener {
            balanceVisible = !balanceVisible
            renderAmounts()
        }
        renderAmounts()
        binding.btnBills.setOnClickListener { onNavigateToBills?.invoke() }
        binding.btnSync.setOnClickListener { BillTool.syncBills() }
    }

    private fun renderAmounts() {
        val stats = totals
        fun amount(value: Double, hidden: Boolean = PrefManager.homePrivacyMode): String = when {
            hidden -> "••••"
            stats == null -> "—"
            else -> BillTool.formatAmount(value)
        }
        val income = stats?.get("income") ?: 0.0
        val expense = stats?.get("expense") ?: 0.0
        binding.tvIncomeAmount.text = amount(income, !incomeVisible)
        binding.tvExpenseAmount.text = amount(expense)
        val totalExpense = stats?.get("totalExpense") ?: expense
        val occasional = stats?.get("occasionalExpense") ?: 0.0
        binding.totalExpenseHint.text = "总支出 ${amount(totalExpense)} · 偶发 ${amount(occasional)}"
        binding.totalExpenseHint.visibility = if (stats != null) View.VISIBLE else View.GONE
        binding.tvBalanceAmount.text = amount(income - totalExpense, !balanceVisible)
        fun eye(button: com.google.android.material.button.MaterialButton, visible: Boolean, show: Int, hide: Int) {
            button.isSelected = visible
            button.contentDescription = context.getString(if (visible) hide else show)
            button.setIconResource(if (visible) R.drawable.ledger_eye else R.drawable.ledger_eye_off)
        }
        eye(binding.privacyToggle, !PrefManager.homePrivacyMode, R.string.ledger_show_expense, R.string.ledger_hide_expense)
        eye(binding.incomePrivacyToggle, incomeVisible, R.string.ledger_show_income, R.string.ledger_hide_income)
        eye(binding.balancePrivacyToggle, balanceVisible, R.string.ledger_show_balance, R.string.ledger_hide_balance)
    }

    override fun onComponentResume() {
        super.onComponentResume()
        incomeVisible = false
        balanceVisible = false
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
        incomeVisible = false
        balanceVisible = false
        renderAmounts()
        super.onComponentStop()
    }

    override fun onComponentDestroy() {
        refreshJob?.cancel()
        onNavigateToBills = null
        super.onComponentDestroy()
    }
}
