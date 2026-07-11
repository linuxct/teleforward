package space.linuxct.teleforward.data.telegram

import kotlinx.coroutines.flow.first
import space.linuxct.teleforward.data.secret.SecretStore
import space.linuxct.teleforward.data.settings.SettingsRepository
import space.linuxct.teleforward.data.telegram.dto.TgChat
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives bot-token validation and recipient pairing (plan T2) over [api]/[secretStore]/[settings].
 *
 * [validateToken] saves the token first (so [TokenPathInterceptor] can use it) then calls getMe.
 * [captureChatId] clears any webhook and polls getUpdates for the most recent private chat.
 * [sendTest] delivers a fixed message to the paired chat via [sender].
 */
@Singleton
class PairingRepositoryImpl @Inject constructor(
    private val api: TelegramApi,
    private val secretStore: SecretStore,
    private val settings: SettingsRepository,
    private val sender: TelegramSender,
) : PairingRepository {

    override suspend fun validateToken(token: String): TokenValidation {
        // Persist first so the OkHttp path interceptor rewrites the request with this token.
        secretStore.saveToken(token)
        return try {
            val response = api.getMe()
            val envelope = response.body()
            val user = envelope?.result
            if (response.isSuccessful && envelope != null && envelope.ok && user != null) {
                settings.setBotUsername(user.username)
                TokenValidation.Valid(botId = user.id, botUsername = user.username)
            } else {
                val error = parseTgError(response.errorBody()?.string())
                val code = envelope?.errorCode ?: error.code ?: response.code()
                // A clearly-invalid token (401 Unauthorized) should not linger in the store.
                if (code == 401) secretStore.clearToken()
                val reason = envelope?.description
                    ?: error.description
                    ?: "Invalid token (HTTP ${response.code()})"
                TokenValidation.Invalid(reason)
            }
        } catch (e: IOException) {
            TokenValidation.Invalid(e.message ?: "network error")
        } catch (e: Exception) {
            TokenValidation.Invalid(e.message ?: "unexpected error")
        }
    }

    override suspend fun captureChatId(): PairingResult {
        return try {
            api.deleteWebhook(dropPendingUpdates = false)
            val offset = settings.getUpdatesOffset.first()
            val response = api.getUpdates(offset = offset, timeout = 0)
            val envelope = response.body()
            if (!response.isSuccessful || envelope == null || !envelope.ok) {
                val error = parseTgError(response.errorBody()?.string())
                val message = envelope?.description
                    ?: error.description
                    ?: "getUpdates failed (HTTP ${response.code()})"
                return PairingResult.Error(message)
            }

            val updates = envelope.result.orEmpty()
            if (updates.isEmpty()) return PairingResult.NoUpdate

            // Acknowledge everything we fetched so the next poll starts after it.
            settings.setGetUpdatesOffset(updates.maxOf { it.updateId } + 1)

            val message = updates
                .mapNotNull { it.message }
                .filter { it.chat.type == "private" }
                .maxByOrNull { it.date }
                ?: return PairingResult.NoUpdate

            val chat = message.chat
            val displayName = displayNameOf(chat) ?: message.from?.username?.let { "@$it" }
            settings.setChatId(chat.id)
            settings.setChatDisplayName(displayName)
            PairingResult.Captured(chatId = chat.id, displayName = displayName)
        } catch (e: IOException) {
            PairingResult.Error(e.message ?: "network error")
        } catch (e: Exception) {
            PairingResult.Error(e.message ?: "unexpected error")
        }
    }

    override suspend fun setManualChatId(chatId: Long) {
        settings.setChatId(chatId)
        settings.setChatDisplayName(null)
    }

    override suspend fun sendTest(): SendResult {
        val chatId = settings.chatId.first() ?: return SendResult.Terminal("no recipient")
        return sender.sendTestMessage(chatId, "TeleForward test ✅")
    }

    private fun displayNameOf(chat: TgChat): String? {
        val name = listOfNotNull(chat.firstName, chat.lastName).joinToString(" ").trim()
        return when {
            name.isNotEmpty() -> name
            !chat.username.isNullOrBlank() -> "@${chat.username}"
            !chat.title.isNullOrBlank() -> chat.title
            else -> null
        }
    }
}
