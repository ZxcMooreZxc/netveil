package com.netveil.app.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.netveil.app.model.VaultData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts and persists all VPN profiles, UUIDs, and subscription URLs
 * using a 256-bit AES-GCM key protected by the Android Keystore.
 */
class SecureVault(private val context: Context) {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val vaultFile: File
    get() = File(context.filesDir, VAULT_FILE_NAME)

  @Synchronized
  fun load(): VaultData {
    val file = vaultFile
    if (!file.exists()) return VaultData(schemaVersion = CURRENT_SCHEMA_VERSION)
    return try {
      val encrypted = file.readBytes()
      val plainBytes = decrypt(encrypted)
      val decoded = json.decodeFromString<VaultData>(plainBytes.decodeToString())
      if (decoded.schemaVersion < CURRENT_SCHEMA_VERSION) {
        // v0.2.1 migration: disable leastPing auto-failover by default so manual server selection is always respected
        val migrated = decoded.copy(
          routing = decoded.routing.copy(autoFailover = false),
          schemaVersion = CURRENT_SCHEMA_VERSION
        )
        save(migrated)
        migrated
      } else {
        decoded
      }
    } catch (_: Exception) {
      VaultData(schemaVersion = CURRENT_SCHEMA_VERSION)
    }
  }

  @Synchronized
  fun save(data: VaultData) {
    val plainBytes = json.encodeToString(data).encodeToByteArray()
    val encrypted = encrypt(plainBytes)
    val tmpFile = File(context.filesDir, "$VAULT_FILE_NAME.tmp")
    tmpFile.writeBytes(encrypted)
    if (!tmpFile.renameTo(vaultFile)) {
      vaultFile.writeBytes(encrypted)
      tmpFile.delete()
    }
  }

  private fun getOrCreateKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    if (existing != null) return existing

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setKeySize(256)
      .setRandomizedEncryptionRequired(true)
      .build()
    generator.init(spec)
    return generator.generateKey()
  }

  private fun encrypt(plaintext: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(plaintext)
    return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
      .putInt(iv.size)
      .put(iv)
      .put(ciphertext)
      .array()
  }

  private fun decrypt(payload: ByteArray): ByteArray {
    val buffer = ByteBuffer.wrap(payload)
    val ivLength = buffer.int
    require(ivLength in 12..16) { "Invalid IV length" }
    val iv = ByteArray(ivLength)
    buffer.get(iv)
    val ciphertext = ByteArray(buffer.remaining())
    buffer.get(ciphertext)
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
  }

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "netveil_vault_aes256_gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val VAULT_FILE_NAME = "netveil_vault.enc"
    private const val CURRENT_SCHEMA_VERSION = 2
  }
}
