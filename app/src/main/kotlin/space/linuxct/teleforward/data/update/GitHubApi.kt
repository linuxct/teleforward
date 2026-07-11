package space.linuxct.teleforward.data.update

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import space.linuxct.teleforward.data.update.dto.ReleaseDto

/**
 * Retrofit service for the (unauthenticated) GitHub REST API, base [BASE_URL].
 *
 * Only the "latest release" endpoint is needed. A raw Retrofit [Response] is returned so callers
 * can distinguish a real `404` (the repo has no published releases yet) from a transport failure.
 * The `User-Agent` (required by GitHub) and `Accept: application/vnd.github+json` headers are added
 * by the qualified OkHttp client (see `di/GitHubModule`).
 */
interface GitHubApi {

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
    ): Response<ReleaseDto>

    companion object {
        const val BASE_URL = "https://api.github.com/"
        const val OWNER = "linuxct"
        const val REPO = "teleforward"
    }
}
