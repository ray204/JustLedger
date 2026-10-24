package org.ezbook.server.tools

import java.security.MessageDigest

/** No ledger decisions: this history only silences automatic screen recognition of known pages. */
class RecognizedOrderPages(private val contains: (String) -> Boolean, private val persist: (String) -> Unit) {
    fun shouldSkip(app: String, text: String, manual: Boolean = false): Boolean =
        !manual && keys(app, text).any(contains)

    fun remember(app: String, text: String) { keys(app, text).forEach(persist) }

    companion object {
        private val labelledId = Regex("(商户(?:订单号|单号)|订单(?:编号|号码|号)|交易(?:单号|编号|号))\\s*[:：]?\\s*([0-9]{3,6}(?:[ \\u00a0]+[0-9]{3,6}){1,7}|[A-Za-z0-9][A-Za-z0-9_-]{7,63})(?![A-Za-z0-9_-]|[ \\u00a0]+[0-9])")

        /** IDs must be labelled and unambiguous. Never substitute amount + merchant for an ID. */
        fun keys(app: String, text: String): Set<String> {
            if (app.isBlank() || text.isBlank()) return emptySet()
            fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val keys = linkedSetOf(hash("page|$app|${text.replace(Regex("\\s+"), " ").trim()}"))
            val ids = labelledId.findAll(text).mapNotNull { match ->
                val label = match.groupValues[1]
                val kind = when { label.startsWith("交易") -> "transaction"; label.startsWith("商户") -> "merchant"; else -> "order" }
                val id = match.groupValues[2].replace(Regex("[ \\u00a0]+"), "")
                if (id.length !in 8..64) null else kind to id
            }.toList().groupBy({ it.first }, { it.second })
            if (ids.values.any { it.distinct().size > 1 }) return keys
            ids.forEach { (kind, values) -> keys.add(hash("id|$app|$kind|${values.first()}")) }
            return keys
        }
    }
}
