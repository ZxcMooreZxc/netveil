package com.netveil.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

data class ReleaseInfo(
  val versionName: String,
  val tagName: String,
  val changelog: String,
  val apkDownloadUrl: String,
  val apkSizeBytes: Long
)

/**
 * Hardened GitHub Releases updater:
 * - Queries only the hardcoded NetVeil GitHub repository over HTTPS
 * - Enforces strict host allowlist on redirects
 * - Enforces semver upgrade check (no downgrades)
 * - Verifies downloaded APK packageName, versionCode, and SHA-256 signing certificate
 *   against the currently installed NetVeil app before allowing installation.
 */
object AppUpdater {

  private const val RELEASES_API_URL =
    "https://api.github.com/repos/ZxcMooreZxc/netveil/releases/latest"
  private const val EXPECTED_DOWNLOAD_PREFIX =
    "https://github.com/ZxcMooreZxc/netveil/releases/download/"
  private val ALLOWED_CDN_HOSTS = setOf(
    "github.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com"
  )
  private const val MAX_JSON_BYTES = 512 * 1024
  private const val MAX_APK_BYTES = 140L * 1024 * 1024
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

    val assets = root["assets"]?.jsonArray ?: return null
    val apkAsset = assets
      .map { it.jsonObject }
      .firstOrNull { asset ->
        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
        val url = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
        name.endsWith(".apk", ignoreCase = true) && isValidDownloadUrl(url)
      } ?: return null

    val downloadUrl = apkAsset["browser_download_url"]!!.jsonPrimitive.content
    val size = apkAsset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
    if (size > MAX_APK_BYTES) return null

    val changelog = root["body"]?.jsonPrimitive?.content?.trim().orEmpty().take(1500)
    return ReleaseInfo(
      versionName = remoteVersion,
      tagName = tag,
      changelog = changelog,
      apkDownloadUrl = downloadUrl,
      apkSizeBytes = size
    )
  }

  internal fun isValidDownloadUrl(url: String): Boolean {
    return url.startsWith(EXPECTED_DOWNLOAD_PREFIX, ignoreCase = false) &&
      url.endsWith(".apk", ignoreCase = true)
  }

  internal fun isNewerVersion(remote: String, current: String): Boolean {
    val rParts = remote.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
    val cParts = current.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
    if (rParts.isEmpty() || cParts.isEmpty()) return false
    val maxLen = maxOf(rParts.size, cParts.size)
    for (i in 0 until maxLen) {
      val r = rParts.getOrElse(i) { 0 }
      val c = cParts.getOrElse(i) { 0 }
      if (r > c) return true
      if (r < c) return false
    }
    return false
  }

  suspend fun downloadAndVerifyApk(
    context: Context,
    release: ReleaseInfo,
    onProgress: (Int) -> Unit
  ): File = withContext(Dispatchers.IO) {
    if (!isValidDownloadUrl(release.apkDownloadUrl)) {
      throw SecurityException("Недопустимый URL загрузки APK")
    }

    val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
    updatesDir.listFiles()?.forEach { it.delete() }
    val outFile = File(updatesDir, "NetVeil-${release.versionName}.apk")

    var currentUrl = release.apkDownloadUrl
    var hops = 0
    var connection: HttpsURLConnection? = null

    try {
      while (true) {
        val uri = URI(currentUrl)
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host !in ALLOWED_CDN_HOSTS) {
          throw SecurityException("Заблокирован редирект на недоверенный хост: ${uri.host}")
        }
        connection = (URL(currentUrl).openConnection() as HttpsURLConnection).apply {
          requestMethod = "GET"
          connectTimeout = TIMEOUT_MS
          readTimeout = 30_000
          instanceFollowRedirects = false
          setRequestProperty("User-Agent", "NetVeil-Updater")
        }
        val code = connection.responseCode
        if (code in 300..399) {
          val location = connection.getHeaderField("Location")
            ?: throw IllegalStateException("Пустой редирект при загрузке APK")
          connection.disconnect()
          hops++
          if (hops > 4) throw IllegalStateException("Слишком много редиректов")
          currentUrl = location
          continue
        }
        if (code !in 200..299) {
          throw IllegalStateException("Ошибка загрузки APK: HTTP $code")
        }
        break
      }

      val activeConn = connection!!
      val contentLength = activeConn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
      if (contentLength > MAX_APK_BYTES) {
        throw SecurityException("Размер APK превышает допустимый лимит")
      }

      val buffer = ByteArray(16 * 1024)
      var totalRead = 0L
      var lastPct = -1
      activeConn.inputStream.use { input ->
        outFile.outputStream().use { output ->
          while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > MAX_APK_BYTES) {
              throw SecurityException("Превышен максимальный размер APK")
            }
            output.write(buffer, 0, read)
            if (contentLength > 0) {
              val pct = ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
              if (pct != lastPct) {
                lastPct = pct
                onProgress(pct)
              }
            }
          }
        }
      }

      verifyApkSignature(context, outFile)
      outFile
    } catch (e: Exception) {
      outFile.delete()
      throw e
    } finally {
      connection?.disconnect()
    }
  }

  private fun verifyApkSignature(context: Context, apkFile: File) {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      PackageManager.GET_SIGNING_CERTIFICATES
    } else {
      @Suppress("DEPRECATION")
      PackageManager.GET_SIGNATURES
    }

    val installedInfo = pm.getPackageInfo(context.packageName, flags)
    val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
      ?: throw SecurityException("Не удалось прочитать метаданные скачанного APK")

    if (archiveInfo.packageName != context.packageName) {
      throw SecurityException("Package ID скачанного APK не совпадает: ${archiveInfo.packageName}")
    }

    val installedSigs = extractSignerDigests(installedInfo)
    val archiveSigs = extractSignerDigests(archiveInfo)
    if (installedSigs.isEmpty() || archiveSigs.isEmpty() || installedSigs != archiveSigs) {
      throw SecurityException("Подпись скачанного APK не совпадает с ключом NetVeil!")
    }
  }

  private fun extractSignerDigests(info: android.content.pm.PackageInfo): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      info.signingInfo?.apkContentsSigners ?: emptyArray()
    } else {
      @Suppress("DEPRECATION")
      info.signatures ?: emptyArray()
    }
    val digest = MessageDigest.getInstance("SHA-256")
    return signatures.map { sig ->
      digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
    }.toSet()
  }
}
