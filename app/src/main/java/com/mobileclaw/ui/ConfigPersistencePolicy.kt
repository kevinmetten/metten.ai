package com.mobileclaw.ui

import com.mobileclaw.config.ConfigSnapshot

/** Production policy separating persistence from the legacy save-and-exit behavior. */
internal class ConfigPersistencePolicy(
    private val update: suspend (ConfigSnapshot) -> Unit,
    private val navigate: (AppPage) -> Unit,
) {
    suspend fun updateInPlace(snapshot: ConfigSnapshot) = update(snapshot)

    suspend fun saveAndExit(snapshot: ConfigSnapshot) {
        update(snapshot)
        navigate(AppPage.HOME)
    }
}
