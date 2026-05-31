package com.gpssimulator.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Polls GitHub Releases for a newer version of this APK and surfaces it
 * to the UI. No-ops silently on any failure (network, rate limit, parse,
 * blank repo, no APK asset) so the check never blocks the app.
 *
 * Throttled to once per 12h via SharedPreferences. The user can dismiss a
 * version; subsequent checks suppress that exact version until a newer
 * one appears.
 */
object UpdateChecker {
    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_VERSION = "dismissed_version"
    private const val THROTTLE_MS = 12L * 60 * 60 * 1000

    data class LatestRelease(
        val version: String,
        val name: String,
        val notes: String,
        val downloadUrl: String,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Returns a release iff the repo is configured, the throttle window has
     * elapsed, the remote tag is strictly newer than BuildConfig.VERSION_NAME,
     * the user hasn't dismissed that exact version, and the release has an
     * .apk asset. Returns null otherwise.
     */
    suspend fun checkOnce(context: Context): LatestRelease? {
        val repo = BuildConfig.GITHUB_REPO.trim()
        if (repo.isBlank()) return null

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        if (now - last < THROTTLE_MS) return null

        return try {
            val release = withContext(Dispatchers.IO) { fetchLatest(repo) }
            prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
            if (release == null) return null
            if (!isNewer(BuildConfig.VERSION_NAME, release.version)) return null
            val dismissed = prefs.getString(KEY_DISMISSED_VERSION, null)
            if (dismissed == release.version) return null
            release
        } catch (_: Exception) {
            null
        }
    }

    fun markDismissed(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply()
    }

    private fun fetchLatest(repo: String): LatestRelease? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val version = stripV(tag)
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: tag
            val notes = json.optString("body", "")
            val assets = json.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                    break
                }
            }
            val downloadUrl = url ?: return null
            return LatestRelease(version, name, notes, downloadUrl)
        }
    }

    private fun stripV(tag: String): String =
        if (tag.isNotEmpty() && (tag[0] == 'v' || tag[0] == 'V')) tag.substring(1) else tag

    /** Numeric-prefix semver compare. "0.5.1" > "0.5.0"; non-numeric suffixes are ignored. */
    fun isNewer(local: String, remote: String): Boolean {
        val l = parseVersion(local)
        val r = parseVersion(remote)
        val n = maxOf(l.size, r.size)
        for (i in 0 until n) {
            val li = l.getOrElse(i) { 0 }
            val ri = r.getOrElse(i) { 0 }
            if (ri > li) return true
            if (ri < li) return false
        }
        return false
    }

    private fun parseVersion(v: String): List<Int> {
        val parts = v.split('.', '-', '+')
        val out = mutableListOf<Int>()
        for (p in parts) {
            val n = p.toIntOrNull() ?: break
            out += n
        }
        return out
    }
}
