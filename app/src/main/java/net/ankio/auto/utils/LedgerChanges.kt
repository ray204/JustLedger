package net.ankio.auto.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Refresh visible ledger screens only after the local transaction has committed. */
object LedgerChanges {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun notifyCommitted() { revision.update { it + 1 } }
}
