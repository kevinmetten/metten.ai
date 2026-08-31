package com.mobileclaw.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePresentationSemanticsTest {
    @Test
    fun `item counts use English singular and plural`() {
        assertEquals("0 items", WorkspacePresentationSemantics.itemCount(0))
        assertEquals("1 item", WorkspacePresentationSemantics.itemCount(1))
        assertEquals("2 items", WorkspacePresentationSemantics.itemCount(2))
    }

    @Test
    fun `event categories retain grouping with English labels`() {
        assertEquals("Progress", WorkspacePresentationSemantics.eventCategory("tool_call"))
        assertEquals("Reminder", WorkspacePresentationSemantics.eventCategory("phone_control_guard"))
        assertEquals("Repair", WorkspacePresentationSemantics.eventCategory("validation_repair"))
        assertEquals("Completed", WorkspacePresentationSemantics.eventCategory("task_completed"))
        assertEquals("Blocked", WorkspacePresentationSemantics.eventCategory("task_error"))
    }

    @Test
    fun `unknown event categories preserve arbitrary unicode`() {
        assertEquals("custom_event", WorkspacePresentationSemantics.eventCategory("custom_event"))
        assertEquals("حدث_خاص", WorkspacePresentationSemantics.eventCategory("حدث_خاص"))
    }
}
