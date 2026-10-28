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

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import net.ankio.auto.utils.LedgerChanges
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialFadeThrough
import net.ankio.auto.R
import net.ankio.auto.databinding.FragmentBillBinding
import net.ankio.auto.http.api.BillAPI
import net.ankio.auto.storage.Logger
import net.ankio.auto.ui.adapter.BillAdapter
import net.ankio.auto.ui.api.BasePageFragment
import net.ankio.auto.ui.api.BaseSheetDialog
import net.ankio.auto.ui.components.LedgerChartData
import net.ankio.auto.utils.PaymentChannel
import net.ankio.auto.ui.components.MaterialSearchView
import net.ankio.auto.ui.components.WrapContentLinearLayoutManager
import net.ankio.auto.ui.dialog.BillEditorDialog
import net.ankio.auto.ui.dialog.BillMoreDialog
import net.ankio.auto.ui.dialog.BottomSheetDialogBuilder
import net.ankio.auto.ui.models.OrderGroup
import net.ankio.auto.ui.utils.ListPopupUtilsGeneric
import net.ankio.auto.utils.BillTool
import net.ankio.auto.utils.DateUtils
import org.ezbook.server.tools.OccasionalPolicy
import org.ezbook.server.constant.BillState


open class BillFragment : BasePageFragment<OrderGroup, FragmentBillBinding>() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough().apply { duration = 220L }
        exitTransition = MaterialFadeThrough().apply { duration = 160L }
    }

    /** 时间筛选模式：按月或全部 */
    private enum class TimeFilterMode { MONTH, ALL }

    // 同步状态筛选：初始化为全部状态
    private var syncType = mutableListOf(
        BillState.Synced.name,
        BillState.Edited.name,
        BillState.Wait2Edit.name
    )

    /** 默认按月筛选，保持现有行为不变 */
    private var timeFilterMode: TimeFilterMode = TimeFilterMode.MONTH

    private var currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    private var currentMonth: Int =
        java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1

    private var searchKeyword = ""
    private var selectedChannel: String? = null
    private var selectedCategory: String? = null
    private var selectedScope = OccasionalPolicy.Scope.DAILY

    override val pageSize: Int = Int.MAX_VALUE

    /**
     * 加载数据：使用服务端分组，避免客户端重复分组导致的性能问题
     */
    override suspend fun loadData(): List<OrderGroup> {
        // 服务端已经完成分组，直接返回
        val groups = if (timeFilterMode == TimeFilterMode.ALL) {
            BillAPI.listGrouped(syncType, keyword = searchKeyword)
        } else {
            BillAPI.listGrouped(syncType, currentYear, currentMonth, searchKeyword)
        }
        val rows = groups.flatMap { it.bills }
        val channels = rows.map { PaymentChannel.name(it) }.distinct().sorted()
        val categories = rows.map { LedgerChartData.category(it) }.distinct().sorted()
        val scoped = groups.map { group -> group.copy(bills = group.bills.filter {
            LedgerChartData.matches(it, selectedCategory) &&
                (selectedChannel == null || PaymentChannel.name(it) == selectedChannel)
        }) }.filter { it.bills.isNotEmpty() }
        val filtered = scoped.map { group -> group.copy(bills = group.bills.filter { OccasionalPolicy.matches(it, selectedScope) }) }
            .filter { it.bills.isNotEmpty() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            if (view != null) {
                binding.ledgerCharts.setBills(scoped.flatMap { it.bills }, selectedScope)
                binding.frequencyFilter.text = selectedScope.label
                binding.frequencyFilter.contentDescription = "账单范围：${selectedScope.label}"
                binding.frequencyFilter.setOnClickListener { button ->
                    (binding.topAppBar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.clearFocus()
                    ListPopupUtilsGeneric.create<OccasionalPolicy.Scope>(requireContext()).setAnchor(button)
                        .setList(linkedMapOf("日常账单" to OccasionalPolicy.Scope.DAILY,
                            "偶发账单" to OccasionalPolicy.Scope.OCCASIONAL, "全部账单" to OccasionalPolicy.Scope.ALL))
                        .setSelectedValue(selectedScope).setOnItemClick { _, _, scope ->
                            selectedScope = scope; reloadSilently()
                        }.show()
                }
                bindDropdown(binding.channelFilter, "全部渠道", channels, selectedChannel) {
                    selectedChannel = it; reloadSilently()
                }
                bindDropdown(binding.categoryFilter, "全部分类", categories, selectedCategory) {
                    selectedCategory = it; reloadSilently()
                }
            }
        }
        return filtered
    }

    private fun bindDropdown(
        button: com.google.android.material.button.MaterialButton,
        all: String, options: List<String>, selected: String?, onSelect: (String?) -> Unit
    ) {
        button.text = selected ?: all
        button.contentDescription = "${if (all == "全部分类") "分类" else "渠道"}筛选：${selected ?: all}"
        button.setOnClickListener {
            // Avoid restoring the search keyboard over the filtered list when the popup closes.
            (binding.topAppBar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.clearFocus()
            // Keep an empty selected category available until the user explicitly resets it.
            val entries = linkedMapOf(all to "")
            (options + listOfNotNull(selected)).distinct().forEach { entries[it] = it }
            ListPopupUtilsGeneric.create<String>(requireContext())
                .setAnchor(button).setList(entries).setSelectedValue(selected ?: "")
                .setOnItemClick { _, _, value -> onSelect(value.takeIf { it.isNotEmpty() }) }.show()
        }
    }

    private val adapter = BillAdapter()

    override fun onCreateAdapter(): RecyclerView.Adapter<*> {
        val recyclerView = binding.statusPage.contentView!!
        recyclerView.layoutManager = WrapContentLinearLayoutManager(requireContext())

        adapter.setOnItemClickListener { item, position, itemAdapter ->
            BaseSheetDialog.create<BillEditorDialog>(requireContext())
                .setBillInfo(item)
                .setOnConfirm { updatedBill ->
                    Logger.i("编辑账单: ${updatedBill.id}")
                    itemAdapter.updateItem(position, updatedBill)
                    reloadSilently()

                }
                .show()
        }

        // 设置长按事件
        adapter.setOnItemLongClickListener { item, _, itemAdapter ->
            // Always ask, including installations with the old confirmation setting disabled.
            showDeleteConfirmDialog { deleteBill(item, itemAdapter) }
        }

        // 设置更多按钮点击事件
        adapter.setOnMoreClickListener { item, itemAdapter ->
            BaseSheetDialog.create<BillMoreDialog>(requireContext())
                .setBillInfo(item)
                .setOnReload {
                    it.dismiss()
                    reload()
                }
                .show()
        }

        setupFilters()
        return adapter
    }


    /**
     * 设置筛选器
     */
    private fun setupFilters() {
        setupMonthFilter()
        setupStatusFilter()
    }

    /**
     * 设置月份筛选器
     */
    private fun setupMonthFilter() {
        updateMonthFilterLabel()
        binding.inputMonthButton.setOnClickListener { showMonthPickerDialog() }
        binding.inputMonthChevron.setOnClickListener {
            val filterOptions = linkedMapOf(
                getString(R.string.filter_time_month) to TimeFilterMode.MONTH,
                getString(R.string.filter_time_all) to TimeFilterMode.ALL,
            )
            ListPopupUtilsGeneric.create<Map.Entry<String, TimeFilterMode>>(requireContext())
                .setAnchor(it)
                .setList(
                    filterOptions.entries.associateBy(
                        { entry -> entry.key },
                        { value -> value })
                )
                .setOnItemClick { _, _, entry ->
                    if (entry.value == TimeFilterMode.MONTH) {
                        showMonthPickerDialog()
                    } else {
                        timeFilterMode = TimeFilterMode.ALL
                        updateMonthFilterLabel()
                        reload()
                    }
                }
                .setOnDismiss {
                    binding.inputMonthChevron.isChecked = false
                }
                .show()
        }
    }

    /** 显示月份选择弹窗，并在确认后更新筛选条件 */
    private fun showMonthPickerDialog() {
        BaseSheetDialog.create<net.ankio.auto.ui.dialog.DateTimePickerDialog>(requireContext())
            .setTitle(getString(R.string.select_month))
            .setYearMonthOnly(true)
            .setOnDateTimeSelected { year, month, _, _, _ ->
                timeFilterMode = TimeFilterMode.MONTH
                currentYear = year
                currentMonth = month
                updateMonthFilterLabel()
                reload()
            }
            .setOnDismiss {
                binding.inputMonthChevron.isChecked = false
            }
            .show()
    }

    /** 按当前筛选模式刷新时间筛选按钮文案 */
    private fun updateMonthFilterLabel() {
        binding.inputMonthButton.text = if (timeFilterMode == TimeFilterMode.ALL) {
            getString(R.string.filter_time_all)
        } else {
            formatMonthLabel(currentYear, currentMonth)
        }
    }

    /**
     * 设置状态筛选器
     */
    private fun setupStatusFilter() {
        val statusItems = linkedMapOf(
            getString(R.string.filter_type_all) to null,
            "已入账" to BillState.Edited,
            getString(R.string.item_wait_edit) to BillState.Wait2Edit
        )
        binding.inputStatusButton.text = statusItems.keys.first()
        binding.inputStatusButton.setOnClickListener { binding.inputStatusChevron.performClick() }
        binding.inputStatusChevron.setOnClickListener { anchorView ->
            ListPopupUtilsGeneric.create<Map.Entry<String, BillState?>>(requireContext())
                .setAnchor(anchorView)
                .setList(statusItems.entries.associateBy({ it.key }, { it }))
                .setOnItemClick { _, key, entry ->
                    updateSyncTypeFilter(entry.value)
                    binding.inputStatusButton.text = key
                    reload()
                }
                .setOnDismiss {
                    binding.inputStatusChevron.isChecked = false
                }
                .show()
        }
    }

    /**
     * 更新同步状态筛选
     */
    private fun updateSyncTypeFilter(state: BillState?) {
        syncType.clear()
        if (state != null) {
            syncType.add(state.name)
            if (state == BillState.Edited) syncType.add(BillState.Synced.name)
        } else {
            // 全部：包含三种状态
            syncType.add(BillState.Synced.name)
            syncType.add(BillState.Edited.name)
            syncType.add(BillState.Wait2Edit.name)
        }
    }

    /** 将年月格式化为 "YYYY-MM" */
    private fun formatMonthLabel(year: Int, month: Int): String {
        val mm = if (month < 10) "0$month" else "$month"
        return "$year-$mm"
    }

    /**
     * 删除账单
     * 抽取删除逻辑，消除重复代码
     */
    private fun deleteBill(
        item: org.ezbook.server.db.model.BillInfoModel,
        itemAdapter: net.ankio.auto.ui.adapter.BillItemAdapter
    ) {
        Logger.i("删除账单: ${item.id}")
        launch { BillAPI.remove(item.id); reloadSilently() }
    }

    /**
     * 显示删除确认对话框
     */
    private fun showDeleteConfirmDialog(onConfirm: () -> Unit) {
        BaseSheetDialog.create<BottomSheetDialogBuilder>(requireContext())
            .setTitleInt(R.string.delete_title)
            .setMessage(R.string.delete_bill_message)
            .setPositiveButton(R.string.confirm_delete_action) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> }
            .show()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.swipeRefreshLayout.isEnabled = true
        // The direct child is StatusPage, not the actual scrolling list.
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            binding.statusPage.contentView?.canScrollVertically(-1) == true
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                LedgerChanges.changes.drop(1).collect { reloadSilently() }
            }
        }

        // 顶部菜单事件监听
        binding.topAppBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.item_clear -> {
                    showClearDataDialog()
                    true
                }

                else -> false
            }
        }
        setUpSearch()
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun setUpSearch() {
        val searchItem = binding.topAppBar.menu.findItem(R.id.action_search) ?: return
        val searchView = searchItem.actionView as? MaterialSearchView ?: return

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                searchKeyword = newText ?: ""
                searchJob?.cancel()
                searchJob = launch {
                    kotlinx.coroutines.delay(300)
                    reloadSilently() // 实时触发静默刷新，避免闪烁
                }
                return true
            }
        })

        // 监听搜索框展开和收起
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                searchKeyword = ""
                searchJob?.cancel()
                reload()
                return true
            }
        })
    }

    // 抽取删除数据的逻辑
    private fun showClearDataDialog() {
        BaseSheetDialog.create<BottomSheetDialogBuilder>(requireContext())
            .setTitle(getString(R.string.delete_data))
            .setMessage(getString(R.string.delete_msg))
            .setPositiveButton(getString(R.string.sure_msg)) { _, _ ->
                Logger.i("清空账单数据")
                launch {
                    BillAPI.clear()
                    reload()
                }
            }
            .setNegativeButton(requireActivity().getString(R.string.cancel_msg)) { _, _ -> }
            .show()
    }
}