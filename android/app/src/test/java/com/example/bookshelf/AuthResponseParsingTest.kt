package com.example.bookshelf

import com.example.bookshelf.data.remote.MobileSessionDto
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
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
                "access_token":"v1.mobile.nonce.signature",
                "token_type":"bearer",
                "api_version":"1.0"
            }""".trimIndent(),
            MobileSessionDto::class.java,
        )

        assertTrue(response.accessToken.startsWith("v1.mobile."))
        assertEquals("bearer", response.tokenType)
        assertEquals("1.0", response.apiVersion)
    }
}
