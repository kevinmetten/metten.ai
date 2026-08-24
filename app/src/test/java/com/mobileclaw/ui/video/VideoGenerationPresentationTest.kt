package com.mobileclaw.ui.video

import com.mobileclaw.skill.builtin.VideoTaskStatuses
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoGenerationPresentationTest {
    @Test
    fun `known task statuses use English labels`() {
        assertEquals("Generating", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.SUBMITTED, ""))
        assertEquals("Generating", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.RUNNING, ""))
        assertEquals("Tracking in background", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.TIMED_OUT, ""))
        assertEquals("Generated", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.COMPLETED, ""))
        assertEquals("Downloaded", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.DOWNLOADED, ""))
        assertEquals("Waiting for download URL", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.RUNNING, "", true))
    }

    @Test
    fun `runtime status and error data remain unchanged`() {
        val error = "تعذر إنشاء الفيديو"
        assertEquals("Failed: $error", VideoGenerationPresentation.taskStatus(VideoTaskStatuses.FAILED, error))
        assertEquals("حالة_مخصصة", VideoGenerationPresentation.taskStatus("حالة_مخصصة", ""))
    }
}
