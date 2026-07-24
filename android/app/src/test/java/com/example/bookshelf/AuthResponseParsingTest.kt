package com.example.bookshelf

import com.example.bookshelf.data.remote.MobileSessionDto
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthResponseParsingTest {
    @Test
    fun parsesBackendMobileSessionContract() {
        val gson = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create()
        val response = gson.fromJson(
            """{
                "access_token":"v1.mobile.1784736809.nonce.signature",
                "token_type":"bearer",
                "expires_at":"2026-07-22T16:13:29Z",
                "api_version":"1.0"
            }""".trimIndent(),
            MobileSessionDto::class.java,
        )

        assertTrue(response.accessToken.startsWith("v1.mobile."))
        assertEquals("bearer", response.tokenType)
        assertEquals("1.0", response.apiVersion)
        assertEquals(1784736809000L, Instant.parse(response.expiresAt).toEpochMilli())
    }
}
