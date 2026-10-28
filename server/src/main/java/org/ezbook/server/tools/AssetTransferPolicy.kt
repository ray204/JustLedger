package org.ezbook.server.tools

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Ignore principal moving between the user's assets, not genuine separately paid investment income. */
object AssetTransferPolicy {
    const val MESSAGE = "这是本人资产划转，不计为收入或支出"
    const val AI_INSTRUCTION = "理财/基金赎回、到期本金回款、申购及本人账户间资产划转仅返回{\"pageType\":\"other\"}；本金不是收入，含本金与收益的到账总额不能当收益。只有明确单独发放的利息、分红或收益才记Income。"

    fun isInternalMovement(raw: String): Boolean {
        fun strings(value: JsonElement): List<String> = when {
            value.isJsonObject -> value.asJsonObject.entrySet().filter { it.key !in setOf("eventId", "key", "app", "packageName") }.flatMap { strings(it.value) }
            value.isJsonArray -> value.asJsonArray.flatMap(::strings)
            value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value.asString)
            else -> emptyList()
        }
        val text = runCatching { strings(JsonParser.parseString(raw)).joinToString("\n") }.getOrDefault(raw).ifBlank { raw }
        val financial = Regex("理财|基金|余额宝|零钱通|周周宝|固收|持有期|存款|定期|本金").containsMatchIn(text)
        val redemption = Regex("赎回|到期兑付|本金返还|本金回款|到期回款|到期转存").containsMatchIn(text)
        val ownRedemption = Regex("(?:账户|账号|卡片|银行卡).{0,12}赎回").containsMatchIn(text)
        val principal = Regex("本金|本息|赎回款|赎回金额|到账总额|资金.{0,25}(?:划入|转入|到账)").containsMatchIn(text)
        val separateProfit = Regex("(?:收益|利息|分红)(?:已)?(?:入账|到账|派发|发放|结算)").containsMatchIn(text)
        if (separateProfit && !principal &&
            !Regex("赎回.{0,8}成功|到期兑付|(?:到账|赎回|实收|回款)(?:金额|总额)").containsMatchIn(text)) return false
        val movement = Regex("赎回.{0,30}(?:成功|到账|入账|划入|转入)|到期兑付|本金(?:返还|回款)|资金.{0,25}(?:划入|转入|到账)|(?:本金|本息).{0,25}(?:到账|划入|转入)").containsMatchIn(text)
        if (redemption && (financial || ownRedemption) && movement) return true
        return financial && Regex("(?:申购|认购)(?:成功|扣款)|(?:余额宝|零钱通)(?:资金)?转出(?:至|到)(?:余额|零钱|本人|绑定银行卡)|(?:余额|零钱|本人账户)转入(?:余额宝|零钱通)").containsMatchIn(text)
    }
}
