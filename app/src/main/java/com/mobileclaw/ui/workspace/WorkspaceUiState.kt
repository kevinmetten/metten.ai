package com.mobileclaw.ui.workspace

import com.mobileclaw.memory.MemoryFact
import com.mobileclaw.workspace.WorkspaceInspectorSnapshot

// Workspace observations and memory state are aggregated locally for this feature.
data class WorkspaceUiState(
    val snapshot: WorkspaceInspectorSnapshot? = null,
    val facts: List<MemoryFact> = emptyList(),
    val areas: List<WorkspaceAreaUi> = emptyList(),
    val openArea: WorkspaceAreaUi? = null,
    val openAreaRoots: List<String> = emptyList(),
    val openAreaCurrentPath: String = "",
    val openAreaEntries: List<WorkspaceFileEntryUi> = emptyList(),
)

data class WorkspaceAreaUi(
    val id: String,
    val title: String,
    val description: String,
    val countLabel: String,
    val statusLabel: String,
)

data class WorkspaceFileEntryUi(
    val path: String,
    val absolutePath: String,
    val isDirectory: Boolean,
    val sizeLabel: String,
    val updatedLabel: String,
    val preview: String = "",
)
