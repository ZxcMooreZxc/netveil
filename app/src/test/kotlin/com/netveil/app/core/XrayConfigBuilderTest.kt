package com.netveil.app.core

import com.netveil.app.model.RoutingMode
import com.netveil.app.model.RoutingSettings
import com.netveil.app.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigBuilderTest {

  private val node1 = ServerProfile(
    id = "1",
    name = "Node 1",
    host = "198.51.100.10",
    port = 443,
    uuid = "550e8400-e29b-41d4-a716-446655440000",
    network = "tcp",
    security = "reality",
    flow = "xtls-rprx-vision",
    sni = "yahoo.com",
    publicKey = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789ABCD",
    shortId = "abcd"
  )

  private val node2 = node1.copy(id = "2", name = "Node 2", host = "198.51.100.11")

  @Test
  fun `builds auto-failover leastPing balancer and RU whitelist rules`() {
    val jsonStr = XrayConfigBuilder.buildVpnConfig(
      primaryProfile = node1,
      failoverPool = listOf(node1, node2),
      routing = RoutingSettings(
        mode = RoutingMode.SMART_RU_WHITELIST,
        autoFailover = true,
        tlsFragment = true,
        directDomains = listOf("sberbank.ru")
      )
    )

    val root = Json.parseToJsonElement(jsonStr).jsonObject
    val inbounds = root["inbounds"]!!.jsonArray
    val socksInbound = inbounds.first { it.jsonObject["tag"]!!.jsonPrimitive.content == "socks" }.jsonObject
    assertEquals("127.0.0.1", socksInbound["listen"]!!.jsonPrimitive.content)

    assertNotNull(root["observatory"])
    val routing = root["routing"]!!.jsonObject
    val balancers = routing["balancers"]!!.jsonArray
    assertEquals("auto-failover", balancers[0].jsonObject["tag"]!!.jsonPrimitive.content)

    val outbounds = root["outbounds"]!!.jsonArray
    assertTrue(outbounds.any { it.jsonObject["tag"]!!.jsonPrimitive.content == "fragment-out" })
    assertTrue(jsonStr.contains("geosite:category-ru"))
    assertTrue(jsonStr.contains("domain:sberbank.ru"))
  }

  @Test
  fun `single node disables failover balancer`() {
    val jsonStr = XrayConfigBuilder.buildVpnConfig(
      primaryProfile = node1,
      failoverPool = listOf(node1),
      routing = RoutingSettings(mode = RoutingMode.GLOBAL_PROXY, autoFailover = true)
    )

    val root = Json.parseToJsonElement(jsonStr).jsonObject
    assertFalse(root.containsKey("observatory"))
    assertFalse(root["routing"]!!.jsonObject.containsKey("balancers"))
  }

  @Test
  fun `log sanitizer redacts uuids, public ips, and reality keys`() {
    val raw = "Connecting to 198.51.100.42 uuid=550e8400-e29b-41d4-a716-446655440000 pbk=SECRET123456 https://sub.example.com/secret/token"
    val clean = LogSanitizer.sanitize(raw)
    assertFalse(clean.contains("198.51.100.42"))
    assertFalse(clean.contains("550e8400"))
    assertFalse(clean.contains("SECRET123456"))
    assertFalse(clean.contains("/secret/token"))
    assertTrue(clean.contains("<uuid-redacted>"))
    assertTrue(clean.contains("<ip-redacted>"))
  }

  @Test
  fun `subscription fetcher rejects non-https and localhost`() {
    val httpErr = runCatching { SubscriptionFetcher.validateSubscriptionUrl("http://example.com/sub") }.exceptionOrNull()
    assertNotNull(httpErr)

    val localErr = runCatching { SubscriptionFetcher.validateSubscriptionUrl("https://localhost/sub") }.exceptionOrNull()
    assertNotNull(localErr)
  }
}
