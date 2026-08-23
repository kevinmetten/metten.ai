package com.mobileclaw.agent

import com.mobileclaw.skill.Skill
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillRegistry
import com.mobileclaw.skill.SkillResult
import com.mobileclaw.skill.SkillToolCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskToolPolicyTest {
    @Test
    fun `phone action and text entry goals retain relevant tools`() {
        val registry = registryWith(
            meta("see_screen", SkillToolCategory.PHONE),
            meta("phone_status", SkillToolCategory.PHONE),
            meta("tap", SkillToolCategory.PHONE),
            meta("input_text", SkillToolCategory.PHONE),
            meta("navigate", SkillToolCategory.PHONE),
        )

        val actionIds = selectedIds(registry, TaskType.PHONE_CONTROL, "tap the Settings button")
        val textIds = selectedIds(registry, TaskType.PHONE_CONTROL, "type hello into the search field")

        assertTrue("tap" in actionIds)
        assertTrue("see_screen" in actionIds)
        assertTrue("input_text" in textIds)
        assertTrue("tap" in textIds)
    }

    @Test
    fun `structural MiniAPP signal overrides ordinary app build keywords`() {
        val registry = artifactRegistry()
        val goal = "artifact_type=miniapp\ncurrent artifact: miniapp\nupdate the native-looking settings page"

        val ids = selectedIds(registry, TaskType.APP_BUILD, goal)

        assertTrue("app_manager" in ids)
        assertFalse("ui_builder" in ids)
    }

    @Test
    fun `native settings page prefers UI builder`() {
        val ids = selectedIds(artifactRegistry(), TaskType.APP_BUILD, "build a native settings page")

        assertTrue("ui_builder" in ids)
        assertFalse("app_manager" in ids)
    }

    @Test
    fun `web research search goal retains search tools`() {
        val registry = registryWith(
            meta("web_search", SkillToolCategory.WEB),
            meta("web_browse", SkillToolCategory.WEB),
            meta("fetch_url", SkillToolCategory.WEB),
            meta("web_content", SkillToolCategory.WEB),
        )

        val ids = selectedIds(registry, TaskType.WEB_RESEARCH, "search for the latest sources")

        assertTrue("web_search" in ids)
        assertTrue("web_browse" in ids)
    }

    @Test
    fun `unknown language goal fails open to normal task policy`() {
        val registry = registryWith(
            meta("memory", SkillToolCategory.MEMORY),
            meta("ui_builder", SkillToolCategory.ARTIFACT),
            meta("web_search", SkillToolCategory.WEB),
        )

        val ids = selectedIds(registry, TaskType.GENERAL, "أنشئ ملخصاً للمشروع 🚀")

        assertTrue(ids.isNotEmpty())
        assertTrue("memory" in ids)
        assertTrue("ui_builder" in ids)
        assertTrue("web_search" in ids)
    }

    @Test
    fun `canonical Unicode skill metadata matches without localized metadata`() {
        val registry = registryWith(
            meta(
                id = "translator_custom",
                category = SkillToolCategory.SYSTEM,
                name = "مترجم",
                description = "أداة ترجمة مخصصة",
                tags = listOf("ترجمة"),
                nameZh = "legacy-only-name",
            ),
        )

        val canonicalMatch = selectedIds(registry, TaskType.GENERAL, "استخدم مترجم للنص")
        val localizedOnlyMatch = selectedIds(registry, TaskType.GENERAL, "legacy-only-name")

        assertTrue("translator_custom" in canonicalMatch)
        assertFalse("translator_custom" in localizedOnlyMatch)
    }

    @Test
    fun `English and machine no-web policies filter web tools`() {
        val registry = registryWith(
            meta("memory", SkillToolCategory.MEMORY),
            meta("web_search", SkillToolCategory.WEB),
            meta("fetch_url", SkillToolCategory.WEB),
        )

        val english = selectedIds(registry, TaskType.GENERAL, "research sources", "no web search")
        val machine = selectedIds(
            registry,
            TaskType.GENERAL,
            "research sources",
            "image_understanding.no_web_search=true",
        )
        val unrelatedUnicode = selectedIds(registry, TaskType.GENERAL, "research sources", "أفضل القراءة بهدوء")

        assertFalse("web_search" in english)
        assertFalse("fetch_url" in machine)
        assertTrue("web_search" in unrelatedUnicode)
    }

    @Test
    fun `forced dynamic skills survive memory and goal narrowing`() {
        val registry = registryWith(
            meta("web_search", SkillToolCategory.WEB),
            meta("forced_custom", SkillToolCategory.SYSTEM, injectionLevel = 2),
        )

        val ids = TaskToolPolicy.select(
            registry = registry,
            taskType = TaskType.GENERAL,
            goal = "create a native page",
            forcedSkillIds = listOf("forced_custom"),
            memoryContext = "no web search",
        ).map { it.id }.toSet()

        assertTrue("forced_custom" in ids)
        assertFalse("web_search" in ids)
    }

    private fun selectedIds(
        registry: SkillRegistry,
        taskType: TaskType,
        goal: String,
        memoryContext: String = "",
    ): Set<String> = TaskToolPolicy.select(
        registry = registry,
        taskType = taskType,
        goal = goal,
        memoryContext = memoryContext,
    ).map { it.id }.toSet()

    private fun artifactRegistry(): SkillRegistry = registryWith(
        meta("ui_builder", SkillToolCategory.ARTIFACT),
        meta("app_manager", SkillToolCategory.ARTIFACT),
        meta("create_html", SkillToolCategory.ARTIFACT),
        meta("create_file", SkillToolCategory.ARTIFACT),
        meta("read_file", SkillToolCategory.ARTIFACT),
        meta("list_files", SkillToolCategory.ARTIFACT),
    )

    private fun registryWith(vararg metas: SkillMeta): SkillRegistry = SkillRegistry().apply {
        metas.forEach { skillMeta ->
            register(
                object : Skill {
                    override val meta: SkillMeta = skillMeta

                    override suspend fun execute(params: Map<String, Any>): SkillResult =
                        SkillResult(success = true, output = "ok")
                },
            )
        }
    }

    private fun meta(
        id: String,
        category: SkillToolCategory,
        name: String = id,
        description: String = "Test skill for $id",
        tags: List<String> = emptyList(),
        nameZh: String? = null,
        injectionLevel: Int = 1,
    ): SkillMeta = SkillMeta(
        id = id,
        name = name,
        description = description,
        nameZh = nameZh,
        tags = tags,
        categories = listOf(category),
        injectionLevel = injectionLevel,
    )
}
