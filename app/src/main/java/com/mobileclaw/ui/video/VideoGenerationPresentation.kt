package com.mobileclaw.ui.video

import com.mobileclaw.skill.builtin.VideoTaskStatuses

internal object VideoGenerationPresentation {
    fun taskStatus(status: String, error: String, downloadUrlPending: Boolean = false): String = when {
        status == VideoTaskStatuses.RUNNING && downloadUrlPending -> "Waiting for download URL"
        status == VideoTaskStatuses.SUBMITTED -> "Generating"
        status == VideoTaskStatuses.RUNNING -> "Generating"
        status == VideoTaskStatuses.TIMED_OUT -> "Tracking in background"
        status == VideoTaskStatuses.COMPLETED -> "Generated"
        status == VideoTaskStatuses.DOWNLOADED -> "Downloaded"
        status == VideoTaskStatuses.FAILED -> "Failed: ${error.take(80)}"
        else -> status
    }
}
