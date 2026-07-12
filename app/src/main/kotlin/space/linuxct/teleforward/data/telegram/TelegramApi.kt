package space.linuxct.teleforward.data.telegram

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query
import space.linuxct.teleforward.data.telegram.dto.TelegramResponse
import space.linuxct.teleforward.data.telegram.dto.TgMessage
import space.linuxct.teleforward.data.telegram.dto.TgUpdate
import space.linuxct.teleforward.data.telegram.dto.TgUser

/**
 * Retrofit service for the Telegram Bot API.
 *
 * Base URL is [BASE_URL]. The method paths intentionally carry **no token**: a
 * `TokenPathInterceptor` (see [space.linuxct.teleforward.di]) reads the token from
 * [space.linuxct.teleforward.data.secret.SecretStore] and prepends the `bot<token>` path segment
 * at request time, so the token never appears in annotations or logs and rotates cleanly.
 *
 * All methods return a raw Retrofit [Response] so callers can inspect the HTTP status (e.g. 429
 * with `parameters.retry_after`, 403 recipient-unreachable) in addition to the parsed envelope.
 */
interface TelegramApi {

    @GET("getMe")
    suspend fun getMe(): Response<TelegramResponse<TgUser>>

    @FormUrlEncoded
    @POST("deleteWebhook")
    suspend fun deleteWebhook(
        @Field("drop_pending_updates") dropPendingUpdates: Boolean = false,
    ): Response<TelegramResponse<Boolean>>

    @GET("getUpdates")
    suspend fun getUpdates(
        @Query("offset") offset: Long?,
        @Query("timeout") timeout: Int = 0,
        @Query("limit") limit: Int = 100,
        @Query("allowed_updates") allowedUpdates: String = "[\"message\"]",
    ): Response<TelegramResponse<List<TgUpdate>>>

    @FormUrlEncoded
    @POST("sendMessage")
    suspend fun sendMessage(
        @Field("chat_id") chatId: Long,
        @Field("text") text: String,
        @Field("parse_mode") parseMode: String? = "HTML",
        @Field("disable_web_page_preview") disableWebPagePreview: Boolean = true,
    ): Response<TelegramResponse<TgMessage>>

    /**
     * Edit the text of a previously sent (non-media) message — used by the magic-link retry to append
     * a resolved `Link:` line after the fact. Returns the edited [TgMessage] on success.
     */
    @FormUrlEncoded
    @POST("editMessageText")
    suspend fun editMessageText(
        @Field("chat_id") chatId: Long,
        @Field("message_id") messageId: Long,
        @Field("text") text: String,
        @Field("parse_mode") parseMode: String? = "HTML",
        @Field("disable_web_page_preview") disableWebPagePreview: Boolean = true,
    ): Response<TelegramResponse<TgMessage>>

    /**
     * Edit the caption of a previously sent media message (photo / media group's first item) — the
     * media-carrying counterpart to [editMessageText] for the magic-link retry.
     */
    @FormUrlEncoded
    @POST("editMessageCaption")
    suspend fun editMessageCaption(
        @Field("chat_id") chatId: Long,
        @Field("message_id") messageId: Long,
        @Field("caption") caption: String,
        @Field("parse_mode") parseMode: String? = "HTML",
    ): Response<TelegramResponse<TgMessage>>

    @Multipart
    @POST("sendPhoto")
    suspend fun sendPhoto(
        @Part("chat_id") chatId: RequestBody,
        @Part photo: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null,
        @Part("parse_mode") parseMode: RequestBody? = null,
    ): Response<TelegramResponse<TgMessage>>

    /**
     * `media` is a JSON array of `InputMediaPhoto` objects whose `media` fields reference the
     * attached files via `attach://<name>`; [files] carries the matching file parts.
     */
    @Multipart
    @POST("sendMediaGroup")
    suspend fun sendMediaGroup(
        @Part("chat_id") chatId: RequestBody,
        @Part("media") media: RequestBody,
        @Part files: List<MultipartBody.Part>,
    ): Response<TelegramResponse<List<TgMessage>>>

    companion object {
        const val BASE_URL = "https://api.telegram.org/"
    }
}
