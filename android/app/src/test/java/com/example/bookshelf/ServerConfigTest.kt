package com.example.bookshelf

import com.example.bookshelf.data.settings.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {
    @Test
    fun `normalizes server input and builds endpoint urls`() {
        val config = ServerConfig.parse(" HTTP ", " 192.168.50.10 ", "8000")

        assertEquals("http://192.168.50.10:8000/api/v1/", config.apiBaseUrl)
        assertEquals("http://192.168.50.10:8000/health", config.healthUrl)
    }

    @Test
    fun `supports domain and ipv6 server addresses`() {
        assertEquals(
            "https://books.example.com:443/api/v1/",
            ServerConfig.parse("https", "books.example.com", "443").apiBaseUrl,
        )
        assertEquals(
            "http://[fd00::120]:8000/api/v1/",
            ServerConfig.parse("http", "[fd00::120]", "8000").apiBaseUrl,
        )
    }

    @Test
    fun `rejects paths schemes and invalid ports in host fields`() {
        assertTrue(runCatching { ServerConfig.parse("http", "http://nas", "8000") }.isFailure)
        assertTrue(runCatching { ServerConfig.parse("http", "nas.local/api", "8000") }.isFailure)
        assertTrue(runCatching { ServerConfig.parse("http", "nas.local", "70000") }.isFailure)
    }

    @Test
    fun `reads default ports from base urls`() {
        assertEquals(443, ServerConfig.fromBaseUrl("https://books.example.com/api/v1/").port)
        assertTrue(runCatching { ServerConfig.fromBaseUrl("http://books.example.com/api/v1/") }.isFailure)
    }

    @Test
    fun `normalizes a single flexible address field`() {
        assertEquals("http://192.168.1.10:8080", ServerConfig.parseAddress(" 192.168.1.10:8080/ ").origin)
        assertEquals("https://reader.example.com:443", ServerConfig.parseAddress("https://reader.example.com").origin)
    }

    @Test
    fun `rejects credentials paths and unsupported schemes in flexible address`() {
        assertTrue(runCatching { ServerConfig.parseAddress("ftp://reader.example.com") }.isFailure)
        assertTrue(runCatching { ServerConfig.parseAddress("https://user:pass@reader.example.com") }.isFailure)
        assertTrue(runCatching { ServerConfig.parseAddress("https://reader.example.com/api") }.isFailure)
        assertTrue(runCatching { ServerConfig.parseAddress("http://999.2.3.4:8000") }.isFailure)
        assertTrue(runCatching { ServerConfig.parseAddress("http://-bad.example:8000") }.isFailure)
        assertTrue(runCatching { ServerConfig.parseAddress("http://reader.example.com:8000") }.isFailure)
    }
}
