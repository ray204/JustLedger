package org.ezbook.server.ai.tools

import org.ezbook.server.db.model.BillInfoModel

object ReceiptRecognition {
    /** At most three requests; include bounded prior output and the actual error when retrying. */
    suspend fun recognize(user: String, requireReceiptPage: Boolean = false, request: suspend (String) -> String): BillInfoModel? {
        var correction = ""
        repeat(3) { attempt ->
            var response = ""
            try {
                response = request(user + correction)
                return ReceiptResponseParser.parse(response, requireReceiptPage)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == 2) throw IllegalArgumentException("账单格式仍无法解析，请手动核对", e)
                fun safe(value:String,limit:Int) = value.replace(Regex("(?i)sk-[a-z0-9_-]+|Bearer\\s+\\S+"), "[已隐藏]").take(limit)
                correction = "\n上次模型输出：${safe(response,3000).ifBlank { "未返回内容" }}" +
                    "\n${if(response.isEmpty()) "模型调用错误" else "解析错误"}：${safe(e.message ?: e.javaClass.simpleName,200)}" +
                    "\n请纠正，只返回一个合法JSON对象。"
            }
        }
        return null
    }
}
