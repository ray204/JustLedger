package net.ankio.auto.service.ocr

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.ankio.auto.BuildConfig
import net.ankio.auto.http.LocalNetwork
import net.ankio.auto.storage.Logger
import org.ezbook.server.db.model.AppDataModel
import org.ezbook.server.tools.RecognizedOrderPages

/** Phone-only persistent hashes, separate from API settings and temporary caches. */
object RecognizedPageHistory {
    private val storage by lazy { MMKV.mmkvWithID("recognized_ocr_pages_v1", MMKV.MULTI_PROCESS_MODE) }
    private val history by lazy { RecognizedOrderPages({ storage.decodeBool(it, false) }, { storage.encode(it, true) }) }
    private val importLock = Mutex()

    fun shouldSkip(app: String, text: String, manual: Boolean = false) = history.shouldSkip(app, text, manual)
    fun remember(app: String, text: String) = history.remember(app, text)

    /** Upgrade previously successful OCR records too. An unavailable local service must not cause a rescan loop. */
    suspend fun importPreviousSuccesses(): Boolean = withContext(Dispatchers.IO) {
        importLock.withLock {
            if (storage.decodeBool("history_imported", false)) return@withLock true
            try {
                var page = 1
                var count = 0
                while (true) {
                    val response = LocalNetwork.get<List<AppDataModel>>("data/list?page=$page&limit=100&type=OCR&match=true").getOrThrow()
                    check(response.code == 200) { "OCR history unavailable" }
                    val rows = requireNotNull(response.data)
                    rows.filter { it.match && it.app.isNotBlank() && it.app != BuildConfig.APPLICATION_ID }
                        .forEach { remember(it.app, it.data); count++ }
                    if (rows.size < 100) break
                    page++
                }
                storage.encode("history_imported", true)
                Logger.i("OCR history imported: $count successful captures")
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                Logger.w("OCR history not ready; automatic capture skipped")
                false
            }
        }
    }
}
