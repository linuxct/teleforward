package space.linuxct.teleforward.data.link

/**
 * Discord constants and the pure helpers used by the "magic link" reconstruction.
 *
 * A Discord message notification publishes a conversation shortcut whose id **is the channel id** — the
 * app builds it with `String.valueOf(channelId)` and sets the same value as the notification's
 * `locusId` — so it reaches us through the generic conversation-shortcut path as
 * [space.linuxct.teleforward.data.db.entity.OutboxEntity.conversationId]. The message snowflake rides a
 * readable `latestMessageId` extra ([MESSAGE_ID_EXTRA]). Together they address a single message:
 *
 * `https://discord.com/channels/@me/<channelId>/<messageId>`
 *
 * **Only direct messages are linkable, by design.** A *server* channel's canonical url is
 * `/channels/<guildId>/<channelId>/<messageId>`, and the **guild id appears in no readable field** — it
 * exists only inside the notification's `contentIntent`, which Android does not let another app read.
 * Substituting `@me` for a server channel would produce a link that resolves to nothing on the web
 * client, so group/server conversations deliberately yield **no** link rather than a wrong one.
 *
 * `android.isGroupConversation` is the discriminator ([isDirectMessage]): Discord sets it false only for
 * a 1:1 DM (guild text channels, threads and group DMs are all true). An *unknown* value is treated as
 * not-a-DM, so a missing field can never produce a bad link. All helpers are pure and unit-testable.
 */
object Discord {

    /** The Discord app package. */
    val PACKAGES: Set<String> = setOf(
        "com.discord",
    )

    const val SERVICE = "discord"

    /** A Discord snowflake id: 17–19 digits (channel ids, message ids, guild ids all share the shape). */
    val snowflakeRegex: Regex = Regex("\\d{17,19}")

    /** The readable extras key carrying the message snowflake of the notification's newest message. */
    const val MESSAGE_ID_EXTRA = "latestMessageId"

    private const val BASE = "https://discord.com/channels"

    /**
     * Pure: is this notification a 1:1 **direct message** — the only case we can build a correct url for?
     * Only an explicit `false` qualifies; null (the field wasn't captured) is deliberately NOT a DM, so
     * an unknown conversation can never be mislabelled into a wrong-channel link.
     */
    fun isDirectMessage(isGroupConversation: Boolean?): Boolean = isGroupConversation == false

    /**
     * Pure: the DM url for [channelId], deep-linked to [messageId] when that is itself a valid snowflake.
     * Returns null when [channelId] is not a snowflake (so a non-Discord conversation id — a WhatsApp
     * JID, a `t:title` fallback — can never be pasted into a Discord url).
     */
    fun dmUrl(channelId: String?, messageId: String? = null): String? {
        val channel = channelId?.trim().orEmpty()
        if (!snowflakeRegex.matches(channel)) return null
        val message = messageId?.trim().orEmpty()
        return if (snowflakeRegex.matches(message)) {
            "$BASE/@me/$channel/$message"
        } else {
            "$BASE/@me/$channel"
        }
    }
}
