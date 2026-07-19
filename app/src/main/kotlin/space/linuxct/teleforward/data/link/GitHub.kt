package space.linuxct.teleforward.data.link

/**
 * GitHub constants and the pure helpers used by the "magic link" reconstruction.
 *
 * Unlike the other services, GitHub needs no hidden id and no network: its notifications name the thing
 * they are about **in readable text**, as the familiar `owner/repo#123` reference. The url is then a
 * pure string build, because `github.com/<owner>/<repo>/issues/<n>` **302-redirects to `/pull/<n>`**
 * when that number is a pull request (and vice-versa) — verified to work anonymously, and even on repos
 * with issues disabled. So one form covers issues and PRs alike, with no API call and no need to know
 * which it is.
 *
 * Two deliberate refusals keep this from ever emitting a wrong link:
 *  - **Discussions.** `/discussions/<n>` is a *separate numbering namespace*; `/issues/<n>` will not
 *    redirect to it and may resolve to an unrelated issue that happens to share the number. Any text
 *    mentioning a discussion is therefore skipped entirely.
 *  - **Shape.** The reference must match GitHub's own owner/repo grammar, so ordinary prose containing
 *    a slash and a `#` can't be turned into a bogus repository url.
 *
 * All helpers are pure and unit-testable.
 */
object GitHub {

    /** The official GitHub mobile app. */
    val PACKAGES: Set<String> = setOf(
        "com.github.android",
    )

    const val SERVICE = "github"

    /**
     * An `owner/repo#number` reference. Owner: alphanumeric + hyphens, max 39 (GitHub's own limit).
     * Repo: alphanumeric plus `.`, `_`, `-`. Number: a bounded run of digits.
     */
    private val REFERENCE = Regex("""([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9._-]{1,100})#(\d{1,10})""")

    /** Any mention of a discussion disqualifies the text (see the class note). */
    private val DISCUSSION = Regex("discussion", RegexOption.IGNORE_CASE)

    /**
     * Pure: the canonical url for the first `owner/repo#n` reference in [text], or null when there is
     * none — or when the text mentions a discussion, whose numbering does not share the issue/PR space.
     *
     * Always builds the `/issues/<n>` form: GitHub redirects it to `/pull/<n>` when the number is a pull
     * request, so this is correct for both without needing to disambiguate.
     */
    fun issueUrl(text: String?): String? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (DISCUSSION.containsMatchIn(value)) return null
        val match = REFERENCE.find(value) ?: return null
        val (owner, repo, number) = match.destructured
        return "https://github.com/$owner/$repo/issues/$number"
    }
}
