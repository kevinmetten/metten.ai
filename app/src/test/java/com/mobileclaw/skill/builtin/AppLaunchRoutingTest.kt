package com.mobileclaw.skill.builtin

import com.mobileclaw.perception.InstalledAppCatalog
import com.mobileclaw.perception.InstalledAppResolver
import com.mobileclaw.perception.LaunchableApp
import com.mobileclaw.perception.LaunchableAppProvider
import com.mobileclaw.skill.SkillResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchRoutingTest {
    private val resolver = InstalledAppResolver(InstalledAppCatalog(LaunchableAppProvider {
        listOf(
            LaunchableApp("spotify.pkg", "Spotify"),
            LaunchableApp("music.a", "Alpha Music"),
            LaunchableApp("music.b", "Beta Music"),
        )
    }))

    @Test fun `package only remains compatible and package wins over app name`() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val result = AppLaunchRouting.execute(
            mapOf("action" to "launch", "package_name" to "spotify.pkg", "app_name" to "Missing", "foreground" to true),
            resolver,
        ) { pkg, foreground -> calls += pkg to foreground; SkillResult(true, "ok") }
        assertTrue(result!!.success)
        assertEquals(listOf("spotify.pkg" to true), calls)
    }

    @Test fun `app name resolves and preserves foreground flag`() = runBlocking {
        var launched: Pair<String, Boolean>? = null
        AppLaunchRouting.execute(mapOf("action" to "launch", "app_name" to "Spotify"), resolver) { pkg, foreground ->
            launched = pkg to foreground
            SkillResult(true, "ok")
        }
        assertEquals("spotify.pkg" to false, launched)
    }

    @Test fun `missing and ambiguous names never invoke launch`() = runBlocking {
        var calls = 0
        val launch: suspend (String, Boolean) -> SkillResult = { _, _ -> calls++; SkillResult(true, "bad") }
        assertFalse(AppLaunchRouting.execute(mapOf("action" to "launch"), resolver, launch)!!.success)
        assertFalse(AppLaunchRouting.execute(mapOf("action" to "launch", "app_name" to "Music"), resolver, launch)!!.success)
        assertFalse(AppLaunchRouting.execute(mapOf("action" to "launch", "app_name" to "Missing"), resolver, launch)!!.success)
        assertEquals(0, calls)
    }

    @Test fun `non launch action does not require app arguments`() = runBlocking {
        assertNull(AppLaunchRouting.execute(mapOf("action" to "home"), resolver) { _, _ -> SkillResult(true, "bad") })
    }

    @Test fun `longer missing app name never launches shorter installed app`() = runBlocking {
        val googleOnlyResolver = InstalledAppResolver(InstalledAppCatalog(LaunchableAppProvider {
            listOf(LaunchableApp("google.pkg", "Google"))
        }))
        var launchCalls = 0

        val result = AppLaunchRouting.execute(
            mapOf("action" to "launch", "app_name" to "Google Maps"),
            googleOnlyResolver,
        ) { _, _ -> launchCalls++; SkillResult(true, "unexpected") }

        assertFalse(result!!.success)
        assertEquals(0, launchCalls)
    }
}
