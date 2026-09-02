package com.mobileclaw.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledAppResolverTest {
    @Test fun `explicit package exact match is valid`() {
        assertResolved(
            resolver(app("Spotify", "com.spotify.music")).resolvePackage("com.spotify.music"),
            packageName = "com.spotify.music",
        )
    }

    @Test fun `invalid or non-launchable explicit package is rejected`() {
        val result = resolver(app("Visible", "visible.pkg")).resolvePackage("service.only.pkg")
        assertTrue(result is AppResolution.InvalidPackage)
    }

    @Test fun `exact case and repeated whitespace labels normalize`() {
        val resolver = resolver(app("My   Music", "music.pkg"))
        assertResolved(resolver.resolveName("my music"), "music.pkg")
        assertResolved(resolver.resolveName("MY MUSIC"), "music.pkg")
    }

    @Test fun `common separator punctuation normalizes conservatively`() {
        assertResolved(resolver(app("Acme-Music.Player", "acme.pkg")).resolveName("acme music player"), "acme.pkg")
    }

    @Test fun `arbitrary Unicode labels are preserved and matched`() {
        assertResolved(resolver(app("音乐 🎧", "unicode.pkg")).resolveName("音乐 🎧"), "unicode.pkg")
    }

    @Test fun `unique partial label resolves`() {
        assertResolved(resolver(app("Google Maps", "maps.pkg"), app("Calendar", "cal.pkg")).resolveName("Maps"), "maps.pkg")
    }

    @Test fun `longer requested identity does not collapse to shorter installed label`() {
        val result = resolver(app("Google", "google.pkg")).resolveName("Google Maps")
        assertTrue(result is AppResolution.NotInstalled)
    }

    @Test fun `arbitrary internal substring is not a partial label match`() {
        val result = resolver(app("YouTube", "youtube.pkg"), app("Roadmap", "roadmap.pkg")).resolveName("Tube")
        assertTrue(result is AppResolution.NotInstalled)
    }

    @Test fun `ambiguous partial label is stable regardless of provider order`() {
        val apps = listOf(app("YouTube Music", "yt.pkg"), app("Amazon Music", "amazon.pkg"), app("Samsung Music", "samsung.pkg"))
        val first = resolver(*apps.toTypedArray()).resolveName("Music") as AppResolution.Ambiguous
        val second = resolver(*apps.reversed().toTypedArray()).resolveName("Music") as AppResolution.Ambiguous
        val expected = listOf("amazon.pkg", "samsung.pkg", "yt.pkg")
        assertEquals(expected, first.candidates.map { it.packageName })
        assertEquals(expected, second.candidates.map { it.packageName })
    }

    @Test fun `duplicate exact labels are ambiguous`() {
        val result = resolver(app("Notes", "b.pkg"), app("Notes", "a.pkg")).resolveName("notes") as AppResolution.Ambiguous
        assertEquals(listOf("a.pkg", "b.pkg"), result.candidates.map { it.packageName })
    }

    @Test fun `no match refreshes only once`() {
        val provider = MutableProvider(listOf(app("A", "a.pkg")))
        val resolver = InstalledAppResolver(InstalledAppCatalog(provider))
        assertTrue(resolver.resolveName("Missing") is AppResolution.NotInstalled)
        assertEquals(2, provider.calls)
    }

    @Test fun `fresh cache hit does not enumerate repeatedly`() {
        val provider = MutableProvider(listOf(app("A", "a.pkg")))
        val resolver = InstalledAppResolver(InstalledAppCatalog(provider))
        assertResolved(resolver.resolveName("A"), "a.pkg")
        assertResolved(resolver.resolveName("A"), "a.pkg")
        assertEquals(1, provider.calls)
    }

    @Test fun `refresh on miss discovers newly installed app`() {
        val provider = MutableProvider(listOf(app("A", "a.pkg")))
        val resolver = InstalledAppResolver(InstalledAppCatalog(provider))
        assertResolved(resolver.resolveName("A"), "a.pkg")
        provider.apps = listOf(app("A", "a.pkg"), app("B", "b.pkg"))
        assertResolved(resolver.resolveName("B"), "b.pkg")
        assertEquals(2, provider.calls)
    }

    @Test fun `stale shorter label does not suppress refresh for newly installed full label`() {
        val provider = MutableProvider(listOf(app("Google", "google.pkg")))
        val resolver = InstalledAppResolver(InstalledAppCatalog(provider))
        assertResolved(resolver.resolveName("Google"), "google.pkg")
        provider.apps = listOf(app("Google", "google.pkg"), app("Google Maps", "maps.pkg"))

        assertResolved(resolver.resolveName("Google Maps"), "maps.pkg")
        assertEquals(2, provider.calls)
    }

    @Test fun `force refresh updates catalog and injectable clock expires TTL`() {
        var now = 10L
        val provider = MutableProvider(listOf(app("A", "a.pkg")))
        val catalog = InstalledAppCatalog(provider, ttlMs = 100, nowMs = { now })
        assertEquals(listOf("a.pkg"), catalog.apps().map { it.packageName })
        provider.apps = listOf(app("B", "b.pkg"))
        assertEquals(listOf("b.pkg"), catalog.refresh().map { it.packageName })
        provider.apps = listOf(app("C", "c.pkg"))
        now += 101
        assertEquals(listOf("c.pkg"), catalog.apps().map { it.packageName })
        assertEquals(3, provider.calls)
    }

    private fun resolver(vararg apps: LaunchableApp) = InstalledAppResolver(
        InstalledAppCatalog(LaunchableAppProvider { apps.toList() }),
    )
    private fun app(name: String, pkg: String) = LaunchableApp(pkg, name)
    private fun assertResolved(result: AppResolution, packageName: String) =
        assertEquals(packageName, (result as AppResolution.Resolved).app.packageName)

    private class MutableProvider(var apps: List<LaunchableApp>) : LaunchableAppProvider {
        var calls = 0
        override fun listLaunchableApps(): List<LaunchableApp> = apps.also { calls++ }
    }
}
