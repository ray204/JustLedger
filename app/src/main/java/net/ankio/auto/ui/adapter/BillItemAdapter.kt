/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
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

package net.ankio.auto.ui.adapter

import net.ankio.auto.databinding.AdapterOrderItemBinding
import net.ankio.auto.ui.api.BaseAdapter
import net.ankio.auto.ui.api.BaseViewHolder
import net.ankio.auto.ui.utils.CategoryIcons
import net.ankio.auto.utils.BillTool
import net.ankio.auto.utils.PaymentChannel
import net.ankio.auto.utils.PrefManager
import org.ezbook.server.constant.BillState
import org.ezbook.server.db.model.BillInfoModel

class BillItemAdapter(private val showMore: Boolean = true) : BaseAdapter<AdapterOrderItemBinding, BillInfoModel>() {
    private var onItemClickListener: ((BillInfoModel, Int) -> Unit)? = null
    private var onItemLongClickListener: ((BillInfoModel, Int) -> Unit)? = null
    private var onMoreClickListener: ((BillInfoModel) -> Unit)? = null
    fun setOnItemClickListener(listener: (BillInfoModel, Int) -> Unit) { onItemClickListener = listener }
    fun setOnItemLongClickListener(listener: (BillInfoModel, Int) -> Unit) { onItemLongClickListener = listener }
    fun setOnMoreClickListener(listener: (BillInfoModel) -> Unit) { onMoreClickListener = listener }
    override fun onInitViewHolder(holder: BaseViewHolder<AdapterOrderItemBinding, BillInfoModel>) {
        holder.binding.root.setOnClickListener { holder.item?.let { onItemClickListener?.invoke(it, indexOf(it)) } }
        holder.binding.root.setOnLongClickListener {
            holder.item?.let { onItemLongClickListener?.invoke(it, indexOf(it)) }; true
        }
    }
    override fun onBindViewHolder(holder: BaseViewHolder<AdapterOrderItemBinding, BillInfoModel>, data: BillInfoModel, position: Int) {
        val b = holder.binding
        b.merchant.text = data.shopName.ifBlank { data.shopItem }.ifBlank { data.cateName }.ifBlank { "未分类" }
        val channel = PaymentChannel.name(data)
        b.channel.text = when { data.state == BillState.Wait2Edit -> "待核对"; data.hasFlag(BillInfoModel.FLAG_NOT_COUNT) -> "不计收支"; else -> if (org.ezbook.server.tools.OccasionalPolicy.isOccasional(data)) "偶发 · $channel" else channel }
        BillTool.setTextViewPrice(data.money, data.type, b.money, data.currencyCode().takeIf { it != PrefManager.baseCurrency })
        b.categoryIcon.setImageResource(CategoryIcons.drawable(data.cateName))
        b.root.contentDescription = "${b.merchant.text}，${data.cateName}，${b.channel.text}，${b.money.text}，点击编辑，长按删除"
    }
    override fun areItemsSame(oldItem: BillInfoModel, newItem: BillInfoModel) = oldItem.id == newItem.id
    override fun areContentsSame(oldItem: BillInfoModel, newItem: BillInfoModel) = oldItem == newItem
}
