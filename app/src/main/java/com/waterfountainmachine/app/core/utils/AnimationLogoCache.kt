package com.waterfountainmachine.app.core.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Tiny on-disk cache for advertiser animation logos.
 *
 * The vend animation runs on a fixed timeline, so downloading the brand logo
 * lazily at vend time races the reveal (and re-downloads the same image every
 * vend). This caches each logo to [Context.getCacheDir] keyed by a hash of its
 * URL, so:
 *   - the kiosk can PREFETCH all current slot logos right after inventory sync
 *     ([prefetch]), and
 *   - the vend path reads the bitmap from disk instantly ([getBitmap]).
 *
 * Dependency-free (HttpURLConnection + BitmapFactory) to match the rest of the
 * app — see AGENTS.md "engineered enough, not over-engineered". All methods do
 * blocking IO and MUST be called off the main thread.
 */
object AnimationLogoCache {
    private const val TAG = "AnimationLogoCache"
    private const val DIR = "anim_logos"
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 6000

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    private fun fileFor(context: Context, url: String): File =
        File(cacheDir(context), sha256(url))

    /** The cache file name (sha-256 hex) used for [url]. */
    private fun keyFor(url: String): String = sha256(url)

    private fun sha256(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** True if [url] is already fully cached on disk. */
    fun isCached(context: Context, url: String): Boolean =
        fileFor(context, url).let { it.exists() && it.length() > 0 }

    /**
     * Download [url] into the cache if not already present. Writes to a `.tmp`
     * file first and renames on completion so a partial/interrupted download is
     * never observed as a complete cache entry. Returns true if the bytes are
     * on disk afterward.
     */
    fun ensureCached(context: Context, url: String): Boolean {
        val file = fileFor(context, url)
        if (file.exists() && file.length() > 0) return true
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() > 0 && tmp.renameTo(file)) {
                true
            } else {
                tmp.delete()
                false
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to cache logo from $url: ${e.message}")
            false
        }
    }

    /**
     * Cache-first bitmap load: ensures [url] is cached, then decodes it from
     * disk. Returns null if the download or decode fails (callers fall back to
     * the bundled WaterFountain logo).
     */
    fun getBitmap(context: Context, url: String): Bitmap? {
        if (!ensureCached(context, url)) return null
        return try {
            BitmapFactory.decodeFile(fileFor(context, url).absolutePath)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to decode cached logo from $url: ${e.message}")
            null
        }
    }

    /**
     * Make the on-disk cache match the current inventory: download any missing
     * logos, then prune everything else.
     *
     * Pruning keeps the cache bounded and healthy. Because an updated can
     * design re-uploads with a fresh download token, its logo URL changes ->
     * new cache key -> the OLD file is no longer referenced and is pruned here
     * (so a stale logo is never served). Rotated-out designs are pruned the
     * same way. Blank URLs are ignored. Best-effort; blocking IO.
     */
    fun syncToInventory(context: Context, urls: Collection<String>) {
        val keep = urls.filter { it.isNotBlank() }.distinct()

        // 1) Fetch anything missing.
        var fetched = 0
        for (url in keep) {
            if (!isCached(context, url) && ensureCached(context, url)) fetched++
        }

        // 2) Prune anything not in the keep-set (stale versions, rotated-out
        //    designs, and any leftover *.tmp from an interrupted download).
        val keepNames = keep.map { keyFor(it) }.toHashSet()
        var pruned = 0
        cacheDir(context).listFiles()?.forEach { file ->
            if (file.name !in keepNames) {
                if (file.delete()) pruned++
            }
        }

        AppLog.i(TAG, "Logo cache synced: keep=${keep.size}, fetched=$fetched, pruned=$pruned")
    }
}
