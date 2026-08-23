package com.mobileclaw.ui.common

import com.mobileclaw.R
import com.mobileclaw.app.MiniApp
import com.mobileclaw.memory.db.EpisodeEntity
import com.mobileclaw.str

internal enum class RecommendationCategory {
    SEARCH,
    ANALYSIS,
    WRITING,
    LEARNING,
    TRANSLATION,
    EXPLORE,
}

internal enum class ProfileRecommendationCategory {
    DEVELOPMENT,
    DESIGN,
    EDUCATION,
    BUSINESS,
    WRITING,
    FINANCE,
    READING,
    FITNESS,
    TRAVEL,
    FOOD,
    GAMING,
    PHOTOGRAPHY,
}

internal enum class EmotionRecommendationCategory {
    FRUSTRATION,
    FATIGUE,
    STRESS,
    BOREDOM,
    CONFUSION,
    EMOTIONAL_SUPPORT,
}

internal object SmartRecommendationSemantics {
    private val requestPrefixPattern = Regex(
        "^(?:please\\s+help\\s+me|please\\s+help|help\\s+me|can\\s+you\\s+help|could\\s+you|please|help|let\\s+me|i\\s+want)\\s+",
        RegexOption.IGNORE_CASE,
    )

    private val appContextTerms = setOf(
        "app", "application", "applications", "tool", "tools", "program", "software", "interface",
        "web page", "html", "miniapp", "mini app", "native page",
    )
    private val searchTerms = setOf("search", "look up", "check", "research")
    private val analysisTerms = setOf("analyze", "analysis", "statistics", "data")
    private val writingTerms = setOf("write", "draft", "copywriting", "edit")
    private val learningTerms = setOf("learn", "research", "study")
    private val translationTerms = setOf("translate", "translation")

    private val frustrationTerms = setOf(
        "irritable", "annoyed", "frustrated", "upset", "anxious", "uneasy", "depressed", "uncomfortable", "overwhelmed",
    )
    private val fatigueTerms = setOf("tired", "exhausted", "fatigued", "low energy", "worn out", "so tired")
    private val stressTerms = setOf("stress", "deadline", "rushed", "overtime", "too busy", "overwhelmed")
    private val boredomTerms = setOf("bored", "idle", "nothing to do", "don't know what to do")
    private val confusionTerms = setOf("lost", "overwhelmed", "conflicted", "confused", "stuck")
    private val emotionalInstabilityTerms = setOf(
        "unstable", "anxious", "volatile", "breakdown", "insomnia", "poor", "draining",
    )

    private val developmentProfessionTerms = setOf("software", "development", "developer", "engineering", "engineer", "programming")
    private val designProfessionTerms = setOf("design", "designer")
    private val educationProfessionTerms = setOf("student", "learning", "education", "teacher")
    private val businessProfessionTerms = setOf("operations", "business", "market", "marketing", "product")
    private val writingProfessionTerms = setOf("writing", "writer", "media", "content")
    private val financeProfessionTerms = setOf("finance", "investment", "financial")

    private val readingInterestTerms = setOf("reading", "book")
    private val fitnessInterestTerms = setOf("fitness", "sport", "running")
    private val travelInterestTerms = setOf("travel", "tourism")
    private val foodInterestTerms = setOf("food", "cooking", "cook")
    private val gamingInterestTerms = setOf("game", "gaming")
    private val photographyInterestTerms = setOf("photography", "photo")

    fun normalizeTopic(goalText: String): String = requestPrefixPattern.replaceFirst(goalText.trim(), "").trim()

    fun isAppGoal(goalText: String): Boolean = goalText.containsAnyTerm(appContextTerms)

    fun episodeCategory(goalText: String): RecommendationCategory {
        val normalized = normalizeTopic(goalText)
        return when {
            normalized.containsAnyTerm(searchTerms) -> RecommendationCategory.SEARCH
            normalized.containsAnyTerm(analysisTerms) -> RecommendationCategory.ANALYSIS
            normalized.containsAnyTerm(writingTerms) -> RecommendationCategory.WRITING
            normalized.containsAnyTerm(learningTerms) -> RecommendationCategory.LEARNING
            normalized.containsAnyTerm(translationTerms) -> RecommendationCategory.TRANSLATION
            else -> RecommendationCategory.EXPLORE
        }
    }

