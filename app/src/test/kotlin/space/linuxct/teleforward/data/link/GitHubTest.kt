package space.linuxct.teleforward.data.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [GitHub.issueUrl] — parsing an `owner/repo#123` reference out of readable notification
 * text into a canonical url. Plain JUnit: pure string work, no Android, no network.
 */
class GitHubTest {

    @Test
    fun `extracts an owner repo number reference`() {
        assertEquals(
            "https://github.com/linuxct/teleforward/issues/42",
            GitHub.issueUrl("linuxct/teleforward#42"),
        )
    }

    @Test
    fun `finds the reference inside a sentence`() {
        assertEquals(
            "https://github.com/microsoft/vscode/issues/200000",
            GitHub.issueUrl("Someone commented on microsoft/vscode#200000 and mentioned you"),
        )
    }

    @Test
    fun `always builds the issues form since github redirects pull requests`() {
        // `/issues/<n>` 302s to `/pull/<n>` when the number is a PR, so we never need to disambiguate.
        assertEquals(
            "https://github.com/torvalds/linux/issues/7",
            GitHub.issueUrl("review requested on torvalds/linux#7"),
        )
    }

    @Test
    fun `handles dots underscores and hyphens in the repo name`() {
        assertEquals(
            "https://github.com/some-org/my_repo.js/issues/9",
            GitHub.issueUrl("some-org/my_repo.js#9"),
        )
    }

    @Test
    fun `refuses text mentioning a discussion`() {
        // Discussions have their own numbering; /issues/<n> would 404 or hit an unrelated issue.
        assertNull(GitHub.issueUrl("New discussion in linuxct/teleforward#12"))
        assertNull(GitHub.issueUrl("Someone replied to your Discussion on foo/bar#3"))
    }

    @Test
    fun `returns null when there is no reference`() {
        assertNull(GitHub.issueUrl(null))
        assertNull(GitHub.issueUrl(""))
        assertNull(GitHub.issueUrl("   "))
        assertNull(GitHub.issueUrl("Your build succeeded"))
        assertNull("needs the # number", GitHub.issueUrl("linuxct/teleforward"))
        assertNull("needs the owner", GitHub.issueUrl("teleforward#42"))
    }

    @Test
    fun `takes the first reference when several appear`() {
        assertEquals(
            "https://github.com/a/b/issues/1",
            GitHub.issueUrl("a/b#1 and c/d#2"),
        )
    }
}
