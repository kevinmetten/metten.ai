package com.mobileclaw.ui

import com.mobileclaw.config.ConfigSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigPersistencePolicyTest {
    @Test fun `AI Basics persistence updates config without navigating home`() = runBlocking {
        var currentPage = AppPage.AI_BASIC_SETTINGS
        var persisted: ConfigSnapshot? = null
        val policy = ConfigPersistencePolicy(
            update = { persisted = it },
            navigate = { currentPage = it },
        )
        val snapshot = ConfigSnapshot(chatGptModel = "voice-test-model")

        policy.updateInPlace(snapshot)

        assertEquals(snapshot, persisted)
        assertEquals(AppPage.AI_BASIC_SETTINGS, currentPage)
    }

    @Test fun `legacy explicit save still persists and exits`() = runBlocking {
        var currentPage = AppPage.SETTINGS
        var persisted: ConfigSnapshot? = null
        val policy = ConfigPersistencePolicy(
            update = { persisted = it },
            navigate = { currentPage = it },
        )
        val snapshot = ConfigSnapshot()

        policy.saveAndExit(snapshot)

        assertEquals(snapshot, persisted)
        assertEquals(AppPage.HOME, currentPage)
    }
}
