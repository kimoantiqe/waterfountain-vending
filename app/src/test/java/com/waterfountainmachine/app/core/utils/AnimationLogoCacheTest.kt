package com.waterfountainmachine.app.core.utils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Unit coverage for [AnimationLogoCache]'s cache-health (prune) behaviour.
 *
 * The fetch path needs the network, so it's exercised by manual/device runs;
 * here we pin the deterministic prune contract: after a sync, only logos in
 * the current inventory survive. This is what keeps the cache bounded and
 * guarantees a superseded logo (re-uploaded => new download token => new URL)
 * is never served from a stale file.
 *
 * No network is hit: seeded entries are already "cached" (files on disk), so
 * `isCached` short-circuits the download for the keep-set.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class AnimationLogoCacheTest {

    private lateinit var context: Application

    private fun cacheDir(): File = File(context.cacheDir, "anim_logos").apply { mkdirs() }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun seed(url: String) {
        File(cacheDir(), key(url)).writeText("seed")
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir().listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `syncToInventory prunes entries not in the current inventory`() {
        val keepA = "https://cdn.example.com/a.png"
        val keepB = "https://cdn.example.com/b.png"
        val rotatedOut = "https://cdn.example.com/old.png"
        // Simulate a superseded logo: same design, new upload token => new URL.
        val staleVersion = "https://cdn.example.com/a.png?token=OLD"

        seed(keepA)
        seed(keepB)
        seed(rotatedOut)
        seed(staleVersion)

        // Already-cached keep entries short-circuit the download (no network).
        AnimationLogoCache.syncToInventory(context, listOf(keepA, keepB))

        assertThat(File(cacheDir(), key(keepA)).exists()).isTrue()
        assertThat(File(cacheDir(), key(keepB)).exists()).isTrue()
        assertThat(File(cacheDir(), key(rotatedOut)).exists()).isFalse()
        assertThat(File(cacheDir(), key(staleVersion)).exists()).isFalse()
    }

    @Test
    fun `syncToInventory with empty inventory clears the cache`() {
        seed("https://cdn.example.com/a.png")
        seed("https://cdn.example.com/b.png")

        AnimationLogoCache.syncToInventory(context, emptyList())

        assertThat(cacheDir().listFiles()?.size ?: 0).isEqualTo(0)
    }

    @Test
    fun `syncToInventory ignores blank urls`() {
        val keep = "https://cdn.example.com/a.png"
        seed(keep)

        AnimationLogoCache.syncToInventory(context, listOf(keep, "", "   "))

        assertThat(File(cacheDir(), key(keep)).exists()).isTrue()
    }
}
