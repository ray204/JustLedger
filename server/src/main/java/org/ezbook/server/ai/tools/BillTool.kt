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

package org.ezbook.server.ai.tools

import org.ezbook.server.ai.AiManager
import org.ezbook.server.db.Db
import org.ezbook.server.db.model.BillInfoModel
import org.ezbook.server.constant.DataType
import org.ezbook.server.constant.DefaultData
import org.ezbook.server.tools.SettingUtils
import org.ezbook.server.tools.runCatchingExceptCancel

class BillTool {
    suspend fun execute(data: String, app: String, dataType: DataType, image: String = "", pageContext: String = ""): Result<BillInfoModel?> = runCatchingExceptCancel {
        require(data.length <= 12000) { "页面文字过多，请只识别单笔交易" }
        val prompt = SettingUtils.aiBillRecognitionPrompt().ifBlank { DefaultData.AI_BILL_RECOGNITION_PROMPT } +
            if (dataType == DataType.OCR) "\n" + org.ezbook.server.tools.OcrPagePolicy.AI_INSTRUCTION else ""
        val book = org.ezbook.server.server.resolveBookByNameOrDefault(SettingUtils.bookName())
        org.ezbook.server.tools.LocalCategoryDefaults.ensure(book.remoteId)
        val categories = Db.get().categoryDao().all().filter { it.remoteBookId == book.remoteId }
            .mapNotNull { it.name }.distinct().take(40).joinToString(",").take(240)
        val user = "来源:$app\n类型:$dataType\n分类:$categories\n页面上下文:${pageContext.take(4000).ifBlank { "未提供" }}\n原文:\n$data"
        ReceiptRecognition.recognize(user, requireReceiptPage = dataType == DataType.OCR) { input ->
            AiManager.getInstance().request(prompt, input, image = image).getOrThrow()
        }
    }
}
