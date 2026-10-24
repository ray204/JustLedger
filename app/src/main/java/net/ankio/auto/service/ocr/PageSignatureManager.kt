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

package net.ankio.auto.service.ocr

import net.ankio.auto.utils.PrefManager
import org.json.JSONArray
import org.ezbook.server.tools.PageLearningPolicy

/**
 * 页面特征管理器
 *
 * 负责：存储、匹配、内容指纹生成。
 * 匹配逻辑：包名 + activity（空=任意）+ 指纹（空=不校验，否则相似度阈值）
 */
object PageSignatureManager {

    /**
     * 获取所有已记住的页面签名
     */
    fun getAll(): List<PageSignature> {
        val raw = PrefManager.pageSignatures
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { PageSignature.fromJson(it) }
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 添加页面签名
     */
    fun add(sig: PageSignature) {
        val existing = getAll()
        val updated = PageLearningPolicy.remember(existing.map { it.entry() }, sig.entry(), sig.never)
        save(updated.map { entry -> existing.firstOrNull { it.entry() == entry } ?: sig })
    }

    /**
     * 移除指定 key 的签名
     */
    fun remove(key: String) {
        val list = getAll().filter { it.key() != key }.toMutableList()
        save(list)
    }

    /**
     * 匹配条件：包名 + activity + 结构指纹
     * 签名中 structureFingerprint 为空时退化为 pkg+activity 匹配（兼容旧数据）
     */
    fun matches(
        packageName: String,
        activityName: String,
        structureFingerprint: String = "",
    ): Boolean = PageLearningPolicy.automatic(getAll().map { it.entry() },
        PageLearningPolicy.Entry(packageName, activityName, structureFingerprint))

    fun shouldAsk(packageName: String, activityName: String, structureFingerprint: String): Boolean =
        PageLearningPolicy.shouldAsk(getAll().map { it.entry() },
            PageLearningPolicy.Entry(packageName, activityName, structureFingerprint))

    private fun PageSignature.entry() = PageLearningPolicy.Entry(packageName, activityName, structureFingerprint, never)


    private fun save(list: List<PageSignature>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        PrefManager.pageSignatures = arr.toString()
    }
}
