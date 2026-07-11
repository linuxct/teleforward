package space.linuxct.teleforward.data.secret

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import space.linuxct.teleforward.di.IoDispatcher
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Telegram **bot token** encrypted at rest with a non-exportable AES-256-GCM key held in
 * the [AndroidKeyStore][ANDROID_KEYSTORE]. The random IV and GCM ciphertext are base64-encoded into
 * a private [SharedPreferences] file ([PREFS_NAME]); the key material never leaves the keystore and
 * is not exportable.
 *
 * A [SharedPreferences] blob (rather than DataStore) backs the encrypted payload deliberately: it
 * exposes a synchronous read API, which lets [peekToken] serve the OkHttp interceptor from an
 * in-memory cache without a coroutine, and lets the cache be populated lazily on first access.
 *
 * ### Caching / [peekToken] contract
 * The decrypted token is held in [cachedToken] and is the single source of truth once [loaded].
 * [peekToken] is synchronous, fast and non-throwing: it decrypts once on first access (lazy load)
 * and thereafter returns the cached plaintext with no crypto/disk work. [saveToken]/[clearToken]
 * update the cache in-process so a save is immediately visible to a subsequent [peekToken] (the
 * pairing flow saves the token, then the interceptor reads it via [peekToken]).
 *
 * The token is never logged. All failures (missing/rotated/invalidated key, corrupt blob) degrade
 * to "no token" rather than crashing.
 */
@Singleton
class KeystoreSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SecretStore {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Cached plaintext token. Guarded by [lock] for writes; volatile for lock-free peek reads. */
    @Volatile
    private var cachedToken: String? = null

    /** Whether [cachedToken] reflects disk state (true even when the resolved token is null). */
    @Volatile
    private var loaded = false

    private val lock = Any()

    override suspend fun saveToken(token: String) {
        withContext(ioDispatcher) {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
            // GCM requires a fresh randomized IV per encryption; the cipher generates it for us.
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))

            prefs.edit()
                .putString(KEY_IV, iv.encodeBase64())
                .putString(KEY_CIPHERTEXT, ciphertext.encodeBase64())
                .commit()

            synchronized(lock) {
                cachedToken = token
                loaded = true
            }
        }
    }

    override suspend fun getToken(): String? = withContext(ioDispatcher) {
        ensureLoaded()
        cachedToken
    }

    override fun peekToken(): String? {
        ensureLoaded()
        return cachedToken
    }

    override suspend fun hasToken(): Boolean = !getToken().isNullOrEmpty()

    override suspend fun clearToken() {
        withContext(ioDispatcher) {
            runCatching {
                prefs.edit()
                    .remove(KEY_IV)
                    .remove(KEY_CIPHERTEXT)
                    .commit()
            }
            deleteKey()
            synchronized(lock) {
                cachedToken = null
                loaded = true
            }
        }
    }

    /**
     * Populates [cachedToken] from disk exactly once. Cheap and idempotent after the first call.
     * Never throws: any failure resolves the token to null (and drops the stale blob so future
     * saves start clean).
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            cachedToken = loadFromDisk()
            loaded = true
        }
    }

    private fun loadFromDisk(): String? = try {
        val ivB64 = prefs.getString(KEY_IV, null)
        val ctB64 = prefs.getString(KEY_CIPHERTEXT, null)
        if (ivB64 == null || ctB64 == null) {
            null
        } else {
            val key = loadKeyOrNull()
            if (key == null) {
                null
            } else {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivB64.decodeBase64()))
                }
                String(cipher.doFinal(ctB64.decodeBase64()), Charsets.UTF_8)
            }
        }
    } catch (t: Throwable) {
        // Key rotated/permanently invalidated, or corrupt/tampered blob: discard and report absent.
        runCatching { prefs.edit().remove(KEY_IV).remove(KEY_CIPHERTEXT).commit() }
        null
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Returns the existing keystore key, or null if none (or the keystore is unreadable). */
    private fun loadKeyOrNull(): SecretKey? = runCatching {
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        loadKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // No user-authentication requirement: the token must be usable by background workers.
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun deleteKey() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "teleforward_bot_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
        const val PREFS_NAME = "teleforward_secret"
        const val KEY_IV = "bot_token_iv"
        const val KEY_CIPHERTEXT = "bot_token_ct"
    }
}
