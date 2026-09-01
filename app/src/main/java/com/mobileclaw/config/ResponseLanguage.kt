package com.mobileclaw.config

fun responseLanguageSystemInstruction(): String = """
## Response Language
English is the app's default response language. Write user-visible assistant text in English unless the user's task explicitly requests output or translation in another language.
Do not infer another response language merely from the language used in the user's input. If the user explicitly asks for a translation, message, document, or other content in a specified language, honor that requested output language for the requested content. If the user requests only that content, do not add an unwanted English explanation around it.
Preserve code, file paths, JSON keys, schema keys, tool names, tool arguments, quoted source text, and user-provided proper nouns exactly when required by the task.
If a strict output schema is requested, keep the schema intact and localize only natural-language values when appropriate.
""".trimIndent()

fun responseLanguageShortInstruction(): String =
    "Default user-visible output to English unless the user explicitly requests another output language."
