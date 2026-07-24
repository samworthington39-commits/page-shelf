package com.example.bookshelf.data.remote

import com.example.bookshelf.data.settings.ServerConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import java.net.SocketTimeoutException

data class ConnectionCheck(val latencyMillis: Long, val apiVersion: String)

class ServerConnectionTester {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    suspend fun test(config: ServerConfig): ConnectionCheck = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(config.healthUrl).get().build()
        val startedAt = System.nanoTime()
        try {
            val apiVersion = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ConnectionTestException("健康检查返回 HTTP ${response.code}")
                }
                val health = runCatching {
                    gson.fromJson(response.body?.string().orEmpty(), HealthResponse::class.java)
                }.getOrNull()
                if (health?.status != "ok") {
                    throw ConnectionTestException("服务器响应正常，但不是兼容的页架后端")
                }
                if (health.apiVersion?.substringBefore('.') != SUPPORTED_API_MAJOR) {
                    throw ConnectionTestException("后端版本不兼容，请将服务器升级到 API 1.x")
                }
                requireNotNull(health.apiVersion)
            }
            ConnectionCheck((System.nanoTime() - startedAt) / 1_000_000, apiVersion)
        } catch (error: ConnectionTestException) {
            throw error
        } catch (error: Exception) {
            throw ConnectionTestException(error.toUserMessage(), error)
        }
    }
}

class ConnectionTestException(message: String, cause: Throwable? = null) : Exception(message, cause)

private data class HealthResponse(val status: String?, @SerializedName("api_version") val apiVersion: String?)

private const val SUPPORTED_API_MAJOR = "1"

private fun Exception.toUserMessage(): String = when (this) {
    is UnknownHostException -> "找不到服务器，请检查 IP 或域名"
    is ConnectException -> "无法连接到该端口，请检查地址、防火墙和后端状态"
    is SocketTimeoutException -> "连接超时，请确认手机和服务器位于可互通的网络"
    is SSLException -> "HTTPS 证书验证失败"
    else -> message ?: "连接失败"
}
