package net.ankio.auto.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateFeed(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val endpoint: String = UpdatePolicy.ENDPOINT) {
    fun latest(localVersion: String): UpdateRelease? {
        try {
            val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "JustLedger/$localVersion").build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    403, 429 -> throw UpdateFailure("检查过于频繁，请稍后重试")
                    404 -> throw UpdateFailure("暂未找到正式版本，请稍后重试")
                }
                if (!response.isSuccessful) throw UpdateFailure("更新服务暂不可用，请稍后重试")
                val body = response.body ?: throw UpdateFailure("未收到更新信息，请重试")
                body.byteStream().use { input ->
                    val data = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (data.size() + n > 1024 * 1024) throw UpdateFailure("更新信息异常，请稍后重试")
                        data.write(buffer, 0, n)
                    }
                    return UpdatePolicy.parse(data.toString("UTF-8"), localVersion)
                }
            }
        } catch (e: UpdateFailure) { throw e }
        catch (_: java.io.IOException) { throw UpdateFailure("无法连接更新服务，请检查网络后重试") }
    }
}
