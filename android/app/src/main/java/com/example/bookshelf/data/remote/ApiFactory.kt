package com.example.bookshelf.data.remote

import com.example.bookshelf.BuildConfig
import com.example.bookshelf.data.settings.SecureCredentialStore
import com.example.bookshelf.data.settings.ServerConfigStore
import com.example.bookshelf.data.settings.ShelfAccessStore
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiFactory {
    fun create(
        configStore: ServerConfigStore,
        shelfAccess: ShelfAccessStore,
        credentials: SecureCredentialStore,
    ): BooksApi = retrofit(
        configStore = configStore,
        shelfAccess = shelfAccess,
        credentials = credentials,
        rewriteHost = true,
    ).create(BooksApi::class.java)

    fun createAuth(configStore: ServerConfigStore): AuthApi = retrofit(
        configStore = configStore,
        shelfAccess = null,
        credentials = null,
        rewriteHost = false,
    ).create(AuthApi::class.java)

    private fun retrofit(
        configStore: ServerConfigStore,
        shelfAccess: ShelfAccessStore?,
        credentials: SecureCredentialStore?,
        rewriteHost: Boolean,
    ): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC logs the method, URL and status only. Credentials and response bodies are never logged.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.MINUTES)

        if (rewriteHost) {
            builder.addInterceptor { chain ->
                val config = configStore.current()
                val url = chain.request().url.newBuilder()
                    .scheme(config.scheme)
                    .host(config.host)
                    .port(config.port)
                    .build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
        }
        builder.addInterceptor { chain ->
            val request = chain.request()
            val requestBuilder = request.newBuilder()
            credentials?.bearerToken()?.let { token -> requestBuilder.header("Authorization", "Bearer $token") }
            val segments = request.url.pathSegments
            val booksIndex = segments.indexOf("books")
            val bookId = segments.getOrNull(booksIndex + 1).takeIf { booksIndex >= 0 }
            bookId?.let { shelfAccess?.pinForBook(it) }?.let { pin -> requestBuilder.header("X-Shelf-Pin", pin) }
            chain.proceed(requestBuilder.build())
        }
        builder.addInterceptor(logging)

        val gson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(builder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}
