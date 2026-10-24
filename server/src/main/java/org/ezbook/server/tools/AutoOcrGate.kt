package org.ezbook.server.tools

/** Session-scoped attempt history: success and failure both consume an automatic attempt.
 * A deliberate manual scan can retry. No timer may turn a banner dismissal into a new attempt.
 */
class AutoOcrGate(private val capacity: Int = 128) {
    init { require(capacity > 0) }
    private val attempted = LinkedHashSet<String>()
    private val presenters = mutableSetOf<Any>()
    private var running = false

    @Synchronized fun isBlocked(): Boolean = running || presenters.isNotEmpty()

    @Synchronized fun setPresenting(owner: Any, visible: Boolean) {
        if (visible) presenters.add(owner) else presenters.remove(owner)
    }

    @Synchronized fun canAutomaticallyStart(key: String): Boolean =
        key.isNotBlank() && !isBlocked() && key !in attempted

    @Synchronized fun begin(key: String?, manual: Boolean, expectedKey: String? = null): Boolean {
        if (isBlocked() || expectedKey != null && expectedKey != key) return false
        if (!manual && (key.isNullOrBlank() || key in attempted)) return false
        if (!key.isNullOrBlank()) {
            attempted.remove(key)
            attempted.add(key)
            while (attempted.size > capacity) attempted.remove(attempted.first())
        }
        running = true
        return true
    }

    @Synchronized fun finish() { running = false }

    companion object {
        fun isExternalSource(eventPackage: String?, rootPackage: String?, ownPackage: String): Boolean =
            !eventPackage.isNullOrBlank() && eventPackage != ownPackage && eventPackage == rootPackage
    }
}
