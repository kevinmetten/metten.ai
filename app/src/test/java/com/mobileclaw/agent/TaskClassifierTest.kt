package com.mobileclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskClassifierTest {
    @Test
    fun `phone control recognizes direct app and screen actions`() {
        assertType(TaskType.PHONE_CONTROL, "Open Gmail")
        assertType(TaskType.PHONE_CONTROL, "Open Maps and search for coffee")
        assertType(TaskType.PHONE_CONTROL, "Scroll down and tap Settings")
        assertType(TaskType.PHONE_CONTROL, "tap the blue button", hasImage = true)
    }

    @Test
    fun `general requests include analysis questions and short follow ups`() {
        assertType(TaskType.GENERAL, "Describe this image", hasImage = true)
        listOf("Continue", "Fix it", "try again", "change it", "not this").forEach {
            assertType(TaskType.GENERAL, it)
        }
        assertType(TaskType.GENERAL, "Explain how Android navigation works")
        assertType(TaskType.GENERAL, "What is dependency injection?")
    }

    @Test
    fun `web research requires explicit online research intent`() {
        assertType(TaskType.WEB_RESEARCH, "Search the web for the latest Android news")
        assertType(TaskType.WEB_RESEARCH, "Find sources about this")
        assertType(TaskType.GENERAL, "Don't search the web; use what I gave you")
        assertType(TaskType.PHONE_CONTROL, "Open Maps and type coffee in the search box")
    }

    @Test
    fun `media generation excludes image analysis`() {
        assertType(TaskType.IMAGE_GENERATION, "Generate an image of a mountain")
        assertType(TaskType.GENERAL, "Describe this image")
        assertType(TaskType.GENERAL, "Analyze this image", hasImage = true)
    }

    @Test
    fun `artifact requests distinguish apps pages and files`() {
        listOf("Build a dashboard page", "Create a mini app", "Make a calculator app").forEach {
            assertType(TaskType.APP_BUILD, it)
        }
        assertType(TaskType.FILE_CREATE, "Create a PDF report")
        assertType(TaskType.FILE_CREATE, "Export this as a CSV")
        assertType(TaskType.FILE_CREATE, "Explain this file")
    }

    @Test
    fun `vpn control is contextual and avoids generic false positives`() {
        assertType(TaskType.VPN_CONTROL, "Connect the VPN")
        assertType(TaskType.VPN_CONTROL, "Configure the proxy settings")
        assertType(TaskType.VPN_CONTROL, "Switch to another VPN server")
        assertType(TaskType.GENERAL, "Make the global header easier to read")
        assertType(TaskType.GENERAL, "Explain what a proxy server is")
    }

    @Test
    fun `skill management and code execution require concrete actions`() {
        assertType(TaskType.SKILL_MANAGEMENT, "Create a new skill")
        assertType(TaskType.SKILL_MANAGEMENT, "Edit the Researcher role")
        assertType(TaskType.GENERAL, "What skills are useful for research?")
        assertType(TaskType.CODE_EXECUTION, "Run this Python script")
        assertType(TaskType.CODE_EXECUTION, "Execute this shell command")
        assertType(TaskType.GENERAL, "Explain how Python generators work")
    }

    @Test
    fun `attachment precedence remains image action then image then file`() {
        assertType(TaskType.PHONE_CONTROL, "tap the blue button", hasImage = true, hasFile = true)
        assertType(TaskType.GENERAL, "Describe this image", hasImage = true, hasFile = true)
        assertType(TaskType.FILE_CREATE, "Summarize the attachment", hasFile = true)
        assertType(TaskType.FILE_CREATE, "Open Gmail", hasFile = true)
    }

    private fun assertType(
        expected: TaskType,
        goal: String,
        hasImage: Boolean = false,
        hasFile: Boolean = false,
    ) {
        assertEquals(expected, TaskClassifier.classify(goal, hasImage, hasFile))
    }
}
