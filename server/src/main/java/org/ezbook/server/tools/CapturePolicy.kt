package org.ezbook.server.tools

object CapturePolicy {
    fun foregroundPackage(appWindows: List<Pair<String, Boolean>>, rootPackage: String?, ownPackage: String): String? {
        val selected = appWindows.firstOrNull { it.second }?.first ?: appWindows.firstOrNull()?.first ?: rootPackage
        return selected?.takeUnless { it.isBlank() || it == ownPackage || it in setOf("android", "com.android.systemui") }
    }
    /** Preserve all transaction digits and text; only presentation whitespace is normalized. */
    fun contentFingerprint(text: String): String = MD5HashTable.md5(text.replace(Regex("\\s+"), " ").trim())

    fun notificationEvent(key: String, postedAt: Long, title: String, text: String): String =
        "notice|$key|$postedAt|$title|$text"

    fun refundType(type: String, text: String): String =
        if (type == "Income" && Regex("退款|退货款|原路退回").containsMatchIn(text)) "IncomeRefund" else type

    fun refundCompleted(text: String, status: String): Boolean {
        if (status.uppercase() in setOf("PENDING", "PROCESSING", "FAILED", "CANCELLED")) return false
        val pending = Regex("退款(申请|处理中|中|失败|未到账|已受理|关闭)|等待退款|预计.{0,12}到账|退款将在")
        return !pending.containsMatchIn(text)
    }
}
