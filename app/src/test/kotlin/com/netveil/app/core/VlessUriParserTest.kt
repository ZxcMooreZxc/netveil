package com.netveil.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VlessUriParserTest {

  @Test
  fun `parses valid VLESS Reality Vision URI`() {
    val uri = "vless://550e8400-e29b-41d4-a716-446655440000@de.example.com:443" +
      "?type=tcp&security=reality&flow=xtls-rprx-vision&fp=chrome&sni=google.com" +
      "&pbk=AbCdEfGhIjKlMnOpQrStUvWxYz0123456789ABCD&sid=a1b2c3d4#DE%20Frankfurt"

    val profile = VlessUriParser.parseVlessUri(uri)
    assertNotNull(profile)
    profile!!
    assertEquals("DE Frankfurt", profile.name)
    assertEquals("de.example.com", profile.host)
    assertEquals(443, profile.port)
    assertEquals("reality", profile.security)
    assertEquals("xtls-rprx-vision", profile.flow)
    assertEquals("chrome", profile.fingerprint)
    assertEquals("google.com", profile.sni)
    assertEquals("a1b2c3d4", profile.shortId)
    assertEquals(false, profile.allowInsecure)
  }

  @Test
  fun `rejects malicious or malformed VLESS URIs`() {
    // Missing Reality pbk
    assertNull(
      VlessUriParser.parseVlessUri(
        "vless://550e8400-e29b-41d4-a716-446655440000@example.com:443?security=reality#Bad"
      )
    )
    // Control characters / injection attempt
    assertNull(
      VlessUriParser.parseVlessUri(
        "vless://550e8400-e29b-41d4-a716-446655440000@example.com:443?security=tls\n#Evil"
      )
    )
    // Wildcard bind address 0.0.0.0
    assertNull(
      VlessUriParser.parseVlessUri(
        "vless://550e8400-e29b-41d4-a716-446655440000@0.0.0.0:443?security=none#BindAll"
      )
    )
    // Non-vless scheme
    assertNull(VlessUriParser.parseVlessUri("vmess://eyJhZGQiOiIxLjEuMS4xIn0="))
  }

  @Test
  fun `parses base64 subscription payload and counts rejected entries`() {
    val lines = listOf(
      "vless://550e8400-e29b-41d4-a716-446655440000@s1.example.com:443?type=ws&security=tls&path=%2Fws#Node1",
      "vless://invalid-port@s2.example.com:99999?security=none#BrokenPort",
      "vless://550e8400-e29b-41d4-a716-446655440001@s3.example.com:8443?type=grpc&security=tls&serviceName=grpc#Node2"
    ).joinToString("\n")
    val b64 = Base64.getEncoder().encodeToString(lines.toByteArray())

    val batch = VlessUriParser.parseSubscriptionPayload(b64, subscriptionId = "sub-1")
    assertEquals(2, batch.profiles.size)
    assertEquals(1, batch.rejectedCount)
    assertTrue(batch.profiles.all { it.subscriptionId == "sub-1" })
  }

  @Test
  fun `sanitizes user domains safely`() {
    assertEquals("gosuslugi.ru", VlessUriParser.sanitizeUserDomain("https://Gosuslugi.RU/login"))
    assertNull(VlessUriParser.sanitizeUserDomain("bad..domain.ru"))
    assertNull(VlessUriParser.sanitizeUserDomain("evil;rm -rf /"))
  }
}
