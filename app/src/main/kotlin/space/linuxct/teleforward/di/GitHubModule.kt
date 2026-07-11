package space.linuxct.teleforward.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import space.linuxct.teleforward.BuildConfig
import space.linuxct.teleforward.data.update.GitHubApi
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Distinguishes the GitHub OkHttp/Retrofit from the (unqualified) Telegram ones in NetworkModule. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHubClient

/**
 * Provides the GitHub REST API stack for the update check: a dedicated OkHttp client that adds the
 * `User-Agent` header GitHub requires plus `Accept: application/vnd.github+json`, and a Retrofit
 * pinned to [GitHubApi.BASE_URL] that reuses the shared `ignoreUnknownKeys` [Json] converter.
 */
@Module
@InstallIn(SingletonComponent::class)
object GitHubModule {

    @Provides
    @Singleton
    @GitHubClient
    fun provideGitHubOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .addInterceptor(gitHubHeaderInterceptor())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    @GitHubClient
    fun provideGitHubRetrofit(
        @GitHubClient client: OkHttpClient,
        json: Json,
    ): Retrofit = Retrofit.Builder()
        .baseUrl(GitHubApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideGitHubApi(@GitHubClient retrofit: Retrofit): GitHubApi = retrofit.create()

    /** GitHub rejects requests without a User-Agent; also opt into the versioned media type. */
    private fun gitHubHeaderInterceptor() = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", "TeleForward/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        chain.proceed(request)
    }
}
