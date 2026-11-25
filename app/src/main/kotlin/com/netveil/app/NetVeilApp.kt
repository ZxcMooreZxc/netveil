package com.netveil.app

import android.app.Application
import com.netveil.app.core.AppLogger
import libv2ray.Libv2ray
import java.io.File
import java.security.SecureRandom
import java.util.Base64

class NetVeilApp : Application() {

  override fun onCreate() {
    super.onCreate()
    try {
      ensureGeoAssets()
      val randomBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val xudpKey = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
      Libv2ray.initCoreEnv(filesDir.absolutePath, xudpKey)
      AppLogger.info("Инициализировано ядро: ${Libv2ray.checkVersionX()}")
    } catch (e: Exception) {
      AppLogger.error("Ошибка инициализации ядра: ${e.message}")
    }
  }

  private fun ensureGeoAssets() {
    listOf("geoip.dat", "geosite.dat").forEach { name ->
      val outFile = File(filesDir, name)
      if (!outFile.exists() || outFile.length() == 0L) {
        assets.open(name).use { input ->
          outFile.outputStream().use { output ->
            input.copyTo(output)
          }
        }
      }
    }
  }
}
