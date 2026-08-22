package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerTest {
    @Test
    fun `parses the English planning contract`() {
        val plan = parse(
            """
            Summary: Research the release
            Steps:
            1. Find authoritative sources
            2. Compare the release notes
            3. Summarize the changes
            Success criteria: The summary cites the relevant sources
            """.trimIndent(),
            TaskType.WEB_RESEARCH,
        )

        assertEquals("Research the release", plan.summary)
        assertEquals(listOf("Find authoritative sources", "Compare the release notes", "Summarize the changes"), plan.steps)
        assertEquals("The summary cites the relevant sources", plan.successCriteria)
    }

    @Test
    fun `labels are case insensitive and allow harmless whitespace`() {
        val plan = parse(
            """
              sUmMaRy :   Update the artifact
              sTePs:
              1) Inspect the current artifact
              2) Apply the requested update
              3) Verify the result
              SuCcEsS CrItErIa :   The updated artifact is usable
            """.trimIndent(),
            TaskType.APP_BUILD,
        )

        assertEquals("Update the artifact", plan.summary)
        assertEquals("Inspect the current artifact", plan.steps.first())
        assertEquals("The updated artifact is usable", plan.successCriteria)
    }

    @Test
    fun `bullet steps remain supported`() {
        val plan = parse(
            """
            Summary: Create the report
            Steps:
            - Determine the required sections
            - Create the document
            - Verify the exported file
            Success criteria: The report opens correctly
            """.trimIndent(),
            TaskType.FILE_CREATE,
        )

        assertEquals(3, plan.steps.size)
        assertEquals("Create the document", plan.steps[1])
    }

    @Test
    fun `missing steps use task specific fallback steps`() {
        val plan = parse(
            """
            Summary: Complete the phone task
            Steps:
            Success criteria: The requested state is visible
            """.trimIndent(),
            TaskType.PHONE_CONTROL,
        )

        assertEquals(TaskPlanner.fallback("", TaskType.PHONE_CONTROL).steps, plan.steps)
        assertTrue(plan.steps.first().contains("Observe"))
    }

    @Test
    fun `blank malformed and alternate labels fall back safely`() {
        assertNull(TaskPlanner.parsePlan("", TaskType.GENERAL))
        assertNull(TaskPlanner.parsePlan("Write a useful plan in prose.", TaskType.GENERAL))
        assertNull(
            TaskPlanner.parsePlan(
                """
                Overview: Update the page
                Actions:
                1. Inspect it
                Completion: The page works
                """.trimIndent(),
                TaskType.APP_BUILD,
            )
        )

        val fallback = TaskPlanner.parseOrFallback("not a structured plan", "Create a PDF", TaskType.FILE_CREATE)
        assertEquals(TaskPlanner.fallback("Create a PDF", TaskType.FILE_CREATE), fallback)
    }

    @Test
    fun `missing success criteria uses the English default`() {
        val plan = parse(
            """
            Summary: Run the command
            Steps:
            1. Execute the command
            2. Inspect the output
            """.trimIndent(),
            TaskType.CODE_EXECUTION,
        )

        assertEquals("The user goal is satisfied and the result is verified.", plan.successCriteria)
    }

    private fun parse(raw: String, taskType: TaskType): TaskPlan =
        requireNotNull(TaskPlanner.parsePlan(raw, taskType))
}
