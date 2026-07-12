package space.linuxct.teleforward.data.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import space.linuxct.teleforward.data.db.entity.OutboxWithImages
import space.linuxct.teleforward.data.telegram.dto.TelegramResponse
import space.linuxct.teleforward.data.telegram.dto.TgMessage
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes the [SendPlan] chosen by [messageBuilder] against the Telegram Bot API [api] and maps
 * each outcome onto a [SendResult]: 2xx+ok → Success (collecting message ids), 429 → RetryAfter,
 * 403 → Terminal, 400 → BadRequest, network/timeout/5xx → Transient (see plan T4).
 */
@Singleton
class TelegramSenderImpl @Inject constructor(
    private val api: TelegramApi,
    private val messageBuilder: MessageBuilder,
) : TelegramSender {

    override suspend fun send(item: OutboxWithImages, chatId: Long, extraLink: String?): SendResult {
        return when (val plan = messageBuilder.plan(item, extraLink)) {
            // Editable primary = the sendMessage itself; a later Link: line goes in its text.
            is SendPlan.TextOnly -> when (val text = sendText(chatId, plan.text)) {
                is SendResult.Success -> text.copy(
                    editableMessageId = text.messageIds.firstOrNull(),
                    editableIsCaption = false,
                    editableText = plan.text,
                )
                else -> text
            }

            // Editable primary = the photo; a later Link: line goes in its caption.
            is SendPlan.SinglePhoto -> when (val photo = sendPhoto(chatId, plan.imagePath, plan.caption)) {
                is SendResult.Success -> photo.copy(
                    editableMessageId = photo.messageIds.firstOrNull(),
                    editableIsCaption = true,
                    editableText = plan.caption,
                )
                else -> photo
            }

            is SendPlan.PhotoWithSeparateText -> {
                val ids = ArrayList<Long>()
                // Editable primary = the separate text message (it carries the body); capture its id.
                val editableId: Long? = when (val text = sendText(chatId, plan.text)) {
                    is SendResult.Success -> {
                        ids += text.messageIds
                        text.messageIds.firstOrNull()
                    }
                    else -> return text
                }
                when (val photo = sendPhoto(chatId, plan.imagePath, caption = "")) {
                    is SendResult.Success -> SendResult.Success(
                        ids + photo.messageIds,
                        editableMessageId = editableId,
                        editableIsCaption = false,
                        editableText = plan.text,
                    )
                    else -> photo
                }
            }

            is SendPlan.MediaGroup -> {
                val ids = ArrayList<Long>()
                val preceding = plan.precedingText
                if (!preceding.isNullOrEmpty()) {
                    when (val text = sendText(chatId, preceding)) {
                        is SendResult.Success -> ids += text.messageIds
                        else -> return text
                    }
                }
                // Editable primary = the first media item, which carries the caption.
                when (val group = sendMediaGroup(chatId, plan.imagePaths, plan.caption)) {
                    is SendResult.Success -> SendResult.Success(
                        ids + group.messageIds,
                        editableMessageId = group.messageIds.firstOrNull(),
                        editableIsCaption = true,
                        editableText = plan.caption,
                    )
                    else -> group
                }
            }

            is SendPlan.MediaGroupBatched -> {
                val ids = ArrayList<Long>()
                val preceding = plan.precedingText
                if (!preceding.isNullOrEmpty()) {
                    when (val text = sendText(chatId, preceding)) {
                        is SendResult.Success -> ids += text.messageIds
                        else -> return text
                    }
                }
                // Send every batch in this call; the caption rides the first item of the first
                // batch (which is also the editable primary). A trailing batch of one image can't be a
                // media group, so it falls back to sendPhoto.
                var editableId: Long? = null
                plan.batches.forEachIndexed { index, batch ->
                    if (batch.isEmpty()) return@forEachIndexed
                    val caption = if (index == 0) plan.caption else ""
                    val result = if (batch.size == 1) {
                        sendPhoto(chatId, batch[0], caption)
                    } else {
                        sendMediaGroup(chatId, batch, caption)
                    }
                    when (result) {
                        is SendResult.Success -> {
                            if (index == 0) editableId = result.messageIds.firstOrNull()
                            ids += result.messageIds
                        }
                        else -> return result
                    }
                }
                SendResult.Success(
                    ids,
                    editableMessageId = editableId,
                    editableIsCaption = true,
                    editableText = plan.caption,
                )
            }
        }
    }

    override suspend fun sendTestMessage(chatId: Long, text: String): SendResult =
        sendText(chatId, text)

    override suspend fun editAppendLink(
        chatId: Long,
        messageId: Long,
        isCaption: Boolean,
        currentText: String,
        url: String,
    ): SendResult {
        val newText = messageBuilder.appendLink(currentText, url, isCaption)
        return try {
            val response = if (isCaption) {
                api.editMessageCaption(
                    chatId = chatId,
                    messageId = messageId,
                    caption = newText,
                    parseMode = HTML,
                )
            } else {
                api.editMessageText(
                    chatId = chatId,
                    messageId = messageId,
                    text = newText,
                    parseMode = HTML,
                )
            }
            mapEditResponse(response)
        } catch (e: IOException) {
            SendResult.Transient(e.message ?: "network error")
        } catch (e: Exception) {
            SendResult.Transient(e.message ?: "unexpected error")
        }
    }

    private suspend fun sendText(chatId: Long, text: String): SendResult = try {
        mapResponse(api.sendMessage(chatId = chatId, text = text, parseMode = HTML)) { env ->
            env.result?.let { listOf(it.messageId) } ?: emptyList()
        }
    } catch (e: IOException) {
        SendResult.Transient(e.message ?: "network error")
    } catch (e: Exception) {
        SendResult.Transient(e.message ?: "unexpected error")
    }

    private suspend fun sendPhoto(chatId: Long, imagePath: String, caption: String): SendResult {
        val file = File(imagePath)
        if (!file.exists()) return SendResult.Terminal("image file missing: $imagePath")
        return try {
            val chatBody = chatId.toString().toRequestBody(TEXT_PLAIN)
            val photoPart = MultipartBody.Part.createFormData(
                "photo",
                file.name,
                file.asRequestBody(mediaTypeFor(file)),
            )
            val captionBody = caption.takeIf { it.isNotEmpty() }?.toRequestBody(TEXT_PLAIN)
            val parseModeBody = if (caption.isNotEmpty()) HTML.toRequestBody(TEXT_PLAIN) else null
            mapResponse(
                api.sendPhoto(
                    chatId = chatBody,
                    photo = photoPart,
                    caption = captionBody,
                    parseMode = parseModeBody,
                ),
            ) { env -> env.result?.let { listOf(it.messageId) } ?: emptyList() }
        } catch (e: IOException) {
            SendResult.Transient(e.message ?: "network error")
        } catch (e: Exception) {
            SendResult.Transient(e.message ?: "unexpected error")
        }
    }

    private suspend fun sendMediaGroup(
        chatId: Long,
        imagePaths: List<String>,
        caption: String,
    ): SendResult {
        val files = imagePaths.map(::File)
        files.firstOrNull { !it.exists() }?.let {
            return SendResult.Terminal("image file missing: ${it.path}")
        }
        return try {
            val chatBody = chatId.toString().toRequestBody(TEXT_PLAIN)
            val fileParts = ArrayList<MultipartBody.Part>(files.size)
            val media = files.mapIndexed { index, file ->
                val attachName = "photo$index"
                fileParts += MultipartBody.Part.createFormData(
                    attachName,
                    file.name,
                    file.asRequestBody(mediaTypeFor(file)),
                )
                InputMediaPhoto(
                    type = "photo",
                    media = "attach://$attachName",
                    caption = if (index == 0 && caption.isNotEmpty()) caption else null,
                    parseMode = if (index == 0 && caption.isNotEmpty()) HTML else null,
                )
            }
            val mediaJson = telegramJson
                .encodeToString(ListSerializer(InputMediaPhoto.serializer()), media)
                .toRequestBody(APPLICATION_JSON)
            mapResponse(
                api.sendMediaGroup(chatId = chatBody, media = mediaJson, files = fileParts),
            ) { env -> env.result?.map { it.messageId } ?: emptyList() }
        } catch (e: IOException) {
            SendResult.Transient(e.message ?: "network error")
        } catch (e: Exception) {
            SendResult.Transient(e.message ?: "unexpected error")
        }
    }

    private fun <T> mapResponse(
        response: Response<TelegramResponse<T>>,
        idsOf: (TelegramResponse<T>) -> List<Long>,
    ): SendResult {
        if (response.isSuccessful) {
            val env = response.body()
            if (env != null && env.ok) return SendResult.Success(idsOf(env))
            val error = if (env != null) {
                TgError(env.errorCode, env.description, env.parameters?.retryAfter)
            } else {
                TgError(null, null, null)
            }
            return classify(response.code(), error)
        }
        return classify(response.code(), parseTgError(response.errorBody()?.string()))
    }

    private fun classify(httpCode: Int, error: TgError): SendResult {
        val code = error.code ?: httpCode
        val description = error.description ?: "HTTP $httpCode"
        return when {
            code == 429 || httpCode == 429 ->
                SendResult.RetryAfter(error.retryAfter ?: DEFAULT_RETRY_AFTER_SECONDS)
            code == 403 || httpCode == 403 -> SendResult.Terminal(description)
            code == 400 || httpCode == 400 -> SendResult.BadRequest(description)
            httpCode in 500..599 || code in 500..599 -> SendResult.Transient(description)
            else -> SendResult.Terminal(description)
        }
    }

    /** Map an edit (editMessageText/Caption) response onto a [SendResult] via [classifyEdit]. */
    private fun mapEditResponse(response: Response<TelegramResponse<TgMessage>>): SendResult {
        if (response.isSuccessful) {
            val env = response.body()
            if (env != null && env.ok) {
                return SendResult.Success(env.result?.let { listOf(it.messageId) } ?: emptyList())
            }
            val error = if (env != null) {
                TgError(env.errorCode, env.description, env.parameters?.retryAfter)
            } else {
                TgError(null, null, null)
            }
            return classifyEdit(response.code(), error)
        }
        return classifyEdit(response.code(), parseTgError(response.errorBody()?.string()))
    }

    /**
     * Edit-specific classification. Differs from [classify] on the 400 family: a "message is not
     * modified" reply means the append is already applied (treat as done → Success), while
     * "message to edit not found" / "MESSAGE_ID_INVALID" / "can't be edited" are unrecoverable
     * (Terminal — give up on the row). Any other 400 is a [SendResult.BadRequest].
     */
    private fun classifyEdit(httpCode: Int, error: TgError): SendResult {
        val code = error.code ?: httpCode
        val description = error.description ?: "HTTP $httpCode"
        val lower = description.lowercase()
        return when {
            code == 429 || httpCode == 429 ->
                SendResult.RetryAfter(error.retryAfter ?: DEFAULT_RETRY_AFTER_SECONDS)
            lower.contains("message is not modified") -> SendResult.Success(emptyList())
            lower.contains("message to edit not found") ||
                lower.contains("message_id_invalid") ||
                lower.contains("can't be edited") -> SendResult.Terminal(description)
            code == 400 || httpCode == 400 -> SendResult.BadRequest(description)
            code == 403 || httpCode == 403 -> SendResult.Terminal(description)
            httpCode in 500..599 || code in 500..599 -> SendResult.Transient(description)
            else -> SendResult.Terminal(description)
        }
    }

    private fun mediaTypeFor(file: File): MediaType = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }.toMediaType()

    private companion object {
        const val HTML = "HTML"
        const val DEFAULT_RETRY_AFTER_SECONDS = 30L
        val TEXT_PLAIN: MediaType = "text/plain".toMediaType()
        val APPLICATION_JSON: MediaType = "application/json".toMediaType()
    }
}

/** Minimal `InputMediaPhoto` for a `sendMediaGroup` `media` array; files referenced via `attach://`. */
@Serializable
private data class InputMediaPhoto(
    val type: String,
    val media: String,
    val caption: String? = null,
    @SerialName("parse_mode") val parseMode: String? = null,
)
