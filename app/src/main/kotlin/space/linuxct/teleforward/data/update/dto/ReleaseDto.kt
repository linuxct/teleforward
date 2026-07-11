package space.linuxct.teleforward.data.update.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of the GitHub "get the latest release" response we care about. The full payload carries
 * many more fields (author, assets, timestamps, …); the shared kotlinx [kotlinx.serialization.json.Json]
 * is configured with `ignoreUnknownKeys = true` so they're dropped.
 *
 * See: https://docs.github.com/en/rest/releases/releases#get-the-latest-release
 */
@Serializable
data class ReleaseDto(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
)
