package com.mobileclaw.ui.profile

import com.mobileclaw.memory.MemoryFact
import com.mobileclaw.memory.db.EpisodeEntity
import com.mobileclaw.ui.chat.AiQuizQuestion

// Keep profile and memory runtime state in the profile feature instead of expanding MainUiState.
data class ProfileUiState(
    val facts: Map<String, String> = emptyMap(),
    val semanticFacts: List<MemoryFact> = emptyList(),
    val memoryHasMore: Boolean = false,
    val memoryLoadingMore: Boolean = false,
    val recentEpisodes: List<EpisodeEntity> = emptyList(),
    val isLoading: Boolean = false,
    val isExtracting: Boolean = false,
    val conversationCount: Int = 0,
    val personalitySummary: String = "",
    val personalitySummaryLoading: Boolean = false,
    val dimensionQuizzes: Map<String, List<AiQuizQuestion>> = emptyMap(),
    val dimensionQuizLoading: String? = null,
)
