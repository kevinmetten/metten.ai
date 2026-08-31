package com.mobileclaw.agent

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.mobileclaw.storage.AtomicTextFile
import java.io.File
import java.util.UUID

data class TaskReplayStep(
    val index: Int,
    val thought: String,
    val skillId: String?,
    val skillParams: Map<String, Any>?,
    val observation: String,
    val isError: Boolean,
    val timestampMs: Long,
    val hasImage: Boolean = false,
)

data class TaskReplay(
    val id: String,
    val goal: String,
    val summary: String,
    val success: Boolean,
    val taskType: String,
    val roleId: String,
    val roleName: String,
    val steps: List<TaskReplayStep>,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

data class TaskRecipeStep(
    val index: Int,
    val skillId: String,
    val paramsJson: String,
    val note: String,
)

data class TaskRecipe(
    val id: String,
    val title: String,
    val goal: String,
    val taskType: String,
    val roleId: String,
    val sourceReplayId: String,
    val steps: List<TaskRecipeStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class TaskReplayStore(filesDir: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val ioLock = Any()
    private val dir = File(filesDir, "task_replays").also { it.mkdirs() }

    fun record(result: AgentResult, taskType: TaskType, role: Role): TaskReplay {
        val steps = result.context.steps.map { step ->
            TaskReplayStep(
                index = step.index,
                thought = step.thought.take(1200),
                skillId = step.skillId,
                skillParams = step.skillParams,
                observation = step.observation.take(3000),
                isError = step.isError,
                timestampMs = step.timestampMs,
                hasImage = !step.imageBase64.isNullOrBlank(),
            )
        }
        val firstTs = result.context.steps.firstOrNull()?.timestampMs ?: System.currentTimeMillis()
        val lastTs = result.context.steps.lastOrNull()?.timestampMs ?: firstTs
        val replay = TaskReplay(
            id = result.context.taskId,
            goal = result.context.goal,
            summary = result.summary,
            success = result.success,
            taskType = taskType.name,
            roleId = role.id,
            roleName = role.name,
            steps = steps,
            durationMs = (lastTs - firstTs).coerceAtLeast(0L),
        )
        save(replay)
        return replay
    }

    fun save(replay: TaskReplay) {
        synchronized(ioLock) {
            AtomicTextFile.write(File(dir, "${replay.id}.json"), gson.toJson(replay))
            prune()
        }
    }

    fun get(id: String): TaskReplay? = synchronized(ioLock) {
        runCatching { gson.fromJson(AtomicTextFile.readOrNull(File(dir, "$id.json")), TaskReplay::class.java) }.getOrNull()
    }

    fun recent(limit: Int = 50): List<TaskReplay> = synchronized(ioLock) {
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file -> runCatching { gson.fromJson(AtomicTextFile.readOrNull(file), TaskReplay::class.java) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?.take(limit)
            ?: emptyList()
    }

    private fun prune(max: Int = 200) {
        val files = dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        files.drop(max).forEach { it.delete() }
    }
}

class TaskRecipeStore(filesDir: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val ioLock = Any()
    private val dir = File(filesDir, "task_recipes").also { it.mkdirs() }

    fun createFromReplay(replay: TaskReplay, title: String = replay.goal.toRecipeTitle()): TaskRecipe? {
        val actionSteps = replay.steps
            .filter { !it.isError && !it.skillId.isNullOrBlank() }
            .mapNotNull { step ->
                val skillId = step.skillId ?: return@mapNotNull null
                TaskRecipeStep(
                    index = step.index,
                    skillId = skillId,
                    paramsJson = gson.toJson(step.skillParams ?: emptyMap<String, Any>()),
                    note = step.observation.take(240),
                )
            }
        if (actionSteps.isEmpty()) return null
        val existing = list().firstOrNull { it.sourceReplayId == replay.id }
        val recipe = TaskRecipe(
            id = existing?.id ?: "recipe_${UUID.randomUUID().toString().take(8)}",
            title = title,
            goal = replay.goal,
            taskType = replay.taskType,
            roleId = replay.roleId,
            sourceReplayId = replay.id,
            steps = actionSteps,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        save(recipe)
        return recipe
    }

    fun save(recipe: TaskRecipe) {
        synchronized(ioLock) {
            AtomicTextFile.write(File(dir, "${recipe.id}.json"), gson.toJson(recipe))
        }
    }

    fun get(id: String): TaskRecipe? = synchronized(ioLock) {
        runCatching { gson.fromJson(AtomicTextFile.readOrNull(File(dir, "$id.json")), TaskRecipe::class.java) }.getOrNull()
    }

    fun list(limit: Int = 50): List<TaskRecipe> = synchronized(ioLock) {
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file -> runCatching { gson.fromJson(AtomicTextFile.readOrNull(file), TaskRecipe::class.java) }.getOrNull() }
            ?.sortedByDescending { it.updatedAt }
            ?.take(limit)
            ?: emptyList()
    }

    fun delete(id: String): Boolean = File(dir, "$id.json").delete()

    fun buildRunPrompt(recipe: TaskRecipe): String = buildString {
        appendLine("Execute this saved MobileClaw task recipe.")
        appendLine("Recipe: ${recipe.title}")
        appendLine("Original goal: ${recipe.goal}")
        appendLine("Task type: ${recipe.taskType}")
        appendLine()
        appendLine("Previous successful steps are references only. Do not copy them blindly because the UI or network state may have changed.")
        appendLine("Assess the current state first. Reuse the steps when they fit the same intent; otherwise observe and adapt.")
        appendLine()
        appendLine("Reference steps:")
        recipe.steps.forEach { step ->
            appendLine("${step.index}. ${step.skillId} ${step.paramsJson.take(500)}")
            if (step.note.isNotBlank()) appendLine("   result: ${step.note.replace('\n', ' ').take(180)}")
        }
    }

    private fun String.toRecipeTitle(): String =
        trim()
            .replace(Regex("""\s+"""), " ")
            .take(28)
            .ifBlank { "Saved task recipe" }
}