    fun emotionCategory(messages: List<String>, profileFacts: Map<String, String>): EmotionRecommendationCategory? {
        val recentText = messages.take(15).joinToString(" ")
        return when {
            recentText.containsAnyTerm(frustrationTerms) -> EmotionRecommendationCategory.FRUSTRATION
            recentText.containsAnyTerm(fatigueTerms) -> EmotionRecommendationCategory.FATIGUE
            recentText.containsAnyTerm(stressTerms) -> EmotionRecommendationCategory.STRESS
            recentText.containsAnyTerm(boredomTerms) -> EmotionRecommendationCategory.BOREDOM
            recentText.containsAnyTerm(confusionTerms) -> EmotionRecommendationCategory.CONFUSION
            else -> profileFacts.entries
                .firstOrNull { (key, _) -> key.containsProfileKey("emotional", "stability") }
                ?.value
                ?.takeIf { it.containsAnyTerm(emotionalInstabilityTerms) }
                ?.let { EmotionRecommendationCategory.EMOTIONAL_SUPPORT }
        }
    }

    fun professionCategory(value: String): ProfileRecommendationCategory? = when {
        value.containsAnyTerm(developmentProfessionTerms) -> ProfileRecommendationCategory.DEVELOPMENT
        value.containsAnyTerm(designProfessionTerms) -> ProfileRecommendationCategory.DESIGN
        value.containsAnyTerm(educationProfessionTerms) -> ProfileRecommendationCategory.EDUCATION
        value.containsAnyTerm(businessProfessionTerms) -> ProfileRecommendationCategory.BUSINESS
        value.containsAnyTerm(writingProfessionTerms) -> ProfileRecommendationCategory.WRITING
        value.containsAnyTerm(financeProfessionTerms) -> ProfileRecommendationCategory.FINANCE
        else -> null
    }

    fun interestCategory(value: String): ProfileRecommendationCategory? = when {
        value.containsAnyTerm(readingInterestTerms) -> ProfileRecommendationCategory.READING
        value.containsAnyTerm(fitnessInterestTerms) -> ProfileRecommendationCategory.FITNESS
        value.containsAnyTerm(travelInterestTerms) -> ProfileRecommendationCategory.TRAVEL
        value.containsAnyTerm(foodInterestTerms) -> ProfileRecommendationCategory.FOOD
        value.containsAnyTerm(gamingInterestTerms) -> ProfileRecommendationCategory.GAMING
        value.containsAnyTerm(photographyInterestTerms) -> ProfileRecommendationCategory.PHOTOGRAPHY
        else -> null
    }

    private fun String.containsProfileKey(vararg keys: String): Boolean {
        val normalized = lowercase()
        return keys.any { normalized.contains(it) }
    }

    private fun String.containsAnyTerm(terms: Set<String>): Boolean {
        val tokens = Regex("[\\p{L}\\p{N}_]+").findAll(lowercase()).map { it.value }.toList()
        return terms.any { term ->
            val termTokens = Regex("[\\p{L}\\p{N}_]+").findAll(term.lowercase()).map { it.value }.toList()
            termTokens.isNotEmpty() && tokens.windowed(termTokens.size).any { it == termTokens }
        }
    }
}

internal fun buildSmartRecommendations(
    episodes: List<EpisodeEntity>,
    profileFacts: Map<String, String>,
    miniApps: List<MiniApp>,
    recentUserMessages: List<String>,
): List<String> {
    val result = mutableListOf<String>()

    miniApps.sortedByDescending { it.updatedAt }.take(2).forEachIndexed { index, app ->
        result += if (index == 0) {
            str(R.string.quick_suggest_continue_app, app.icon, app.title)
        } else {
            str(R.string.quick_suggest_add_feature, app.icon, app.title)
        }
    }

    detectEmotionSuggestion(recentUserMessages, profileFacts)?.let { result += it }

    episodes
        .filter { episode ->
            episode.goalText.isNotBlank() && episode.success && !SmartRecommendationSemantics.isAppGoal(episode.goalText)
        }
        .groupBy { it.goalText.trim().take(10).lowercase() }
        .entries
        .sortedByDescending { it.value.maxOf { episode -> episode.createdAt } }
        .mapNotNull { (_, matchingEpisodes) -> transformEpisodeToSuggestion(matchingEpisodes.first().goalText) }
        .distinctBy { it.take(12).lowercase() }
        .take(2)
        .forEach { result += it }

    if (result.size < 3) {
        result += buildProfileSuggestions(profileFacts).take(3 - result.size)
    }

    return result.distinctBy { it.take(12).lowercase() }.take(5)
}

