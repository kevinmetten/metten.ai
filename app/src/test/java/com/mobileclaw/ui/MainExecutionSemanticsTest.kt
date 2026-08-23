package com.mobileclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainExecutionSemanticsTest {
    @Test
    fun `capability questions are conservative`() {
        assertTrue(MainExecutionSemantics.isCapabilityInfoQuestion("What can you do?"))
        assertTrue(MainExecutionSemantics.isCapabilityInfoQuestion("What tools do you have?"))
        assertFalse(MainExecutionSemantics.isCapabilityInfoQuestion("Explain how Android accessibility tools work"))
    }

    @Test
    fun `continuations only recognize complete short commands`() {
        assertTrue(MainExecutionSemantics.isRecentContinuationCommand("continue"))
        assertTrue(MainExecutionSemantics.isRecentContinuationCommand("keep going"))
        assertTrue(MainExecutionSemantics.isRecentContinuationCommand("next step"))
        assertFalse(MainExecutionSemantics.isRecentContinuationCommand("Continue monitoring the service and create a new report"))
    }

    @Test
    fun `phone operation requires explicit phone wording`() {
        assertTrue(MainExecutionSemantics.hasExplicitPhoneControlIntent("Open the app on my phone"))
        assertFalse(MainExecutionSemantics.hasExplicitPhoneControlIntent("The museum is open today"))
    }

    @Test
    fun `unknown unicode passes through without matching convenience routes`() {
        val goal = "اشرح لي هذه الفكرة"
        assertEquals("اشرح لي هذه الفكرة", goal)
        assertFalse(MainExecutionSemantics.isCapabilityInfoQuestion(goal))
        assertFalse(MainExecutionSemantics.isRecentContinuationCommand(goal))
        assertFalse(MainExecutionSemantics.hasExplicitPhoneControlIntent(goal))
    }
}
