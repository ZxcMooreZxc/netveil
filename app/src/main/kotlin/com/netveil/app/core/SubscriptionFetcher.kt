package com.netveil.app.core

import com.netveil.app.model.ServerProfile
import com.netveil.app.model.SubscriptionGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches and validates remote VPN subscriptions strictly over HTTPS.
 * Blocks private/loopback hosts (SSRF protection) and enforces a 2 MB response cap.
 */
object SubscriptionFetcher {

  private const val MAX_BYTES = 2 * 1024 * 1024
  private const val TIMEOUT_MS = 15_000

  data class FetchResult(
    val group: SubscriptionGroup,
    val profiles: List<ServerProfile>,
    val rejectedCount: Int
  )

  fun validateSubscriptionUrl(rawUrl: String): URI {
    val trimmed = rawUrl.trim()
    val uri = try {
      URI(trimmed)
    } catch (e: Exception) {
      throw IllegalArgumentException("Некорректный URL подписки", e)
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) {
      throw IllegalArgumentException("Подписки разрешены только по HTTPS для защиты от перехвата")
    }
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty() || host.equals("localhost", ignoreCase = true)) {
      throw IllegalArgumentException("Недопустимый хост подписки")
    }
    return uri
  }

  suspend fun fetch(rawUrl: String, existingGroup: SubscriptionGroup? = null): FetchResult =
    withContext(Dispatchers.IO) {
      val uri = validateSubscriptionUrl(rawUrl)
      val host = uri.host

      // SSRF guard: ensure resolved IP is not loopback/private/link-local
      val addresses = InetAddress.getAllByName(host)
      if (addresses.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress }) {
        throw SecurityException("Локальные и приватные адреса запрещены для подписок")
      }

      val connection = (URL(uri.toString()).openConnection() as HttpsURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", "NetVeil/0.1 (Android)")
        setRequestProperty("Accept", "text/plain,*/*")
      }

      try {
        val code = connection.responseCode
        if (code !in 200..299) {
          throw IllegalStateException("Сервер подписки вернул HTTP $code")
        }

        val body = readBounded(connection.inputStream, MAX_BYTES)
        val groupId = existingGroup?.id ?: UUID.randomUUID().toString()
        val parsed = VlessUriParser.parseSubscriptionPayload(body, subscriptionId = groupId)
        if (parsed.profiles.isEmpty()) {
          throw IllegalStateException("В подписке не найдено ни одного валидного VLESS-узла")
        }

        val headerTitle = connection.getHeaderField("Profile-Title")
          ?.removePrefix("base64:")
          ?.trim()
          ?.take(48)
        val intervalHours = connection.getHeaderField("Profile-Update-Interval")
          ?.toIntOrNull()
          ?.coerceIn(1, 168)
          ?: existingGroup?.updateIntervalHours
          ?: 24

        val userInfo = parseUserInfoHeader(connection.getHeaderField("Subscription-Userinfo"))

        val group = SubscriptionGroup(
          id = groupId,
          name = existingGroup?.name ?: headerTitle?.takeIf { it.isNotBlank() } ?: host,
          url = uri.toString(),
          updatedAt = System.currentTimeMillis(),
          updateIntervalHours = intervalHours,
          usedBytes = userInfo.usedBytes,
          totalBytes = userInfo.totalBytes,
          expireEpochSec = userInfo.expireEpochSec
        )

        FetchResult(group, parsed.profiles, parsed.rejectedCount)
      } finally {
        connection.disconnect()
      }
    }

  private fun readBounded(stream: java.io.InputStream, maxBytes: Int): String {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var total = 0
    stream.use { input ->
      while (true) {
        val read = input.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) {
          throw IllegalStateException("Ответ подписки превышает лимит 2 МБ")
        }
        output.write(buffer, 0, read)
      }
    }
    return output.toString(Charsets.UTF_8.name())
  }

  private data class UserInfo(
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireEpochSec: Long? = null
  )

  private fun parseUserInfoHeader(header: String?): UserInfo {
    if (header.isNullOrBlank()) return UserInfo()
    var upload = 0L
    var download = 0L
    var hasTraffic = false
    var total: Long? = null
    var expire: Long? = null

    for (part in header.split(';')) {
      val kv = part.trim().split('=')
      if (kv.size != 2) continue
      val key = kv[0].trim().lowercase()
      val value = kv[1].trim().toLongOrNull() ?: continue
      when (key) {
        "upload" -> {
          upload = value
          hasTraffic = true
        }
        "download" -> {
          download = value
          hasTraffic = true
        }
        "total" -> total = value
        "expire" -> expire = value
      }
    }
    return UserInfo(
      usedBytes = if (hasTraffic) upload + download else null,
      totalBytes = total,
      expireEpochSec = expire
    )
  }
}