private fun detectEmotionSuggestion(messages: List<String>, profileFacts: Map<String, String>): String? =
    when (SmartRecommendationSemantics.emotionCategory(messages, profileFacts)) {
        EmotionRecommendationCategory.FRUSTRATION -> str(R.string.smart_rec_calm_down)
        EmotionRecommendationCategory.FATIGUE -> str(R.string.smart_rec_restore_energy)
        EmotionRecommendationCategory.STRESS -> str(R.string.smart_rec_sort_priorities)
        EmotionRecommendationCategory.BOREDOM -> str(R.string.smart_rec_find_activity)
        EmotionRecommendationCategory.CONFUSION -> str(R.string.smart_rec_clarify_confusion)
        EmotionRecommendationCategory.EMOTIONAL_SUPPORT -> str(R.string.smart_rec_emotional_checkin)
        null -> null
    }

private fun transformEpisodeToSuggestion(goalText: String): String? {
    val topic = SmartRecommendationSemantics.normalizeTopic(goalText)
    if (topic.length < 2) return null
    val shortTopic = topic.take(15)
    return when (SmartRecommendationSemantics.episodeCategory(goalText)) {
        RecommendationCategory.SEARCH -> str(R.string.quick_suggest_search_deeper, shortTopic)
        RecommendationCategory.ANALYSIS -> str(R.string.quick_suggest_refine, shortTopic)
        RecommendationCategory.WRITING -> str(R.string.quick_suggest_improve, shortTopic)
        RecommendationCategory.LEARNING -> str(R.string.quick_suggest_learn, shortTopic)
        RecommendationCategory.TRANSLATION -> str(R.string.quick_suggest_continue_translation)
        RecommendationCategory.EXPLORE -> str(R.string.quick_suggest_explore, shortTopic)
    }
}

private fun buildProfileSuggestions(profileFacts: Map<String, String>): List<String> {
    val profession = profileFacts.entries
        .firstOrNull { (key, _) -> key.lowercase().let { it.contains("profession") || it.contains("job") || it.contains("occupation") } }
        ?.value
        .orEmpty()
    val interests = profileFacts.entries
        .filter { (key, _) -> key.lowercase().let { it.contains("interest") || it.contains("hobby") } }
        .joinToString(" ") { it.value }

    val suggestions = mutableListOf<String>()
    when (SmartRecommendationSemantics.professionCategory(profession)) {
        ProfileRecommendationCategory.DEVELOPMENT -> suggestions += listOf(
            str(R.string.smart_rec_review_code),
            str(R.string.smart_rec_tech_updates),
        )
        ProfileRecommendationCategory.DESIGN -> suggestions += listOf(
            str(R.string.smart_rec_ui_inspiration),
            str(R.string.smart_rec_creative_design),
        )
        ProfileRecommendationCategory.EDUCATION -> suggestions += listOf(
            str(R.string.smart_rec_study_notes),
            str(R.string.smart_rec_explain_concept),
        )
        ProfileRecommendationCategory.BUSINESS -> suggestions += listOf(
            str(R.string.smart_rec_industry_trends),
            str(R.string.smart_rec_promotion_copy),
        )
        ProfileRecommendationCategory.WRITING -> suggestions += listOf(
            str(R.string.smart_rec_polish_text),
            str(R.string.smart_rec_trending_topics),
        )
        ProfileRecommendationCategory.FINANCE -> suggestions += listOf(
            str(R.string.smart_rec_finance_news),
            str(R.string.smart_rec_income_expense_analysis),
        )
        else -> Unit
    }

    when (SmartRecommendationSemantics.interestCategory(interests)) {
        ProfileRecommendationCategory.READING -> suggestions += str(R.string.smart_rec_book_summary)
        ProfileRecommendationCategory.FITNESS -> suggestions += str(R.string.smart_rec_workout_plan)
        ProfileRecommendationCategory.TRAVEL -> suggestions += str(R.string.smart_rec_travel_guides)
        ProfileRecommendationCategory.FOOD -> suggestions += str(R.string.smart_rec_dish_to_cook)
        ProfileRecommendationCategory.GAMING -> suggestions += str(R.string.smart_rec_game_news)
        ProfileRecommendationCategory.PHOTOGRAPHY -> suggestions += str(R.string.smart_rec_image_composition)
        else -> Unit
    }

    return suggestions.take(3)
}
