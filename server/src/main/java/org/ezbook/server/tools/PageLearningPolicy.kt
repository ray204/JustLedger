package org.ezbook.server.tools

/** Persistent choices are scoped to a page type; refusing learning never blocks an explicit scan. */
object PageLearningPolicy {
    data class Entry(val app: String, val activity: String, val structure: String, val never: Boolean = false)
    private fun matches(saved: Entry, page: Entry) = saved.app == page.app && saved.activity == page.activity &&
        (saved.structure.isBlank() || saved.structure == page.structure)
    fun shouldAsk(saved: List<Entry>, page: Entry) = page.app.isNotBlank() && page.structure.isNotBlank() &&
        saved.none { matches(it, page) }
    fun automatic(saved: List<Entry>, page: Entry) = saved.none { it.never && matches(it, page) } &&
        saved.any { !it.never && matches(it, page) }
    fun remember(saved: List<Entry>, page: Entry, never: Boolean): List<Entry> =
        saved.filterNot { it.app == page.app && it.activity == page.activity && it.structure == page.structure } + page.copy(never = never)
}
