package com.mobileclaw.perception

import android.content.Context
import android.content.pm.PackageManager
import java.util.Locale

data class LaunchableApp(
    val packageName: String,
    val displayName: String,
)

sealed interface AppResolution {
    data class Resolved(val app: LaunchableApp) : AppResolution
    data class Ambiguous(val query: String, val candidates: List<LaunchableApp>) : AppResolution
    data class NotInstalled(val query: String) : AppResolution
    data class InvalidPackage(val packageName: String) : AppResolution
}

fun interface LaunchableAppProvider {
    fun listLaunchableApps(): List<LaunchableApp>
}

class AndroidLaunchableAppProvider(private val context: Context) : LaunchableAppProvider {
    override fun listLaunchableApps(): List<LaunchableApp> {
        val packageManager = context.packageManager
        return packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { LaunchableApp(it.packageName, it.loadLabel(packageManager).toString()) }
            .distinctBy { it.packageName }
            .sortedWith(LaunchableAppOrdering)
            .toList()
    }
}

class InstalledAppCatalog(
    private val provider: LaunchableAppProvider,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var cached: List<LaunchableApp>? = null
    private var loadedAtMs = 0L

    fun apps(forceRefresh: Boolean = false): List<LaunchableApp> = synchronized(lock) {
        val now = nowMs()
        val current = cached
        if (!forceRefresh && current != null && now - loadedAtMs < ttlMs) return@synchronized current
        provider.listLaunchableApps().sortedWith(LaunchableAppOrdering).also {
            cached = it
            loadedAtMs = now
        }
    }

    fun refresh(): List<LaunchableApp> = apps(forceRefresh = true)

    companion object {
        const val DEFAULT_TTL_MS = 2 * 60 * 1000L
    }
}

class InstalledAppResolver(private val catalog: InstalledAppCatalog) {
    fun resolvePackage(packageName: String): AppResolution {
        val query = packageName.trim()
        if (query.isEmpty()) return AppResolution.InvalidPackage(packageName)
        findPackage(catalog.apps(), query)?.let { return AppResolution.Resolved(it) }
        return findPackage(catalog.refresh(), query)?.let { AppResolution.Resolved(it) }
            ?: AppResolution.InvalidPackage(query)
    }

    fun resolveName(appName: String): AppResolution {
        val query = appName.trim()
        if (query.isEmpty()) return AppResolution.NotInstalled(appName)
        resolveNameIn(catalog.apps(), query).let { if (it !is AppResolution.NotInstalled) return it }
        return resolveNameIn(catalog.refresh(), query)
    }

    private fun findPackage(apps: List<LaunchableApp>, packageName: String) =
        apps.firstOrNull { it.packageName == packageName }

    private fun resolveNameIn(apps: List<LaunchableApp>, query: String): AppResolution {
        val normalizedQuery = normalizeAppLabel(query)
        if (normalizedQuery.isEmpty()) return AppResolution.NotInstalled(query)
        val exact = apps.filter { normalizeAppLabel(it.displayName) == normalizedQuery }.sortedWith(LaunchableAppOrdering)
        if (exact.size == 1) return AppResolution.Resolved(exact.single())
        if (exact.size > 1) return AppResolution.Ambiguous(query, exact)

        val partial = apps.filter {
            val label = normalizeAppLabel(it.displayName)
            label.contains(normalizedQuery) || normalizedQuery.contains(label)
        }.sortedWith(LaunchableAppOrdering)
        return when (partial.size) {
            1 -> AppResolution.Resolved(partial.single())
            0 -> AppResolution.NotInstalled(query)
            else -> AppResolution.Ambiguous(query, partial)
        }
    }
}

internal val LaunchableAppOrdering = compareBy<LaunchableApp>(
    { normalizeAppLabel(it.displayName) },
    { it.packageName },
)

/** Preserves Unicode and only treats whitespace plus common separator punctuation as equivalent. */
internal fun normalizeAppLabel(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .replace(Regex("[\\s._·-]+"), " ")
    .replace(Regex(" +"), " ")
