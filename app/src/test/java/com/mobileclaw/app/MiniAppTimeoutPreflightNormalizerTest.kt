package com.mobileclaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAppTimeoutPreflightNormalizerTest {
    @Test
    fun `timeout only report with a title is recovered structurally`() {
        val report = timeoutReport(title = "Dashboard")

        val normalized = normalize(report)

        assertTrue(normalized.ok)
        assertTrue(normalized.issues.isEmpty())
        assertEquals("Dashboard", normalized.title)
        assertEquals(report.recentLogs, normalized.recentLogs)
        assertTrue(normalized.warnings.single().contains("startup preflight"))
    }

    @Test
    fun `arbitrary benign log is meaningful progress without readiness vocabulary`() {
        val report = timeoutReport(logs = listOf("Renderer heartbeat received"))

        val normalized = normalize(report)

        assertTrue(normalized.ok)
        assertTrue(normalized.issues.isEmpty())
        assertEquals(listOf("Renderer heartbeat received"), normalized.recentLogs)
    }

    @Test
    fun `existing warning and diagnostics survive timeout recovery`() {
        val report = timeoutReport(
            title = "Reports",
            logs = listOf("Renderer heartbeat received"),
            warnings = listOf("Existing diagnostic warning"),
        )

        val normalized = normalize(report)

        assertEquals("Reports", normalized.title)
        assertEquals(report.recentLogs, normalized.recentLogs)
        assertTrue(normalized.warnings.contains("Existing diagnostic warning"))
        assertEquals(2, normalized.warnings.size)
    }

    @Test
    fun `startup and strict warnings identify their active modes`() {
        val report = timeoutReport(title = "Workspace")

        val startup = normalize(report, MiniAppPreflightValidator.Mode.STARTUP)
        val strict = normalize(report, MiniAppPreflightValidator.Mode.STRICT)

        assertTrue(startup.warnings.last().contains("startup preflight"))
        assertTrue(strict.warnings.last().contains("strict preflight"))
    }

    @Test
    fun `timeout without title or logs remains a failure`() {
        val report = timeoutReport()

        assertEquals(report, normalize(report))
    }

    @Test
    fun `timeout with another substantive issue remains a failure`() {
        val report = timeoutReport(
            title = "Dashboard",
            issues = listOf(TIMEOUT_ISSUE, "Preflight failed: document.body is missing after load."),
        )

        assertEquals(report, normalize(report))
    }

    @Test
    fun `technical runtime error signals prevent timeout recovery`() {
        val errorLogs = listOf(
            "[error] Render failed",
            "PRELOAD_JS_ERROR: missing symbol",
            "PRELOAD_PROMISE_REJECTION: request failed",
            "Unhandled Promise Rejection: request failed",
            "JS error: unexpected token",
        )

        errorLogs.forEach { log ->
            val report = timeoutReport(logs = listOf(log))
            val normalized = normalize(report)

            assertFalse("Error log must preserve failure: $log", normalized.ok)
            assertEquals(report, normalized)
        }
    }

    @Test
    fun `non-timeout failure and already-ok report remain unchanged`() {
        val failed = MiniAppPreflightReport(
            ok = false,
            issues = listOf("Preflight failed: Claw bridge was not injected."),
            title = "Dashboard",
            recentLogs = listOf("Renderer heartbeat received"),
        )
        val successful = MiniAppPreflightReport(
            ok = true,
            issues = emptyList(),
            warnings = listOf("Existing warning"),
            title = "Dashboard",
            recentLogs = listOf("Renderer heartbeat received"),
        )

        assertEquals(failed, normalize(failed))
        assertEquals(successful, normalize(successful))
    }

    private fun timeoutReport(
        title: String = "",
        logs: List<String> = emptyList(),
        warnings: List<String> = emptyList(),
        issues: List<String> = listOf(TIMEOUT_ISSUE),
    ) = MiniAppPreflightReport(
        ok = false,
        issues = issues,
        warnings = warnings,
        title = title,
        recentLogs = logs,
    )

    private fun normalize(
        report: MiniAppPreflightReport,
        mode: MiniAppPreflightValidator.Mode = MiniAppPreflightValidator.Mode.STARTUP,
    ) = MiniAppTimeoutPreflightNormalizer.normalize(report, mode)

    private companion object {
        const val TIMEOUT_ISSUE =
            "MiniAPP preflight exceeded 4s before startup checks completed. Possible blocking startup code or an infinite loop."
    }
}
