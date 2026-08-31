package com.mobileclaw.skill.builtin

internal object ArtifactChangeClassifier {
    fun patchFocus(changeRequest: String): String = when {
        changeRequest.containsAnyTerm("bug", "fix", "error", "broken", "issue", "regression") -> "bug_fix"
        changeRequest.containsAnyTerm("ui", "layout", "style", "visual", "appearance", "spacing", "color", "theme", "design") -> "ui_surface"
        changeRequest.containsAnyTerm("copy", "text", "wording", "label", "translation", "translate", "language") -> "copywriting"
        changeRequest.containsAnyTerm("feature", "function", "functionality", "button", "interaction", "behavior", "behaviour", "logic", "workflow") -> "behavior"
        else -> "targeted_patch"
    }

    fun changeType(changeRequest: String): String = when {
        changeRequest.containsAnyTerm("remove", "delete", "drop", "get rid of") -> "remove"
        changeRequest.containsAnyTerm("fix", "bug", "error", "broken", "repair") -> "fix"
        changeRequest.containsAnyTerm("add", "new", "include", "extend", "additional") -> "extend"
        changeRequest.containsAnyTerm("optimize", "optimise", "adjust", "refine", "improve", "tweak", "change", "update", "modify") -> "refine"
        else -> "modify"
    }

    private fun String.containsAnyTerm(vararg terms: String): Boolean {
        val tokens = wordTokens()
        return terms.any { term ->
            val termTokens = term.wordTokens()
            termTokens.isNotEmpty() && tokens.windowed(termTokens.size).any { it == termTokens }
        }
    }

    private fun String.wordTokens(): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(lowercase()).map { it.value }.toList()
}
