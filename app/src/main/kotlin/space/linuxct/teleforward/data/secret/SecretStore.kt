package space.linuxct.teleforward.data.secret

/**
 * Secure storage for the single secret in the app: the Telegram **bot token**.
 *
 * Implementation stores the token encrypted with a non-exportable AES-256-GCM key held in the
 * AndroidKeyStore, with ciphertext+IV persisted in DataStore (EncryptedSharedPreferences is
 * deprecated and not used).
 *
 * The token must never be logged. It is consumed by [space.linuxct.teleforward.di] network
 * plumbing via [peekToken] (a synchronous read for the OkHttp path interceptor).
 */
interface SecretStore {

    /** Persist (or replace) the bot token. */
    suspend fun saveToken(token: String)

    /** Read the token, or null if none has been saved. */
    suspend fun getToken(): String?

    /**
     * Synchronous token read for the OkHttp [okhttp3.Interceptor] path (which cannot suspend).
     * Returns null if unavailable. Must be fast and non-throwing.
     */
    fun peekToken(): String?

    /** True if a token is currently stored. */
    suspend fun hasToken(): Boolean

    /** Remove the stored token (e.g. on token rotation / sign-out). */
    suspend fun clearToken()
}
