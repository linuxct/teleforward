package space.linuxct.teleforward.data.telegram

/**
 * Drives bot-token validation and recipient pairing (plan T2).
 *
 * Flow: [validateToken] (getMe → resolve @username), then either [captureChatId]
 * (deleteWebhook + getUpdates auto-capture of the private chat that pressed Start) or
 * [setManualChatId] (numeric fallback), then [sendTest] to confirm end-to-end.
 */
interface PairingRepository {

    /** Persist [token] to the SecretStore (tentatively) and validate it via getMe. */
    suspend fun validateToken(token: String): TokenValidation

    /**
     * Call deleteWebhook then getUpdates, find the private chat that just pressed Start, store its
     * chat id + display name, and advance the getUpdates offset. Poll again on [PairingResult.NoUpdate].
     */
    suspend fun captureChatId(): PairingResult

    /** Manual fallback: store a user-entered numeric chat id (e.g. from @userinfobot). */
    suspend fun setManualChatId(chatId: Long)

    /** Send a test message to the currently paired chat. */
    suspend fun sendTest(): SendResult
}
