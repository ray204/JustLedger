package net.ankio.auto.update

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class UpdatePolicyTest {
    private val bytes = "synthetic APK bytes, identity checked separately".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun release() = UpdateRelease("0.1.3", "新增检查更新", "${UpdatePolicy.REPOSITORY}/releases/download/v0.1.3/JustLedger-0.1.3-arm64.apk", bytes.size.toLong(), hash)
    private fun json() = """{"tag_name":"v0.1.3","draft":false,"prerelease":false,"body":"新增检查更新","assets":[{"name":"JustLedger-0.1.3-arm64.apk","state":"uploaded","size":${bytes.size},"digest":"sha256:$hash","browser_download_url":"${release().url}"}]}"""
    private fun rejects(block: () -> Unit) { assertThrows(UpdateFailure::class.java, block) }

    @Test fun automaticChecksRespectSwitchDayBoundaryAndClockRollback() {
        val day = 24 * 60 * 60 * 1000L
        assertFalse(UpdatePolicy.autoCheckDue(false, 0, day))
        assertTrue(UpdatePolicy.autoCheckDue(true, 0, day))
        assertFalse(UpdatePolicy.autoCheckDue(true, day, day))
        assertFalse(UpdatePolicy.autoCheckDue(true, day, 2 * day - 1))
        assertTrue(UpdatePolicy.autoCheckDue(true, day, 2 * day))
        assertTrue(UpdatePolicy.autoCheckDue(true, day, day - 1))
    }
    @Test fun stableVersionsCompareNumericallyAndNeverDowngrade() {
        assertTrue(UpdatePolicy.isNewer("0.1.10", "0.1.9"))
        assertTrue(UpdatePolicy.isNewer("1.0.0", "0.99.99"))
        assertTrue(UpdatePolicy.isNewer("0.1.3", "0.1.3-preview.2"))
        assertFalse(UpdatePolicy.isNewer("0.1.3", "0.1.3"))
        assertFalse(UpdatePolicy.isNewer("0.1.2", "0.1.3"))
        for (bad in listOf("1.2", "1.2.3-preview", "01.2.3", "-1.2.3", "99999999999999999999999.1.2", "../1.2.3")) {
            assertNull(UpdatePolicy.versionParts(bad)); assertFalse(UpdatePolicy.isNewer(bad, "0.1.0"))
        }
        assertFalse(UpdatePolicy.isNewer("0.1.3", "broken"))
    }
    @Test fun feedAcceptsOnlyNewStableOwnRepositoryApkWithIntegrityMetadata() {
        assertEquals(release(), UpdatePolicy.parse(json(), "0.1.2"))
        assertNull(UpdatePolicy.parse(json(), "0.1.3"))
        assertNull(UpdatePolicy.parse(json(), "0.2.0"))
        assertNull(UpdatePolicy.parse(json().replace("\"draft\":false", "\"draft\":true"), "0.1.2"))
        assertNull(UpdatePolicy.parse(json().replace("\"prerelease\":false", "\"prerelease\":true"), "0.1.2"))
        assertNull(UpdatePolicy.parse(json().replace("v0.1.3", "v0.1.3-preview.1"), "0.1.2"))
        for (url in listOf("http://github.com/ray204/JustLedger/releases/download/v0.1.3/JustLedger-0.1.3-arm64.apk",
            release().url.replace("ray204", "AutoAccountingOrg"), release().url + "?redirect=evil", release().url.replace("github.com", "github.com.evil.example"))) {
            rejects { UpdatePolicy.parse(json().replace(release().url, url), "0.1.2") }
        }
        for (body in listOf("{}", "not json", json().replace("sha256:$hash", ""), json().replace("uploaded", "starter"), json().replace("arm64.apk", "x86.apk"))) {
            rejects { UpdatePolicy.parse(body, "0.1.2") }
        }
        val root = JsonParser.parseString(json()).asJsonObject
        root.getAsJsonArray("assets").add(root.getAsJsonArray("assets")[0].deepCopy())
        rejects { UpdatePolicy.parse(root.toString(), "0.1.2") }
        for (size in listOf(0L, -1L, UpdatePolicy.MAX_APK_SIZE + 1)) rejects { UpdatePolicy.validate(release().copy(size = size)) }
    }
    @Test fun verifiedCopyRejectsCorruptTruncatedOversizeAndInterruptedDownloadsWithoutLeavingPartials() {
        val dir = Files.createTempDirectory("jl-update-test").toFile()
        val out = File(dir, "update.apk")
        try {
            assertEquals(out, UpdatePolicy.verifiedCopy(ByteArrayInputStream(bytes), out, release()))
            assertArrayEquals(bytes, out.readBytes()); out.delete()
            for (bad in listOf(bytes.copyOf(bytes.size - 1), bytes + 1, bytes.clone().apply { this[0] = (this[0] + 1).toByte() })) {
                rejects { UpdatePolicy.verifiedCopy(ByteArrayInputStream(bad), out, release()) }
                assertFalse(out.exists()); assertFalse(File(out.path + ".part").exists())
            }
            val broken = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("connection interrupted") }
            assertThrows(java.io.IOException::class.java) { UpdatePolicy.verifiedCopy(broken, out, release()) }
            assertFalse(out.exists()); assertFalse(File(out.path + ".part").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun installationRequiresMatchingPackageVersionSignerAndIncreasingVersionCode() {
        fun verify(pkg: String = "net.ankio.auto", name: String? = "0.1.3", code: Long = 12, keys: Set<String> = setOf("certificate")) =
            UpdatePolicy.validateIdentity(pkg, name, code, keys, "net.ankio.auto", 11, setOf("certificate"), release())
        verify()
        rejects { verify(pkg = "upstream.other") }; rejects { verify(name = "0.1.4") }; rejects { verify(name = null) }
        rejects { verify(code = 11) }; rejects { verify(code = 10) }
        rejects { verify(keys = setOf("different certificate")) }; rejects { verify(keys = emptySet()) }
    }
}
