package com.example.bookshelf.data.settings

import java.net.URI

data class ServerConfig(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    val origin: String
        get() {
            val urlHost = if (host.contains(':')) "[$host]" else host
            return "$scheme://$urlHost:$port"
        }

    val apiBaseUrl: String get() = "$origin/api/v1/"
    val healthUrl: String get() = "$origin/health"

    companion object {
        fun parse(scheme: String, host: String, port: String): ServerConfig {
            val normalizedScheme = scheme.trim().lowercase()
            require(normalizedScheme == "http" || normalizedScheme == "https") {
                "协议必须是 HTTP 或 HTTPS"
            }

            val normalizedHost = host.trim().removeSurrounding("[", "]")
            require(normalizedHost.isNotBlank()) { "请输入服务器 IP 或域名" }
            require(normalizedHost.none(Char::isWhitespace)) { "服务器地址不能包含空格" }
            require('/' !in normalizedHost && "://" !in normalizedHost) {
                "这里只填写 IP 或域名，不要包含协议和路径"
            }
            require(validHost(normalizedHost)) { "请输入有效的 IP 或域名" }
            require(normalizedScheme == "https" || isPrivateNetworkHost(normalizedHost)) {
                "公网服务器必须使用 HTTPS；HTTP 仅允许局域网地址"
            }

            val normalizedPort = port.trim().toIntOrNull()
                ?: throw IllegalArgumentException("端口必须是数字")
            require(normalizedPort in 1..65535) { "端口范围必须是 1–65535" }
            return ServerConfig(normalizedScheme, normalizedHost, normalizedPort)
        }

        private fun validHost(host: String): Boolean {
            if (':' in host) return host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' || it == '%' || it == '-' }
            if (host.matches(Regex("^\\d+(?:\\.\\d+){3}$"))) {
                return host.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
            }
            if (host == "localhost") return true
            if (host.length > 253) return false
            return host.split('.').all { label ->
                label.length in 1..63 &&
                    label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() &&
                    label.all { it.isLetterOrDigit() || it == '-' }
            }
        }

        private fun isPrivateNetworkHost(host: String): Boolean {
            val normalized = host.lowercase().substringBefore('%')
            if (normalized == "localhost" || normalized.endsWith(".local") || '.' !in normalized && ':' !in normalized) {
                return true
            }
            if (':' in normalized) {
                if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd")) return true
                if (normalized.startsWith("fe8") || normalized.startsWith("fe9") || normalized.startsWith("fea") || normalized.startsWith("feb")) return true
                val mappedIpv4 = normalized.substringAfterLast(':').takeIf { '.' in it }
                return mappedIpv4?.let(::isPrivateIpv4) == true
            }
            return isPrivateIpv4(normalized)
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.').mapNotNull(String::toIntOrNull)
            if (parts.size != 4 || parts.any { it !in 0..255 }) return false
            return parts[0] == 10 ||
                parts[0] == 127 ||
                parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 172 && parts[1] in 16..31 ||
                parts[0] == 169 && parts[1] == 254
        }

        fun fromBaseUrl(baseUrl: String): ServerConfig {
            val uri = URI(baseUrl)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = requireNotNull(uri.host) { "Base URL 缺少主机名" }
            val port = when {
                uri.port > 0 -> uri.port
                scheme == "https" -> 443
                else -> 80
            }
            return parse(scheme, host, port.toString())
        }

        fun parseAddress(address: String): ServerConfig {
            val trimmed = address.trim().trimEnd('/')
            require(trimmed.isNotBlank()) { "请输入服务器地址" }
            val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
            val uri = runCatching { URI(withScheme) }
                .getOrElse { throw IllegalArgumentException("服务器地址格式错误") }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "服务器地址不能包含账号、查询参数或锚点"
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "服务器地址不要包含路径" }
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host ?: throw IllegalArgumentException("请输入有效的 IP 或域名")
            val port = when {
                uri.port > 0 -> uri.port
                scheme == "https" -> 443
                scheme == "http" -> 80
                else -> throw IllegalArgumentException("仅支持 HTTP 或 HTTPS")
            }
            return parse(scheme, host, port.toString())
        }
    }
}
