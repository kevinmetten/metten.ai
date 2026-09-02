package com.mobileclaw.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatGptBackendSemanticsTest {
    @Test fun `client version is honest semantic version`() {
        assertEquals("1.2.3", semanticVersion("v1.2.3-14-gabc"))
        assertEquals("0.0.0", semanticVersion("development"))
    }

    @Test fun `backend routes remain ChatGPT Codex routes`() {
        assertEquals("https://chatgpt.com/backend-api/codex", CHATGPT_BACKEND_ROOT)
    }
}
