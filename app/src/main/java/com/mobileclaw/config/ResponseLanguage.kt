package com.mobileclaw.config

const val ENGLISH_RESPONSE_LANGUAGE = "en"

fun normalizedResponseLanguage(@Suppress("UNUSED_PARAMETER") language: String): String =
    ENGLISH_RESPONSE_LANGUAGE

fun responseLanguageName(@Suppress("UNUSED_PARAMETER") language: String): String = "English"

fun responseLanguageSystemInstruction(@Suppress("UNUSED_PARAMETER") language: String): String = """
## Response Language
The app language is English. You MUST write all user-visible assistant text in English, regardless of whether the user typed Chinese, English, or another language.
Preserve code, file paths, JSON keys, tool names, tool arguments, quoted source text, and user-provided proper nouns exactly when required by the task.
If a strict output schema is requested, keep the schema intact and localize only natural-language values when appropriate.
""".trimIndent()

fun responseLanguageShortInstruction(@Suppress("UNUSED_PARAMETER") language: String): String =
    "Write all user-visible output in English."
