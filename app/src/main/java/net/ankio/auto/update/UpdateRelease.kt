package net.ankio.auto.update

import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class UpdateFailure(message: String) : IOException(message)

data class UpdateRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)

/** Only our stable, arm64 release asset is eligible for an in-place update. */
object UpdatePolicy {
    const val REPOSITORY = "https://github.com/ray204/JustLedger"
    const val ENDPOINT = "https://api.github.com/repos/ray204/JustLedger/releases/latest"
    const val MAX_APK_SIZE = 512L * 1024 * 1024
    private val stable = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")

    fun autoCheckDue(enabled: Boolean, last: Long, now: Long): Boolean =
        enabled && (last <= 0 || now < last || now - last >= 24 * 60 * 60 * 1000L)

    fun versionParts(value: String): List<Long>? {
        val match = stable.matchEntire(value) ?: return null
        return match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = versionParts(remote) ?: return false
        val l = versionParts(local.substringBefore('-')) ?: return false
        for (i in r.indices) if (r[i] != l[i]) return r[i] > l[i]
        return local.contains('-')
    }

    fun validate(release: UpdateRelease): UpdateRelease {
        if (versionParts(release.version) == null ||
            release.url != "$REPOSITORY/releases/download/v${release.version}/JustLedger-${release.version}-arm64.apk" ||
            release.size !in 1..MAX_APK_SIZE || !Regex("[a-f0-9]{64}").matches(release.sha256)) {
            throw UpdateFailure("更新信息不完整，请稍后重试")
        }
        return release
    }

    fun parse(json: String, localVersion: String): UpdateRelease? {
        try {
            val root = JsonParser.parseString(json).asJsonObject
            if (root["draft"].asBoolean || root["prerelease"].asBoolean) return null
            val tag = root["tag_name"].asString
            if (!tag.startsWith('v') || versionParts(tag.drop(1)) == null) return null
            val version = tag.drop(1)
            if (!isNewer(version, localVersion)) return null
            val expected = "JustLedger-$version-arm64.apk"
            val assets = root.getAsJsonArray("assets").map { it.asJsonObject }.filter { it["name"].asString == expected }
            if (assets.size != 1 || assets.single()["state"].asString != "uploaded") throw UpdateFailure("新版安装包尚未就绪，请稍后重试")
            val asset = assets.single()
            val digest = asset["digest"]?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (!digest.startsWith("sha256:")) throw UpdateFailure("更新缺少校验信息，请稍后重试")
            return validate(UpdateRelease(version, root["body"]?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(12000),
                asset["browser_download_url"].asString, asset["size"].asLong, digest.removePrefix("sha256:")))
        } catch (e: UpdateFailure) { throw e }
        catch (_: Exception) { throw UpdateFailure("无法读取更新信息，请稍后重试") }
    }

    /** Stage inside private cache; never pass an unverified or partial download to the installer. */
    fun verifiedCopy(input: InputStream, output: File, release: UpdateRelease): File {
        validate(release)
        val partial = File(output.path + ".part")
        output.parentFile?.mkdirs()
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            partial.outputStream().use { sink ->
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > release.size) throw UpdateFailure("安装包大小不符，请重新下载")
                        hash.update(buffer, 0, n); sink.write(buffer, 0, n)
                    }
                }
            }
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            if (count != release.size || digest != release.sha256) throw UpdateFailure("安装包校验失败，请重新下载")
            if (output.exists() && !output.delete()) throw UpdateFailure("无法保存安装包，请重试")
            if (!partial.renameTo(output)) throw UpdateFailure("无法保存安装包，请重试")
            return output
        } finally { partial.delete() }
    }

    fun validateIdentity(packageName: String, versionName: String?, versionCode: Long, signers: Set<String>,
                         expectedPackage: String, installedCode: Long, installedSigners: Set<String>, release: UpdateRelease) {
        if (packageName != expectedPackage || versionName != release.version) throw UpdateFailure("安装包与当前应用不匹配")
        if (versionCode <= installedCode) throw UpdateFailure("此版本无法覆盖更新，请重新检查更新")
        if (signers.isEmpty() || signers != installedSigners) throw UpdateFailure("安装包签名不一致，无法覆盖更新")
    }
}
