package space.linuxct.teleforward.data.telegram

import okhttp3.Interceptor
import okhttp3.Response
import space.linuxct.teleforward.data.secret.SecretStore

/**
 * Rewrites each request path to prepend the Telegram `bot<token>` segment, reading the token from
 * [secretStore] at request time. This keeps the token out of Retrofit annotations and out of any
 * request logs (the token is added after logging interceptors would run if placed before them),
 * and lets the token rotate without rebuilding Retrofit.
 *
 * If no token is available the request proceeds unchanged (it will fail upstream, surfaced to the
 * user as "set your bot token"). The path is only rewritten when it does not already start with a
 * `bot…` segment, so it is idempotent.
 */
class TokenPathInterceptor(
    private val secretStore: SecretStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = secretStore.peekToken()
        if (token.isNullOrEmpty()) {
            return chain.proceed(request)
        }

        val original = request.url
        val segments = original.pathSegments
        val alreadyTokenized = segments.firstOrNull()?.startsWith("bot") == true &&
            (segments.firstOrNull()?.length ?: 0) > 3
        if (alreadyTokenized) {
            return chain.proceed(request)
        }

        val builder = original.newBuilder()
        // Remove existing segments (back-to-front to keep indices valid).
        for (index in segments.indices.reversed()) {
            builder.removePathSegment(index)
        }
        builder.addPathSegment("bot$token")
        for (segment in segments) {
            builder.addPathSegment(segment)
        }

        val newRequest = request.newBuilder().url(builder.build()).build()
        return chain.proceed(newRequest)
    }
}
