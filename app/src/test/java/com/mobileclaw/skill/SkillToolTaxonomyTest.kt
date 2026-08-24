package com.mobileclaw.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolTaxonomyTest {
    @Test
    fun `generic sticker metadata is inferred as media`() {
        val meta = SkillMeta(
            id = "custom_sticker_creator",
            name = "Sticker Creator",
            description = "Creates reusable sticker images",
        )

        assertTrue(SkillToolCategory.MEDIA in SkillToolTaxonomy.categoriesFor(meta))
    }

    @Test
    fun `neutral metadata is not inferred as media`() {
        val meta = SkillMeta(
            id = "clock_reader",
            name = "Clock Reader",
            description = "Reads the current time",
        )

        assertFalse(SkillToolCategory.MEDIA in SkillToolTaxonomy.categoriesFor(meta))
    }
}
