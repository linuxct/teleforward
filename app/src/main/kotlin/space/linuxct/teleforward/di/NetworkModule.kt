package space.linuxct.teleforward.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import space.linuxct.teleforward.data.secret.SecretStore
import space.linuxct.teleforward.data.telegram.TelegramApi
import space.linuxct.teleforward.data.telegram.TokenPathInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideTokenPathInterceptor(secretStore: SecretStore): TokenPathInterceptor =
        TokenPathInterceptor(secretStore)

    /**
     * Logging is kept at NONE by default so the bot token (path segment) never lands in logs.
     * A Wave-1 build can raise this to BASIC for metadata-only debug logging if desired.
     */
    @Provides
    @Singleton
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        tokenPathInterceptor: TokenPathInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        // Logging first (sees the tokenless path), then the token interceptor rewrites the URL.
        .addInterceptor(loggingInterceptor)
        .addInterceptor(tokenPathInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl(TelegramApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun provideTelegramApi(retrofit: Retrofit): TelegramApi = retrofit.create()
}
