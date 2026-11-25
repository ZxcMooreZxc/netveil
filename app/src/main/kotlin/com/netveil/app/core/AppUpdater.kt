package com.netveil.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class ReleaseInfo(
  val versionName: String,
  val tagName: String,
  val htmlUrl: String,
  val changelog: String
)

/**
 * Checks for new NetVeil releases via GitHub Releases HTTPS API
 * without requiring REQUEST_INSTALL_PACKAGES or downloading APK binaries in-app.
 */
object AppUpdater {

  private const val RELEASES_API_URL =
    "https://api.github.com/repos/ZxcMooreZxc/netveil/releases/latest"
  private const val EXPECTED_HTML_PREFIX =
    "https://github.com/ZxcMooreZxc/netveil/releases/"
  private const val MAX_JSON_BYTES = 512 * 1024
  private const val TIMEOUT_MS = 15_000

  suspend fun checkForUpdate(currentVersionName: String): ReleaseInfo? =
    withContext(Dispatchers.IO) {
      val conn = (URL(RELEASES_API_URL).openConnection() as HttpsURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "NetVeil/$currentVersionName")
      }

      try {
        if (conn.responseCode !in 200..299) {
          throw IllegalStateException("GitHub API вернул HTTP ${conn.responseCode}")
        }
        val body = conn.inputStream.bufferedReader().use { reader ->
          val text = reader.readText()
          if (text.length > MAX_JSON_BYTES) throw IllegalStateException("Слишком большой ответ API")
          text
        }
        parseReleaseJson(body, currentVersionName)
      } finally {
        conn.disconnect()
      }
    }

  internal fun parseReleaseJson(jsonBody: String, currentVersionName: String): ReleaseInfo? {
    val root = Json.parseToJsonElement(jsonBody).jsonObject
    val tag = root["tag_name"]?.jsonPrimitive?.content?.trim().orEmpty()
    val remoteVersion = tag.removePrefix("v").removePrefix("V")
    if (remoteVersion.isBlank() || !isNewerVersion(remoteVersion, currentVersionName)) {
      return null
    }

    val htmlUrl = root["html_url"]?.jsonPrimitive?.content?.trim().orEmpty()
    val safeUrl = if (htmlUrl.startsWith(EXPECTED_HTML_PREFIX, ignoreCase = false)) {
      htmlUrl
    } else {
      "${EXPECTED_HTML_PREFIX}tag/$tag"
    }

    val changelog = root["body"]?.jsonPrimitive?.content?.trim().orEmpty().take(1500)
    return ReleaseInfo(
      versionName = remoteVersion,
      tagName = tag,
      htmlUrl = safeUrl,
      changelog = changelog
    )
  }

  internal fun isNewerVersion(remote: String, current: String): Boolean {
    val rParts = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val cParts = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val maxLen = maxOf(rParts.size, cParts.size)
    for (i in 0 until maxLen) {
      val r = rParts.getOrElse(i) { 0 }
      val c = cParts.getOrElse(i) { 0 }
      if (r > c) return true
      if (r < c) return false
    }
    return false
  }
}
