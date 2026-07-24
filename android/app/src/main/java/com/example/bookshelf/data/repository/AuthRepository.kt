package com.example.bookshelf.data.repository

import com.example.bookshelf.data.remote.AuthApi
import com.example.bookshelf.data.remote.MobileLoginRequest
import com.example.bookshelf.data.remote.ServerConnectionTester
import com.example.bookshelf.data.settings.SecureCredentialStore
import com.example.bookshelf.data.settings.ServerConfig
import com.example.bookshelf.data.settings.ServerConfigStore
import java.time.Instant
import retrofit2.HttpException

data class AuthResult(val latencyMillis: Long, val apiVersion: String)

class AuthRepository(
    private val authApi: AuthApi,
    private val connectionTester: ServerConnectionTester,
    private val configStore: ServerConfigStore,
    private val credentials: SecureCredentialStore,
) {
    init {
        // Older builds retained the management password for silent re-login. Keep only the
        // revocable, expiring session token from now on.
        credentials.clearPassword()
    }

    suspend fun login(config: ServerConfig, password: String): AuthResult {
        require(password.isNotBlank()) { "请输入管理密码" }
        val health = connectionTester.test(config)
        val session = try {
            authApi.login("${config.apiBaseUrl}auth/login", MobileLoginRequest(password))
        } catch (error: HttpException) {
            throw AuthException(
                when (error.code()) {
                    401 -> "管理密码错误"
                    404 -> "后端版本不兼容，缺少移动端登录接口"
                    503 -> "服务器尚未配置管理密码"
                    else -> "服务器登录失败（HTTP ${error.code()}）"
                },
                error,
            )
        }
        if (session.apiVersion.substringBefore('.') != "1") {
            throw AuthException("后端版本不兼容，请将服务器升级到 API 1.x")
        }
        val expiresAt = runCatching { Instant.parse(session.expiresAt).toEpochMilli() }
            .getOrElse { throw AuthException("服务器返回了无效的登录会话") }
        configStore.save(config)
        credentials.clearPassword()
        credentials.saveSession(session.accessToken, expiresAt)
        return AuthResult(health.latencyMillis, session.apiVersion)
    }

    suspend fun autoLogin(): AuthResult {
        check(configStore.isConfigured) { "尚未配置服务器" }
        val token = credentials.bearerToken() ?: throw AuthException("登录已过期，请重新输入管理密码")
        val config = configStore.current()
        val health = connectionTester.test(config)
        val session = try {
            authApi.session("${config.apiBaseUrl}auth/session", "Bearer $token")
        } catch (error: HttpException) {
            if (error.code() == 401) credentials.clearSession()
            throw AuthException(
                if (error.code() == 401) "登录已过期，请重新输入管理密码"
                else "无法验证登录状态（HTTP ${error.code()}）",
                error,
            )
        }
        return AuthResult(health.latencyMillis, session.apiVersion)
    }

    fun hasSavedCredentials(): Boolean = configStore.isConfigured && credentials.bearerToken() != null

    fun clearSession() = credentials.clearSession()
}

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
