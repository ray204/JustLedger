package org.ezbook.server.tools

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class PageLearningPolicyTest {
    private val page=PageLearningPolicy.Entry("payment.app","DetailActivity","layout-A")
    @Test fun laterLeavesPromptAvailableWhileRememberAndNeverSurvivePersistence() {
        assertTrue(PageLearningPolicy.shouldAsk(emptyList(),page)) // Later makes no write.
        assertFalse(PageLearningPolicy.automatic(emptyList(),page))
        for (never in listOf(false,true)) {
            val saved=PageLearningPolicy.remember(emptyList(),page,never)
            val restored=Gson().fromJson(Gson().toJson(saved),Array<PageLearningPolicy.Entry>::class.java).toList()
            assertFalse(PageLearningPolicy.shouldAsk(restored,page))
            assertEquals(!never,PageLearningPolicy.automatic(restored,page))
            assertEquals(1,PageLearningPolicy.remember(restored,page,never).size)
            assertTrue(PageLearningPolicy.shouldAsk(restored,page.copy(structure="layout-B")))
            assertTrue(PageLearningPolicy.shouldAsk(restored,page.copy(app="another.app")))
        }
    }
    @Test fun neverOverridesLegacyBroadRememberAndCanBeReversedWithoutAffectingOtherPages() {
        val legacy=page.copy(structure="")
        val saved=PageLearningPolicy.remember(listOf(legacy),page,never=true)
        assertFalse(PageLearningPolicy.automatic(saved,page))
        assertTrue(PageLearningPolicy.automatic(saved,page.copy(structure="layout-B")))
        val changed=PageLearningPolicy.remember(saved,page,never=false)
        assertTrue(PageLearningPolicy.automatic(changed,page))
        assertFalse(PageLearningPolicy.shouldAsk(emptyList(),page.copy(structure="")))
    }
}
