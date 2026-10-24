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

package net.ankio.auto.ui.dialog

import android.view.View
import kotlinx.coroutines.flow.MutableSharedFlow
import net.ankio.auto.databinding.FloatEditorRefactoredBinding
import net.ankio.auto.storage.Logger
import net.ankio.auto.ui.api.BaseSheetDialog
import net.ankio.auto.ui.api.bindAs
import net.ankio.auto.ui.dialog.components.ActionButtonsComponent
import net.ankio.auto.ui.dialog.components.AmountDisplayComponent
import net.ankio.auto.ui.dialog.components.BasicInfoComponent
import net.ankio.auto.ui.dialog.components.BookHeaderComponent
import net.ankio.auto.ui.dialog.components.PaymentInfoComponent
import net.ankio.auto.ui.dialog.components.RuleInfoComponent
import net.ankio.auto.ui.dialog.components.TransactionTypeSelectorComponent
import net.ankio.auto.utils.BillTool
import org.ezbook.server.constant.BillState
import org.ezbook.server.constant.BillType
import org.ezbook.server.db.model.BillInfoModel


/**
 * 账单编辑对话框 - 组件协调器模式
 *
 * 职责：
 * - 协调各个UI组件的初始化和数据流
 * - 处理组件间的通信和状态同步
 * - 管理对话框的生命周期和回调
 *
 * 设计原则：
 * 1. 单一职责：只做组件协调，不处理具体UI逻辑
 * 2. 数据流清晰：统一的初始化和刷新机制
 * 3. 无特殊情况：用配置驱动，而非条件分支
 *
 * 使用方式：
 * ```kotlin
 * BillEditorDialog.create(activity)
 *     .setBillInfo(billInfo)
 *     .setOnConfirm { bill -> processBill(bill) }
 *     .setOnCancel { bill -> handleCancel(bill) }
 *     .show()
 * ```
 */
