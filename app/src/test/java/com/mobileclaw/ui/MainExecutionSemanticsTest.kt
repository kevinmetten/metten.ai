package com.mobileclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainExecutionSemanticsTest {
    @Test
    fun capabilityQuestionsAreConservative() {
        assertTrue(MainExecutionSemantics.isCapabilityInfoQuestion("What can you do?"))
        assertTrue(MainExecutionSemantics.isCapabilityInfoQuestion("What tools do you have?"))
        assertFalse(MainExecutionSemantics.isCapabilityInfoQuestion("Explain how Android accessibility tools work"))
    }

    @Test
    fun continuationCommandsMustBeTheWholeRequest() {
        listOf("continue", "keep going", "next step").forEach {
            assertTrue(MainExecutionSemantics.isRecentContinuationCommand(it))
        }
        assertFalse(MainExecutionSemantics.isRecentContinuationCommand("Continue monitoring the service and create a new report"))
    }

    @Test
    fun phoneControlRequiresAnExplicitPhonePhrase() {
        assertTrue(MainExecutionSemantics.hasExplicitPhoneControlIntent("Open the app on my phone"))
        assertFalse(MainExecutionSemantics.hasExplicitPhoneControlIntent("Open the quarterly report"))
    }

    @Test
    fun arbitraryUnicodeFailsOpenWithoutMutation() {
        val input = "افتح التقرير الجديد"
        assertEquals("افتح التقرير الجديد", input)
        assertFalse(MainExecutionSemantics.isCapabilityInfoQuestion(input))
        assertFalse(MainExecutionSemantics.isRecentContinuationCommand(input))
        assertFalse(MainExecutionSemantics.hasExplicitPhoneControlIntent(input))
        assertFalse(MainExecutionSemantics.hasMemoryIntent(input))
        assertFalse(MainExecutionSemantics.hasExecutionIntent(input))
    }
}
