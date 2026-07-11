package space.linuxct.teleforward.data.update

import space.linuxct.teleforward.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Default [UpdateRepository] backed by the unauthenticated GitHub REST API.
 *
 * Compares [BuildConfig.VERSION_NAME] against the repo's latest release tag:
 * - HTTP `404` → [UpdateResult.UpToDate] (the repo has no releases yet).
 * - success + a non-draft, non-prerelease release → [isNewer] decides Available vs UpToDate.
 * - any other status / thrown exception → [UpdateResult.Failed].
 */
@Singleton
class UpdateRepositoryImpl @Inject constructor(
    private val gitHubApi: GitHubApi,
) : UpdateRepository {

    override suspend fun check(): UpdateResult {
        val current = BuildConfig.VERSION_NAME
        return try {
            val response = gitHubApi.latestRelease(GitHubApi.OWNER, GitHubApi.REPO)
            when {
                // No published releases yet: treat as up to date rather than an error.
                response.code() == HTTP_NOT_FOUND -> UpdateResult.UpToDate(current)

                response.isSuccessful -> {
                    val release = response.body()
                        ?: return UpdateResult.Failed("Empty response from GitHub")
                    val ignore = release.draft || release.prerelease
                    if (!ignore && isNewer(release.tagName, current)) {
                        UpdateResult.Available(
                            current = current,
                            latest = normalizeVersion(release.tagName),
                            releaseUrl = release.htmlUrl,
                            name = release.name,
                            notes = release.body,
                        )
                    } else {
                        UpdateResult.UpToDate(current)
                    }
                }

                else -> UpdateResult.Failed("GitHub returned HTTP ${response.code()}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            UpdateResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
    }
}
