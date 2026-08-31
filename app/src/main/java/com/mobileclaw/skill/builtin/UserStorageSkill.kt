package com.mobileclaw.skill.builtin

import com.mobileclaw.config.UserStorageManager
import com.mobileclaw.skill.Skill
import com.mobileclaw.skill.SkillMeta
import com.mobileclaw.skill.SkillParam
import com.mobileclaw.skill.SkillResult
import com.mobileclaw.skill.SkillType
import com.mobileclaw.skill.SkillToolCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UserStorageSkill(private val storage: UserStorageManager) : Skill {

    override val meta = SkillMeta(
        id = "user_storage",
        name = "User Storage",
        description = "Manage files and folders in the user's external storage (Downloads, Documents, Pictures, etc.). " +
            "Actions: list_dirs (well-known directories), list (list files in a path), read (read file content), " +
            "write (write/create a file), delete (delete file or folder), copy, move, search, create_dir. " +
            "Requires the user to have granted 'All Files Access' permission.",
        parameters = listOf(
            SkillParam("action", "string", "One of: list_dirs | list | read | write | delete | copy | move | search | create_dir"),
            SkillParam("path", "string", "Absolute file or directory path. Required for: list, read, write, delete, create_dir", required = false),
            SkillParam("content", "string", "Text content to write. Required for: write", required = false),
            SkillParam("destination", "string", "Destination path. Required for: copy, move", required = false),
            SkillParam("query", "string", "Filename search query. Required for: search", required = false),
            SkillParam("search_root", "string", "Root directory for search. Optional for: search (defaults to Downloads)", required = false),
        ),
        type = SkillType.NATIVE,
        injectionLevel = 1,
        categories = listOf(SkillToolCategory.ARTIFACT, SkillToolCategory.SYSTEM),
        tags = listOf("User"),
    )

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!storage.hasAllFilesAccess()) {
            return SkillResult(
                false,
                "❌ File access permission was not granted. Ask the user to open Settings → Special access → All files access and authorize this app," +
                    "or tap Grant file access on the app settings page.",
            )
        }

        val action = params["action"] as? String ?: return SkillResult(false, "action is required")

        return when (action) {
            "list_dirs" -> {
                val dirs = storage.wellKnownDirs()
                val sb = StringBuilder("📁 Available storage directories: \n\n")
                dirs.forEach { sb.append("${it.emoji} ${it.name}\n   Path: ${it.path}\n\n") }
                SkillResult(true, sb.toString().trimEnd())
            }

            "list" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                storage.listDir(path).fold(
                    onSuccess = { entries ->
                        if (entries.isEmpty()) return SkillResult(true, "Directory is empty: $path")
                        val sb = StringBuilder("📂 $path (${entries.size} items)\n\n")
                        entries.forEach { e ->
                            val icon = if (e.isDirectory) "📁" else "📄"
                            val size = if (e.isDirectory) "Directory" else formatSize(e.sizeBytes)
                            val date = sdf.format(Date(e.lastModified))
                            sb.append("$icon ${e.name}\n   $size · $date\n   ${e.path}\n\n")
                        }
                        SkillResult(true, sb.toString().trimEnd())
                    },
                    onFailure = { SkillResult(false, it.message ?: "Unable to list directory") },
                )
            }

            "read" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                storage.readFile(path).fold(
                    onSuccess = { content -> SkillResult(true, content) },
                    onFailure = { SkillResult(false, it.message ?: "Unable to read file") },
                )
            }

            "write" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                val content = params["content"] as? String ?: return SkillResult(false, "content is required")
                storage.writeFile(path, content).fold(
                    onSuccess = { SkillResult(true, "✅ File written: $path") },
                    onFailure = { SkillResult(false, it.message ?: "Unable to write file") },
                )
            }

            "delete" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                storage.deleteFile(path).fold(
                    onSuccess = { deleted -> SkillResult(deleted, if (deleted) "✅ Deleted: $path" else "File does not exist: $path") },
                    onFailure = { SkillResult(false, it.message ?: "Delete failed") },
                )
            }

            "copy" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                val dest = params["destination"] as? String ?: return SkillResult(false, "destination is required")
                storage.copyFile(path, dest).fold(
                    onSuccess = { SkillResult(true, "✅ Copied: $path → $dest") },
                    onFailure = { SkillResult(false, it.message ?: "Copy failed") },
                )
            }

            "move" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                val dest = params["destination"] as? String ?: return SkillResult(false, "destination is required")
                storage.moveFile(path, dest).fold(
                    onSuccess = { SkillResult(true, "✅ Moved: $path → $dest") },
                    onFailure = { SkillResult(false, it.message ?: "Move failed") },
                )
            }

            "search" -> {
                val query = params["query"] as? String ?: return SkillResult(false, "query is required")
                val root = params["search_root"] as? String
                    ?: storage.wellKnownDirs().firstOrNull { it.name.contains("Download") }?.path
                    ?: android.os.Environment.getExternalStorageDirectory().absolutePath
                storage.searchFiles(root, query).fold(
                    onSuccess = { entries ->
                        if (entries.isEmpty()) return SkillResult(true, "No files matched \"$query\" (search root: $root)")
                        val sb = StringBuilder("🔍 \"$query\" search results(${entries.size} items, from $root)\n\n")
                        entries.forEach { e ->
                            val icon = if (e.isDirectory) "📁" else "📄"
                            sb.append("$icon ${e.name}\n   ${e.path}\n\n")
                        }
                        SkillResult(true, sb.toString().trimEnd())
                    },
                    onFailure = { SkillResult(false, it.message ?: "Search failed") },
                )
            }

            "create_dir" -> {
                val path = params["path"] as? String ?: return SkillResult(false, "path is required")
                storage.createDir(path).fold(
                    onSuccess = { SkillResult(true, "✅ Directory created: $path") },
                    onFailure = { SkillResult(false, it.message ?: "Unable to create directory") },
                )
            }

            else -> SkillResult(false, "Unknown action: $action.Allowed values: list_dirs | list | read | write | delete | copy | move | search | create_dir")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${bytes / (1024 * 1024 * 1024)} GB"
    }
}
