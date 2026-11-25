package com.netveil.app.core

import com.netveil.app.model.ServerProfile
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

object VlessUriParser {

  private val ALLOWED_NETWORKS = setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")
  private val ALLOWED_SECURITY = setOf("none", "tls", "reality")
  private val ALLOWED_FLOWS = setOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
  private val ALLOWED_FINGERPRINTS = setOf(
    "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", ""
  )
  private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")
  private val SAFE_HOST_REGEX = Regex("^[a-zA-Z0-9._:-]{1,253}$")
  private val SAFE_DOMAIN_REGEX = Regex("^[a-zA-Z0-9*._-]{1,253}$")

  data class ParseBatchResult(
    val profiles: List<ServerProfile>,
    val rejectedCount: Int
  )

  /**
   * Parses a raw subscription body (base64 or plain newline-separated vless:// URIs).
   * Strictly rejects malformed URIs, control characters, and unsupported schemes.
   */
  fun parseSubscriptionPayload(rawPayload: String, subscriptionId: String? = null): ParseBatchResult {
    val trimmed = rawPayload.trim()
    if (trimmed.isEmpty()) return ParseBatchResult(emptyList(), 0)

    val decoded = if (!trimmed.contains("vless://", ignoreCase = true)) {
      decodeBase64Safe(trimmed) ?: trimmed
    } else {
      trimmed
    }

    val lines = decoded
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .take(500)
      .toList()

    val valid = mutableListOf<ServerProfile>()
    var rejected = 0
    for (line in lines) {
      val parsed = parseVlessUri(line, subscriptionId)
      if (parsed != null) {
        valid.add(parsed)
      } else {
        rejected++
      }
    }
    return ParseBatchResult(valid, rejected)
  }

  /**
   * Parses and strictly validates a single vless:// URI into a typed ServerProfile.
   * Returns null if any field fails security or format validation.
   */
  fun parseVlessUri(rawUri: String, subscriptionId: String? = null): ServerProfile? {
    val cleaned = rawUri.trim()
    if (!cleaned.startsWith("vless://", ignoreCase = true)) return null
    if (CONTROL_CHARS.containsMatchIn(cleaned) || cleaned.length > 4096) return null

    val uri = try {
      URI(cleaned)
    } catch (_: Exception) {
      return null
    }

    val uuid = uri.userInfo?.trim().orEmpty()
    if (!isValidUuidOrSecret(uuid)) return null

    val host = uri.host?.trim()?.removeSurrounding("[", "]").orEmpty()
    if (host.isEmpty() || !SAFE_HOST_REGEX.matches(host)) return null
    if (host == "0.0.0.0") return null

    val port = uri.port
    if (port !in 1..65535) return null

    val params = parseQueryParams(uri.rawQuery)
    val network = (params["type"] ?: params["net"] ?: "tcp").lowercase()
    if (network !in ALLOWED_NETWORKS) return null

    val security = (params["security"] ?: "none").lowercase()
    if (security !in ALLOWED_SECURITY) return null

    val flow = (params["flow"] ?: "").trim()
    if (flow !in ALLOWED_FLOWS) return null

    val fingerprint = (params["fp"] ?: "chrome").lowercase()
    val safeFp = if (fingerprint in ALLOWED_FINGERPRINTS) fingerprint else "chrome"

    val sni = sanitizeDomainOrEmpty(params["sni"] ?: params["serverName"])
    val publicKey = sanitizeToken(params["pbk"], maxLen = 128)
    val shortId = sanitizeHexOrEmpty(params["sid"])
    val spiderX = sanitizePath(params["spx"], maxLen = 128)
    val wsPath = sanitizePath(params["path"], maxLen = 256)
    val wsHost = sanitizeDomainOrEmpty(params["host"])
    val grpcServiceName = sanitizeToken(params["serviceName"], maxLen = 128)
    val alpn = sanitizeToken(params["alpn"], maxLen = 64)

    // Security policy: Reality requires a valid public key; allowInsecure is never trusted from remote URIs
    if (security == "reality" && publicKey.length < 16) return null

    val rawRemark = uri.rawFragment?.let { urlDecode(it) }.orEmpty()
    val safeName = sanitizeRemark(rawRemark).ifBlank { "$host:$port" }

    return ServerProfile(
      id = UUID.randomUUID().toString(),
      name = safeName,
      host = host,
      port = port,
      uuid = uuid,
      network = network,
      security = security,
      flow = flow,
      sni = sni,
      fingerprint = safeFp,
      publicKey = publicKey,
      shortId = shortId,
      spiderX = spiderX,
      wsPath = wsPath,
      wsHost = wsHost,
      grpcServiceName = grpcServiceName,
      alpn = alpn,
      allowInsecure = false,
      subscriptionId = subscriptionId
    )
  }

  fun sanitizeUserDomain(input: String): String? {
    val candidate = input.trim().lowercase()
      .removePrefix("https://")
      .removePrefix("http://")
      .substringBefore("/")
      .substringBefore(":")
    if (candidate.isEmpty() || candidate.length > 253) return null
    if (!SAFE_DOMAIN_REGEX.matches(candidate)) return null
    if (candidate.contains("..")) return null
    return candidate
  }

  private fun isValidUuidOrSecret(value: String): Boolean {
    if (value.isEmpty() || value.length > 128) return false
    if (CONTROL_CHARS.containsMatchIn(value)) return false
    return try {
      UUID.fromString(value)
      true
    } catch (_: Exception) {
      // Xray supports arbitrary 1-30 byte string mapped to UUIDv5, allow safe ASCII tokens only
      value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
  }

  private fun sanitizeDomainOrEmpty(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val trimmed = value.trim()
    return if (SAFE_DOMAIN_REGEX.matches(trimmed) && !CONTROL_CHARS.containsMatchIn(trimmed)) trimmed else ""
  }

  private fun sanitizeToken(value: String?, maxLen: Int): String {
    if (value.isNullOrBlank()) return ""
    val trimmed = value.trim().take(maxLen)
    return if (trimmed.all { it.isLetterOrDigit() || it in setOf('-', '_', '.', ',', '/') }) trimmed else ""
  }

  private fun sanitizeHexOrEmpty(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val trimmed = value.trim().take(32)
    return if (trimmed.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) trimmed else ""
  }

  private fun sanitizePath(value: String?, maxLen: Int): String {
    if (value.isNullOrBlank()) return ""
    val trimmed = value.trim().take(maxLen)
    if (CONTROL_CHARS.containsMatchIn(trimmed) || trimmed.contains("..")) return ""
    return trimmed
  }

  private fun sanitizeRemark(value: String): String {
    return CONTROL_CHARS.replace(value, "")
      .trim()
      .take(64)
  }

  private fun parseQueryParams(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (pair in rawQuery.split('&')) {
      val idx = pair.indexOf('=')
      if (idx <= 0) continue
      val key = urlDecode(pair.substring(0, idx))
      val value = urlDecode(pair.substring(idx + 1))
      if (key.isNotEmpty()) {
        result[key] = value
      }
    }
    return result
  }

  private fun urlDecode(value: String): String {
    return try {
      URLDecoder.decode(value, StandardCharsets.UTF_8)
    } catch (_: Exception) {
      value
    }
  }

  private fun decodeBase64Safe(input: String): String? {
    val compact = input.replace("\r", "").replace("\n", "").replace(" ", "")
    val padded = when (compact.length % 4) {
      2 -> "$compact=="
      3 -> "$compact="
      else -> compact
    }
    return try {
      String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
    } catch (_: Exception) {
      try {
        String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
      } catch (_: Exception) {
        null
      }
    }
  }
}
