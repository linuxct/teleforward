package space.linuxct.teleforward.data.update

/**
 * Outcome of a single update check against the upstream GitHub repo.
 *
 * - [UpToDate] — the installed [current] build is the latest (also reported when the repo has no
 *   published releases yet, i.e. GitHub `404`).
 * - [Available] — a strictly newer, non-draft, non-prerelease release exists.
 * - [Failed] — the check couldn't complete (network error, unexpected HTTP status, …).
 */
sealed interface UpdateResult {
    data class UpToDate(val current: String) : UpdateResult
    data class Available(
        val current: String,
        val latest: String,
        val releaseUrl: String,
        val name: String? = null,
        val notes: String? = null,
    ) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

/** Checks the upstream GitHub repo for a newer release than the installed build. */
interface UpdateRepository {
    suspend fun check(): UpdateResult
}
