package space.linuxct.teleforward.data.telegram

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Shared JSON + error-envelope parsing for the Telegram data layer (Wave 1 helper).
 *
 * Retrofit only parses the response body on 2xx; on 4xx/5xx the Telegram error envelope lands in
 * `errorBody()`. [parseTgError] extracts the fields the sender/pairing code needs (`error_code`,
 * `description`, `parameters.retry_after`) from that raw JSON without needing a typed serializer for
 * the (absent) `result` payload.
 */
internal val telegramJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/** Flattened Telegram error envelope. */
internal data class TgError(
    val code: Int?,
    val description: String?,
    val retryAfter: Long?,
)

/** Best-effort parse of a raw Telegram error body; never throws. */
internal fun parseTgError(raw: String?): TgError {
    if (raw.isNullOrBlank()) return TgError(null, null, null)
    return try {
        val obj = telegramJson.parseToJsonElement(raw).jsonObject
        val code = obj["error_code"]?.jsonPrimitive?.intOrNull
        val description = obj["description"]?.jsonPrimitive?.contentOrNull
        val retryAfter = obj["parameters"]?.jsonObject?.get("retry_after")?.jsonPrimitive?.longOrNull
        TgError(code, description, retryAfter)
    } catch (e: Exception) {
        TgError(null, null, null)
    }
}
