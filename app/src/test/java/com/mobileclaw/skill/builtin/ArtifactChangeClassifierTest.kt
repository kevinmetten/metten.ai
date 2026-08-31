package com.mobileclaw.skill.builtin

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtifactChangeClassifierTest {
    @Test
    fun `classifies UI surface changes`() {
        assertClassification("Adjust the layout spacing and color theme", "ui_surface", "refine")
    }

    @Test
    fun `classifies bug repairs`() {
        assertClassification("Fix the broken checkout error", "bug_fix", "fix")
    }

    @Test
    fun `classifies functional interaction changes`() {
        assertClassification("Update the button interaction workflow", "behavior", "refine")
    }

    @Test
    fun `classifies wording changes`() {
        assertClassification("Change the button label wording", "copywriting", "refine")
    }

    @Test
    fun `classifies feature additions`() {
        assertClassification("Add a new export feature", "behavior", "extend")
    }

    @Test
    fun `classifies feature removals`() {
        assertClassification("Remove the obsolete export feature", "behavior", "remove")
    }

    @Test
    fun `classifies optimization as refinement`() {
        assertClassification("Optimize the visual design", "ui_surface", "refine")
    }

    @Test
    fun `uses conservative defaults for neutral requests`() {
        assertClassification("Reconcile the latest artifact", "targeted_patch", "modify")
    }

    @Test
    fun `matches whole terms instead of arbitrary substrings`() {
        assertClassification("Update the subtitle", "targeted_patch", "refine")
    }

    private fun assertClassification(request: String, focus: String, type: String) {
        assertEquals(focus, ArtifactChangeClassifier.patchFocus(request))
        assertEquals(type, ArtifactChangeClassifier.changeType(request))
    }
}
