package com.netveil.app.core

/**
 * Redacts sensitive identifiers (UUIDs, IPv4 addresses, Reality keys, tokens)
 * before logs are displayed in the UI or copied to the clipboard.
 */
object LogSanitizer {

  private val UUID_REGEX =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
  private val IPV4_REGEX =
    Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
  private val REALITY_PARAM_REGEX =
    Regex("(pbk|sid|publicKey|shortId)=([^&\\s\"']+)", RegexOption.IGNORE_CASE)
  private val SUB_URL_REGEX =
    Regex("https://[^\\s\"']+")

  fun sanitize(message: String): String {
    var out = UUID_REGEX.replace(message, "<uuid-redacted>")
    out = REALITY_PARAM_REGEX.replace(out) { "${it.groupValues[1]}=<redacted>" }
    out = IPV4_REGEX.replace(out) { match ->
      val ip = match.value
      if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip.startsWith("10.") || ip.startsWith("192.168.")) {
        ip
      } else {
        "<ip-redacted>"
      }
    }
    out = SUB_URL_REGEX.replace(out) { url ->
      val hostEnd = url.value.indexOf('/', startIndex = 8)
      if (hostEnd == -1) url.value else "${url.value.substring(0, hostEnd)}/<path-redacted>"
    }
    return out
  }
}
