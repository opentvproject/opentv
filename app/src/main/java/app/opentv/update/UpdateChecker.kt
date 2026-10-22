/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.update

import app.opentv.core.ProjectInfo
import app.opentv.data.remote.GitHubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Checks whether a newer OpenTV release has been published, so a sideloaded install can
 * update itself.
 *
 * Sideloaded apps get no automatic updates — nothing on the device knows to look. Without
 * this, whoever installs v0.1 stays on v0.1 until they happen to hear there is a newer one
 * and go and fetch it by hand. The app store equivalents (Play, Amazon) push updates for
 * you; the Downloader-app install route we ship through does not, so the app has to do it.
 *
 * ## Design
 * - **Silent on failure.** No network, rate-limited, a 404 because the release does not exist
 *   yet — all of it returns "no update" and never surfaces an error. An update check is a
 *   nicety; it must never be a thing that breaks or nags at launch.
 * - **Compares the tag, not a build number.** GitHub gives us a tag like `v0.2.0`. We parse it
 *   as a dotted version and compare it to our own [currentVersionName]. A release whose tag is
 *   not strictly newer is ignored, so re-tagging or a same-version hotfix will not prompt.
 * - **Skips drafts and pre-releases**, which are not meant for the general audience.
 */
class UpdateChecker(
    private val http: OkHttpClient,
    private val currentVersionName: String,
    private val repoSlug: String = REPO_SLUG,
) {
    data class Update(
        val versionName: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val releaseUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the newer release, or null if we are current / could not tell. */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repoSlug/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "OpenTV")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null

                val release = json.decodeFromString<GitHubRelease>(body)
                if (release.draft || release.prerelease) return@withContext null

                val asset = release.apkAsset() ?: return@withContext null
                if (!isNewer(release.tagName, currentVersionName)) return@withContext null

                Update(
                    versionName = release.tagName.trimStart('v', 'V'),
                    title = release.name.ifBlank { release.tagName },
                    notes = cleanNotes(release.body),
                    apkUrl = asset.browserDownloadUrl,
                    apkSizeBytes = asset.size,
                    releaseUrl = release.htmlUrl,
                )
            }
        }.getOrNull()
    }

    companion object {
        /**
         * The project's GitHub location. The releases published there are the ones this build
         * updates from, so it has to be the repository of *this* build — not the project it was
         * forked from. See [app.opentv.core.ProjectInfo].
         */
        const val REPO_SLUG = ProjectInfo.REPO_SLUG

        /**
         * The GitHub release body is written for developers and has GitHub's auto-generated
         * commit list appended. Turn it into a few clean "what's new" lines for the update
         * dialog: drop the build/verify/signing boilerplate and the auto-changelog, and flatten
         * the Markdown that a plain dialog would otherwise show as literal `**`, `[x](y)`, `#`.
         * If nothing user-facing is left, returns empty and the dialog simply omits the notes.
         */
        fun cleanNotes(raw: String): String {
            var text = raw
            // Everything from the first boilerplate / auto-generated marker onward is noise.
            val cutFrom = listOf(
                "Built by GitHub Actions",
                "**Full Changelog**",
                "## What's Changed",
                "<!--",
            )
            for (marker in cutFrom) {
                val i = text.indexOf(marker, ignoreCase = true)
                if (i >= 0) text = text.substring(0, i)
            }
            text = text
                .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1") // [label](url) -> label
                .replace(Regex("""[*_]{1,3}([^*_\n]+)[*_]{1,3}"""), "$1") // **bold**/_em_ -> plain
                .replace("`", "")                                   // inline-code ticks
                .replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")    // ## headers -> text
                .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")        // "- item" -> "• item"
                .replace(Regex("""(?m)^\s*-{3,}\s*$"""), "")        // --- horizontal rules
            return text.lines().joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
                .take(1200)
        }

        /**
         * True when [tag] names a strictly higher version than [current].
         *
         * Both are reduced to lists of integers (`v0.2.0` → 0,2,0) and compared left to right.
         * A missing or non-numeric component counts as 0, so `0.2` and `0.2.0` are equal and a
         * malformed tag simply never wins.
         */
        fun isNewer(tag: String, current: String): Boolean {
            val a = versionParts(tag)
            val b = versionParts(current)
            val n = maxOf(a.size, b.size)
            for (i in 0 until n) {
                val ai = a.getOrElse(i) { 0 }
                val bi = b.getOrElse(i) { 0 }
                if (ai != bi) return ai > bi
            }
            return false
        }

        private fun versionParts(v: String): List<Int> =
            v.trim().trimStart('v', 'V')
                .split('.', '-', '+')
                .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
