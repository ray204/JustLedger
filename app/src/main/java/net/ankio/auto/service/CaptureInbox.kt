package net.ankio.auto.service

import android.content.Context
import android.util.AtomicFile
import androidx.work.*
import com.google.gson.Gson
import net.ankio.auto.http.api.JsAPI
import org.ezbook.server.constant.DataType
import org.ezbook.server.tools.MD5HashTable
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persist captured notifications/SMS before acknowledging the Android callback. */
object CaptureInbox {
    data class Event(val app: String, val type: String, val data: String)
    private fun directory(context: Context) = File(context.filesDir, "capture_inbox").apply { mkdirs() }

    @Synchronized
    fun enqueue(context: Context, type: DataType, data: String, app: String) {
        val content = Gson().toJson(Event(app, type.name, data))
        val file = File(directory(context), "${MD5HashTable.md5(content)}.json")
        if (!file.exists()) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(content.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
        schedule(context, file.name)
    }

    fun recover(context: Context) {
        directory(context).listFiles()?.filter { it.extension == "json" }?.forEach { schedule(context, it.name) }
    }

    private fun schedule(context: Context, name: String) {
        val work = OneTimeWorkRequestBuilder<CaptureWorker>()
            .setInputData(workDataOf("file" to name))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("capture-$name", ExistingWorkPolicy.KEEP, work)
    }

    class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val name = inputData.getString("file") ?: return@withContext Result.failure()
            if (!name.matches(Regex("[a-f0-9]{32}\\.json"))) return@withContext Result.failure()
            val file = File(directory(applicationContext), name)
            if (!file.exists()) return@withContext Result.success()
            try {
                val event = Gson().fromJson(file.readText(), Event::class.java)
                val result = JsAPI.analysis(DataType.valueOf(event.type), event.data, event.app)
                // 404 means received and stored but no applicable rule/completed transaction.
                if (result.code == 200 || result.code == 404) {
                    file.delete()
                    Result.success()
                } else Result.retry()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { Result.retry() }
        }
    }
}
