package com.example.bookshelf.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

interface BooksApi {
    @GET("shelves")
    suspend fun shelves(): List<PublicShelfDto>

    @retrofit2.http.POST("shelves/{shelfId}/unlock")
    suspend fun unlockShelf(
        @Path("shelfId") shelfId: String,
        @Body request: ShelfUnlockRequest,
    ): PublicShelfDto

    @GET("books")
    suspend fun books(): List<BookDto>

    @GET("books/{bookId}")
    suspend fun book(@Path("bookId") bookId: String): BookDto

    @GET("books/{bookId}/toc")
    suspend fun toc(@Path("bookId") bookId: String): TocResponseDto

    @GET("books/{bookId}/chapters/{chapterId}")
    suspend fun chapter(
        @Path("bookId") bookId: String,
        @Path("chapterId") chapterId: String,
    ): ChapterResponseDto

    @GET("books/{bookId}/pdf-navigation")
    suspend fun pdfNavigation(@Path("bookId") bookId: String): PdfNavigationResponseDto

    @Streaming
    @GET("books/{bookId}/file")
    suspend fun file(
        @Path("bookId") bookId: String,
        @Header("Range") range: String? = null,
        @Header("If-Range") ifRange: String? = null,
    ): Response<ResponseBody>

    @GET("books/{bookId}/progress/{deviceId}")
    suspend fun progress(
        @Path("bookId") bookId: String,
        @Path("deviceId") deviceId: String,
    ): Response<ProgressResponseDto>

    @GET("books/{bookId}/progress")
    suspend fun latestProgress(
        @Path("bookId") bookId: String,
    ): Response<ProgressResponseDto>

    @PUT("books/{bookId}/progress/{deviceId}")
    suspend fun saveProgress(
        @Path("bookId") bookId: String,
        @Path("deviceId") deviceId: String,
        @Body body: ProgressRequest,
    ): ProgressResponseDto

    @PUT("books/{bookId}/progress/{deviceId}")
    suspend fun saveTextProgress(
        @Path("bookId") bookId: String,
        @Path("deviceId") deviceId: String,
        @Body body: TextProgressRequest,
    ): ProgressResponseDto
}
