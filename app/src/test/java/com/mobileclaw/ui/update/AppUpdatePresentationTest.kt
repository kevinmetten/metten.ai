package com.mobileclaw.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePresentationTest {
    @Test
    fun `info formatter preserves version and unicode release data`() {
        val notes = "إصلاحات وتحسينات\n✨"
        val available = AppUpdatePresentation.formatInfo(true, "1.0", 10, "2.0-beta", notes)
        assertTrue(available.startsWith("New version available."))
        assertTrue(available.contains("Current version: 1.0 (10)"))
        assertTrue(available.contains("Latest version: 2.0-beta"))
        assertTrue(available.contains(notes))
        assertTrue(AppUpdatePresentation.formatInfo(false, "2.0", 20, "", "").startsWith("You're already on the latest version."))
    }

    @Test
    fun `configuration failures use canonical explanation`() {
        assertEquals(AppUpdatePresentation.NOT_CONFIGURED, AppUpdatePresentation.formatRawResult(false, "Configure pgyer_api_key first"))
        assertEquals(AppUpdatePresentation.NOT_CONFIGURED, AppUpdatePresentation.formatRawResult(false, "Configure release channel api_key and app_key first."))
    }

    @Test
    fun `failed output filters download and preserves external error`() {
        val error = "تعذر الاتصال بالخدمة"
        val formatted = AppUpdatePresentation.formatRawResult(false, "$error\nDownload: https://example.invalid/app.apk")
        assertEquals("Update check failed: $error", formatted)
        assertEquals("Update check failed: Network or configuration error", AppUpdatePresentation.formatRawResult(false, "Download: hidden"))
        assertFalse(formatted.contains("Download:"))
    }

    @Test
    fun `release provider branding is generic case insensitively`() {
        val sanitized = with(AppUpdatePresentation) { "Pgyer pgyer PGYER".releaseMessageForUser() }
        assertEquals("update service update service update service", sanitized)
    }
}
