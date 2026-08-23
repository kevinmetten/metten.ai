package com.mobileclaw.ui.profile

import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mobileclaw.ui.chat.AiQuizQuestion

internal object ProfileAiGeneration {
    const val PERSONALITY_SYSTEM_INSTRUCTION =
        "You create grounded, thoughtful personality summaries that support self-reflection. Respond in English."

    const val QUIZ_SYSTEM_INSTRUCTION =
        "You create thoughtful self-reflection questions from supplied profile context. Return strict JSON only."

    fun buildPersonalitySummaryPrompt(
        factsText: String,
        foundationalMemory: String,
    ): String = """
Write a warm, professional personality analysis of approximately 200 words based on the supplied user profile data.

Requirements:
- Write in the second person and respond in English.
- Include a tentative MBTI-style estimate with one concise explanation. Treat it as an informal estimate, not a clinical determination.
- Identify 3-4 core personality traits.
- Describe communication and social style.
- Describe strengths and potential areas for growth.
- End with one concise concluding observation.
- Ground observations in the supplied facts. Frame uncertain conclusions as tentative, especially when information is sparse.
- Do not invent biographical facts.
- Start directly with the analysis; avoid generic preambles such as "Based on the data...".

User profile data:
$factsText

Foundational memory context:
$foundationalMemory
    """.trimIndent()

    fun buildDimensionQuizPrompt(
        dimensionId: String,
        dimensionTitle: String,
        relevantFacts: String,
        foundationalMemory: String,
    ): String {
        val exampleFactKey = JsonPrimitive("profile.$dimensionId.example").toString()
        return """
Create exactly 5 substantive self-reflection questions for the "$dimensionTitle" dimension. This is for personal reflection, not clinical diagnosis.

Known profile information:
$relevantFacts

Foundational memory context:
$foundationalMemory

Requirements:
- Write the questions, hints, and answer choices in English.
- Use known profile information while exploring useful unknown aspects instead of repeating known facts.
- Each question must have exactly 4 meaningfully distinct, nonblank answer choices.
- Include a concise hint explaining what the question explores.
- Each factKey must begin with "profile.$dimensionId." and end with a specific key appropriate to this dimension.
- Return exactly 5 objects. Each object must contain only question, hint, answers, and factKey.
- Return ONLY a JSON array. No Markdown. No code fences. No commentary before or after it.

Valid JSON shape example:
[
  {
    "question": "Question text",
    "hint": "What this question is intended to explore",
    "answers": [
      "Option A",
      "Option B",
      "Option C",
      "Option D"
    ],
    "factKey": $exampleFactKey
  }
]
        """.trimIndent()
    }

    fun parseDimensionQuiz(content: String, dimensionId: String): List<AiQuizQuestion> =
        runCatching {
            val array = JsonParser.parseString(content.trim()).asJsonArray
            if (array.size() != 5) return emptyList()
            val prefix = "profile.$dimensionId."
            array.map { element ->
                val item = element.asJsonObject
                val question = item.get("question")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                val hint = item.get("hint")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                val answers = item.getAsJsonArray("answers")?.map { answer ->
                    answer.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                }.orEmpty()
                val factKey = item.get("factKey")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                if (
                    question.isBlank() ||
                    answers.size != 4 ||
                    answers.any { it.isBlank() } ||
                    !factKey.startsWith(prefix) ||
                    factKey.removePrefix(prefix).isBlank()
                ) return emptyList()
                AiQuizQuestion(question = question, hint = hint, answers = answers, factKey = factKey)
            }
        }.getOrDefault(emptyList())
}
