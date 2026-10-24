package org.ezbook.server.tools

import org.junit.Assert.*
import org.junit.Test

class ForegroundPolicyTest {
    @Test fun ownOverlayDoesNotReplaceUnderlyingPaymentApp() {
        assertEquals("com.sankuai.meituan", CapturePolicy.foregroundPackage(listOf("com.sankuai.meituan" to true), "net.ankio.auto", "net.ankio.auto"))
        assertEquals("com.tencent.mm", CapturePolicy.foregroundPackage(listOf("com.sankuai.meituan" to false, "com.tencent.mm" to true), "net.ankio.auto", "net.ankio.auto"))
    }
    @Test fun actualOwnActivityAndUnknownWindowDoNotReuseStalePackage() {
        assertNull(CapturePolicy.foregroundPackage(listOf("net.ankio.auto" to true), "net.ankio.auto", "net.ankio.auto"))
        assertNull(CapturePolicy.foregroundPackage(emptyList(),null,"net.ankio.auto"))
        assertNull(CapturePolicy.foregroundPackage(emptyList(),"com.android.systemui","net.ankio.auto"))
    }
}
