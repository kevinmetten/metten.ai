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
    fun `execution intent matches whole words`() {
        assertTrue(MainExecutionSemantics.hasExecutionIntent("open the settings app"))
        assertTrue(MainExecutionSemantics.hasExecutionIntent("run the script"))
        assertTrue(MainExecutionSemantics.hasExecutionIntent("search for the report"))
        assertTrue(MainExecutionSemantics.hasExecutionIntent("connect the service"))

        assertFalse(MainExecutionSemantics.hasExecutionIntent("Explain connection pooling"))
        assertFalse(MainExecutionSemantics.hasExecutionIntent("The runner finished the race"))
        assertFalse(MainExecutionSemantics.hasExecutionIntent("Describe a searchlight"))
        assertFalse(MainExecutionSemantics.hasExecutionIntent("Discuss open-minded design"))
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