class BillEditorDialog internal constructor(
    context: android.content.Context
) : BaseSheetDialog<FloatEditorRefactoredBinding>(context) {

    private var billInfoModel: BillInfoModel? = null
    private var onCancelClick: ((billInfoModel: BillInfoModel) -> Unit)? = null
    private var onConfirmClick: ((billInfoModel: BillInfoModel) -> Unit)? = null

    // 核心状态
    private var saving = false
    private var currentBillType = BillType.Expend

    // 组件实例 - 避免重复创建，支持直接刷新
    private lateinit var bookHeaderComponent: BookHeaderComponent
    private lateinit var amountDisplayComponent: AmountDisplayComponent
    private lateinit var ruleInfoComponent: RuleInfoComponent
    private lateinit var transactionTypeSelectorComponent: TransactionTypeSelectorComponent
    private lateinit var paymentInfoComponent: PaymentInfoComponent
    private lateinit var basicInfoComponent: BasicInfoComponent
    private lateinit var actionButtonsComponent: ActionButtonsComponent

    /**
     * 设置账单信息
     * @param billInfo 账单信息模型
     * @return 当前对话框实例，支持链式调用
     */
    fun setBillInfo(billInfo: BillInfoModel) = apply {
        this.billInfoModel = billInfo // 直接引用，确保父账单编辑后能同步更新
        this.currentBillType = BillTool.getType(billInfo.type)

        // 统一刷新所有组件
        if (uiReady()) refreshAllComponents()
    }


    /**
     * 链式配置方法 - 参考BookHeaderComponent的简洁设计
     */
    fun setOnCancel(callback: (billInfoModel: BillInfoModel) -> Unit) = apply {
        this.onCancelClick = callback
    }

    fun setOnConfirm(callback: (billInfoModel: BillInfoModel) -> Unit) = apply {
        this.onConfirmClick = callback
    }

    override fun onViewCreated(view: View?) {
        super.onViewCreated(view)

        // 基础设置
        val parent = view?.parent as? View ?: return
        parent.setPadding(0, 0, 0, 0)

        // 创建组件实例（只创建一次）
        createAllComponents()

        // 初始化组件数据
        refreshAllComponents()

        // 设置全局刷新监听 - 直接调用各组件的刷新方法
        launch {
            refreshEvent.collect {
                Logger.d("refresh all components")
                refreshAllComponents()
            }
        }
        Logger.d("账单编辑器初始化")
    }

    /**
     * 创建所有组件实例 - 只在对话框创建时执行一次
     */
    private fun createAllComponents() {
        // 使用 bindAs() 创建组件实例并保存为属性
        bookHeaderComponent = binding.bookHeader.bindAs()
        billInfoModel?.let { bookHeaderComponent.setBillInfo(it) }

        amountDisplayComponent = binding.amountDisplay.bindAs()
        billInfoModel?.let { amountDisplayComponent.setBillInfo(it) }

        ruleInfoComponent = binding.ruleInfo.bindAs()
        billInfoModel?.let { ruleInfoComponent.setBillInfo(it) }

        transactionTypeSelectorComponent = binding.transactionTypeSelector.bindAs()
        billInfoModel?.let { transactionTypeSelectorComponent.setBillInfo(it) }

        paymentInfoComponent = binding.paymentInfo.bindAs()
        billInfoModel?.let { paymentInfoComponent.setBillInfo(it) }

        basicInfoComponent = binding.basicInfo.bindAs()
        billInfoModel?.let { basicInfoComponent.setBillInfo(it) }

        actionButtonsComponent = binding.actionButtons.bindAs()
        billInfoModel?.let { actionButtonsComponent.setBillInfo(it) }
        // 设置操作按钮事件（只需要设置一次）
        setupActionButtonEvents()
    }

    /**
     * 刷新所有组件 - 确保数据一致性
     */
    private fun refreshAllComponents() {
        val reason = billInfoModel?.takeIf { it.state == BillState.Wait2Edit }?.reviewReason.orEmpty()
        binding.reviewCard.visibility = if (reason.isBlank()) View.GONE else View.VISIBLE
        binding.reviewReason.text = "核对账单"
        binding.reviewHint.text = "检查后保存"
        binding.reviewReason.setOnClickListener {
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("核对说明").setMessage(reason).setPositiveButton("知道了", null).create()
            dialog.window?.setType(window?.attributes?.type ?: android.view.WindowManager.LayoutParams.TYPE_APPLICATION)
            dialog.show()
        }
        binding.enrichExisting.visibility = View.GONE
        loadPossibleBills()
        val details = org.ezbook.server.tools.ReceiptDetails.lines(billInfoModel?.detailsJson.orEmpty())
        binding.receiptDetails.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE
        binding.receiptDetails.setOnClickListener {
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle("订单信息").setMessage(details.joinToString("\n\n")).setPositiveButton("关闭",null).create()
            dialog.window?.setType(window?.attributes?.type ?: android.view.WindowManager.LayoutParams.TYPE_APPLICATION)
            dialog.show()
        }

        val billInfo = billInfoModel ?: return
        Logger.d("刷新所有组件，账单ID：${billInfo.id}")
        
        // 更新基础状态
        currentBillType = BillTool.getType(billInfo.type)

        // 统一更新所有组件 - 消除组件间的数据不一致
        if (::bookHeaderComponent.isInitialized) bookHeaderComponent.setBillInfo(billInfo)
        if (::amountDisplayComponent.isInitialized) amountDisplayComponent.setBillInfo(billInfo)
        if (::ruleInfoComponent.isInitialized) ruleInfoComponent.setBillInfo(billInfo)
        if (::transactionTypeSelectorComponent.isInitialized) transactionTypeSelectorComponent.setBillInfo(
            billInfo
        )
        if (::paymentInfoComponent.isInitialized) paymentInfoComponent.setBillInfo(billInfo)
        if (::basicInfoComponent.isInitialized) basicInfoComponent.setBillInfo(billInfo)
        if (::actionButtonsComponent.isInitialized) actionButtonsComponent.setBillInfo(billInfo)
        // Rule management belongs in settings; keep this sheet focused on the transaction.
        binding.ruleInfo.root.visibility = View.GONE
        binding.actionButtons.confirmButton.text = if (binding.possibleBills.visibility == View.VISIBLE) "另存新账单" else "保存"
    }


    /**
     * 设置操作按钮事件 - 使用组件属性
     */
    private fun setupActionButtonEvents() {
        actionButtonsComponent.setOnCancelClickListener {
            val billInfo = billInfoModel ?: return@setOnCancelClickListener
            dismiss()
            onCancelClick?.invoke(billInfo)
        }
        actionButtonsComponent.setOnConfirmClickListener {
            saveBill()
        }
    }


    /**
     * 保存账单 - 简化的保存逻辑
     */
    private fun saveBill() {
        val billInfo = billInfoModel ?: return
        if (saving) return
        saving = true
        BillTool.saveBill(billInfo, onSaved = {
            onConfirmClick?.invoke(billInfo)
            dismiss()
        }, onFailure = { saving = false })
    }

    private var candidateRequest = 0

    private fun loadPossibleBills() {
        val bill = billInfoModel ?: return
        val request = ++candidateRequest
        binding.possibleBills.removeAllViews()
        binding.possibleBills.visibility = View.GONE
        if (bill.id <= 0 || bill.groupId != -1L || bill.state != BillState.Wait2Edit) return
        launch {
            try {
                val candidates = net.ankio.auto.http.api.BillAPI.enrichmentCandidates(bill.id)
                if (request != candidateRequest || !uiReady()) return@launch
                // A warning about duplicates is only shown when actual candidates exist.
                binding.reviewReason.text = if (candidates.isEmpty()) "核对账单" else "可能已有记录"
                binding.reviewHint.text = if (candidates.isEmpty()) "检查后保存" else "同一笔？点选原记录补充"
                binding.possibleBills.visibility = if (candidates.isEmpty()) View.GONE else View.VISIBLE
                binding.actionButtons.confirmButton.text = if (candidates.isEmpty()) "保存" else "另存新账单"
                val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                candidates.forEach { target ->
                    val button = com.google.android.material.button.MaterialButton(context, null,
                        android.R.attr.borderlessButtonStyle)
                    val subtitle = listOf(format.format(java.util.Date(target.time)),
                        target.channel.ifBlank { target.accountNameFrom }).filter { it.isNotBlank() }.joinToString(" · ")
                    button.text = "${target.shopName.ifBlank { target.channel }.take(18)}  ¥${BillTool.formatAmount(target.money)}  ›\n$subtitle"
                    button.textSize = 12f
                    button.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    button.isAllCaps = false
                    button.maxLines = 2
                    button.setOnClickListener { confirmExistingBill(target) }
                    binding.possibleBills.addView(button, android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, (64 * context.resources.displayMetrics.density).toInt()))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Logger.e("Cannot load possible bills", e) }
        }
    }

    private fun confirmExistingBill(target: BillInfoModel) {
        val id = billInfoModel?.id ?: return
        if (saving) return
        launch {
            try {
                val source = requireNotNull(net.ankio.auto.http.api.BillAPI.get(id))
                val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                    .setTitle("补充到这笔账单？")
                    .setMessage("${target.shopName} · ¥${BillTool.formatAmount(target.money)}\n保留已确认金额，只补充缺失信息。")
                    .setNegativeButton("取消", null).setPositiveButton("确认补充") { _, _ ->
                        if (!saving) {
                            saving = true
                            launch {
                                try {
                                    val updated = net.ankio.auto.http.api.BillAPI.enrich(source, target)
                                    net.ankio.auto.utils.LedgerChanges.notifyCommitted()
                                    net.ankio.auto.service.receipt.BillReceiptManager.refresh()
                                    onConfirmClick?.invoke(updated)
                                    dismiss()
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (e: Exception) { net.ankio.auto.ui.utils.ToastUtils.error(e.message ?: "补充失败") }
                                finally { saving = false }
                            }
                        }
                    }.create()
                dialog.window?.setType(window?.attributes?.type ?: android.view.WindowManager.LayoutParams.TYPE_APPLICATION)
                dialog.show()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { net.ankio.auto.ui.utils.ToastUtils.error(e.message ?: "读取失败") }
        }
    }


    /**
     * 软键盘显示时滚动到底部
     */
    override fun onImeVisible() {
        // 提前获取 ScrollView 引用，避免异步执行时 binding 已被置空导致崩溃
        val scroll = runCatching { binding.scrollView }.getOrNull() ?: return
        scroll.post {
            if (scroll.isAttachedToWindow) {
                scroll.fullScroll(View.FOCUS_DOWN)
            }
        }
    }


    companion object {
        // 组件刷新事件流 - 简单直接
        val refreshEvent = MutableSharedFlow<Boolean>(replay = 1)


        fun notifyRefresh() {
            refreshEvent.tryEmit(true)
        }

    }
}
