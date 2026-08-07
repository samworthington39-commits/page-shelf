package com.example.bookshelf.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

data class MobileLoginRequest(val password: String)

data class MobileSessionDto(
    val accessToken: String,
    val tokenType: String,
    val apiVersion: String,
)

data class SessionStatusDto(val status: String, val apiVersion: String)

interface AuthApi {
    @POST
    suspend fun login(@Url url: String, @Body request: MobileLoginRequest): MobileSessionDto

    @GET
    suspend fun session(@Url url: String, @Header("Authorization") authorization: String): SessionStatusDto
}
