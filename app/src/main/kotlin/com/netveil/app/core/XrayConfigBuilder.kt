package com.netveil.app.core

import com.netveil.app.model.RoutingMode
import com.netveil.app.model.RoutingSettings
import com.netveil.app.model.ServerProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates hardened Xray-core JSON configs with:
 * - Native TUN inbound + localhost-only SOCKS inbound (127.0.0.1:10808)
 * - DNS leak protection
 * - Flexible RU/custom domain whitelists
 * - Optional leastPing Auto-Failover balancer across multiple servers
 * - Optional TLS Fragment for unstable/DPI-filtered links
 */
object XrayConfigBuilder {

  private val prettyJson = Json { prettyPrint = true }

  fun buildVpnConfig(
    primaryProfile: ServerProfile,
    failoverPool: List<ServerProfile>,
    routing: RoutingSettings
  ): String {
    val useFailover = routing.autoFailover && failoverPool.size > 1
    val orderedPool = if (useFailover) {
      listOf(primaryProfile) + failoverPool.filter { it.id != primaryProfile.id }.take(7)
    } else {
      listOf(primaryProfile)
    }

    val proxyOutbounds = orderedPool.mapIndexed { idx, profile ->
      val tag = if (useFailover) "proxy-$idx" else "proxy"
      buildProxyOutbound(profile, tag, routing.tlsFragment)
    }

    val root = buildJsonObject {
      put("stats", buildJsonObject {})
      put("log", buildJsonObject {
        put("loglevel", "warning")
      })
      put("policy", buildJsonObject {
        put("levels", buildJsonObject {
          put("8", buildJsonObject {
            put("handshake", 4)
            put("connIdle", 300)
            put("uplinkOnly", 1)
            put("downlinkOnly", 1)
          })
        })
        put("system", buildJsonObject {
          put("statsOutboundUplink", true)
          put("statsOutboundDownlink", true)
        })
      })
      put("inbounds", buildInbounds())
      put("outbounds", buildJsonArray {
        proxyOutbounds.forEach { add(it) }
        add(buildDirectOutbound())
        if (routing.tlsFragment) {
          add(buildFragmentOutbound())
        }
        add(buildBlockOutbound())
      })
      if (useFailover) {
        put("observatory", buildJsonObject {
          put("subjectSelector", buildJsonArray { add(JsonPrimitive("proxy-")) })
          put("probeURL", "https://www.gstatic.com/generate_204")
          put("probeInterval", "30s")
          put("enableConcurrency", true)
        })
      }
      put("dns", buildDnsBlock(routing))
      put("routing", buildRoutingBlock(routing, useFailover))
    }

    return prettyJson.encodeToString(JsonElement.serializer(), root)
  }

  /**
   * Minimal config for Libv2ray.measureOutboundDelay().
   */
  fun buildPingConfig(profile: ServerProfile): String {
    val root = buildJsonObject {
      put("log", buildJsonObject { put("loglevel", "none") })
      put("inbounds", buildJsonArray {
        add(buildJsonObject {
          put("tag", "socks-ping")
          put("listen", "127.0.0.1")
          put("port", 10809)
          put("protocol", "socks")
          put("settings", buildJsonObject {
            put("auth", "noauth")
            put("udp", true)
          })
        })
      })
      put("outbounds", buildJsonArray {
        add(buildProxyOutbound(profile, "proxy", tlsFragment = false))
      })
    }
    return root.toString()
  }

  private fun buildInbounds(): JsonArray = buildJsonArray {
    add(buildJsonObject {
      put("tag", "socks")
      put("listen", "127.0.0.1")
      put("port", 10808)
      put("protocol", "socks")
      put("settings", buildJsonObject {
        put("auth", "noauth")
        put("udp", true)
        put("userLevel", 8)
      })
      put("sniffing", buildJsonObject {
        put("enabled", true)
        put("destOverride", buildJsonArray {
          add(JsonPrimitive("http"))
          add(JsonPrimitive("tls"))
          add(JsonPrimitive("quic"))
        })
      })
    })
    add(buildJsonObject {
      put("tag", "tun")
      put("protocol", "tun")
      put("settings", buildJsonObject {
        put("name", "xray0")
        put("MTU", 1500)
        put("userLevel", 8)
      })
      put("sniffing", buildJsonObject {
        put("enabled", true)
        put("destOverride", buildJsonArray {
          add(JsonPrimitive("http"))
          add(JsonPrimitive("tls"))
          add(JsonPrimitive("quic"))
        })
      })
    })
  }

  private fun buildProxyOutbound(
    profile: ServerProfile,
    tag: String,
    tlsFragment: Boolean
  ): JsonObject = buildJsonObject {
    put("tag", tag)
    put("protocol", "vless")
    put("settings", buildJsonObject {
      put("vnext", buildJsonArray {
        add(buildJsonObject {
          put("address", profile.host)
          put("port", profile.port)
          put("users", buildJsonArray {
            add(buildJsonObject {
              put("id", profile.uuid)
              put("encryption", "none")
              if (profile.flow.isNotBlank()) {
                put("flow", profile.flow)
              }
              put("level", 8)
            })
          })
        })
      })
    })
    put("streamSettings", buildJsonObject {
      put("network", profile.network)
      put("security", profile.security)
      when (profile.security) {
        "reality" -> {
          put("realitySettings", buildJsonObject {
            put("show", false)
            put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
            put("serverName", profile.sni.ifBlank { profile.host })
            put("publicKey", profile.publicKey)
            put("shortId", profile.shortId)
            put("spiderX", profile.spiderX)
          })
        }
        "tls" -> {
          put("tlsSettings", buildJsonObject {
            put("allowInsecure", false)
            put("serverName", profile.sni.ifBlank { profile.host })
            put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
            if (profile.alpn.isNotBlank()) {
              put("alpn", buildJsonArray {
                profile.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                  add(JsonPrimitive(it))
                }
              })
            }
          })
        }
      }
      when (profile.network) {
        "ws" -> {
          put("wsSettings", buildJsonObject {
            put("path", profile.wsPath.ifBlank { "/" })
            if (profile.wsHost.isNotBlank()) {
              put("headers", buildJsonObject {
                put("Host", profile.wsHost)
              })
            }
          })
        }
        "grpc" -> {
          put("grpcSettings", buildJsonObject {
            put("serviceName", profile.grpcServiceName)
            put("multiMode", false)
          })
        }
        "httpupgrade" -> {
          put("httpupgradeSettings", buildJsonObject {
            put("path", profile.wsPath.ifBlank { "/" })
            if (profile.wsHost.isNotBlank()) {
              put("host", profile.wsHost)
            }
          })
        }
        "xhttp" -> {
          put("xhttpSettings", buildJsonObject {
            put("path", profile.wsPath.ifBlank { "/" })
            if (profile.wsHost.isNotBlank()) {
              put("host", profile.wsHost)
            }
          })
        }
      }
      if (tlsFragment) {
        put("sockopt", buildJsonObject {
          put("dialerProxy", "fragment-out")
        })
      }
    })
  }

  private fun buildDirectOutbound(): JsonObject = buildJsonObject {
    put("tag", "direct")
    put("protocol", "freedom")
    put("settings", buildJsonObject {
      put("domainStrategy", "UseIP")
    })
  }

  private fun buildFragmentOutbound(): JsonObject = buildJsonObject {
    put("tag", "fragment-out")
    put("protocol", "freedom")
    put("settings", buildJsonObject {
      put("domainStrategy", "UseIP")
    })
    put("streamSettings", buildJsonObject {
      put("sockopt", buildJsonObject {
        put("fragment", buildJsonObject {
          put("packets", "tlshello")
          put("length", "10-50")
          put("interval", "1-5")
        })
      })
    })
  }

  private fun buildBlockOutbound(): JsonObject = buildJsonObject {
    put("tag", "block")
    put("protocol", "blackhole")
    put("settings", buildJsonObject {
      put("response", buildJsonObject { put("type", "http") })
    })
  }

  private fun buildDnsBlock(routing: RoutingSettings): JsonObject = buildJsonObject {
    put("hosts", buildJsonObject {
      put("domain:googleapis.cn", "googleapis.com")
    })
    put("servers", buildJsonArray {
      add(JsonPrimitive(routing.dnsUpstream.ifBlank { "https://1.1.1.1/dns-query" }))
      if (routing.mode == RoutingMode.SMART_RU_WHITELIST || routing.mode == RoutingMode.WHITELIST_ONLY) {
        add(buildJsonObject {
          put("address", "77.88.8.8")
          put("domains", buildJsonArray {
            add(JsonPrimitive("geosite:category-ru"))
            add(JsonPrimitive("domain:ru"))
            add(JsonPrimitive("domain:xn--p1ai"))
            routing.directDomains.forEach { d ->
              VlessUriParser.sanitizeUserDomain(d)?.let { add(JsonPrimitive("domain:$it")) }
            }
          })
        })
      }
    })
    put("queryStrategy", if (routing.blockIpv6Leaks) "UseIPv4" else "UseIP")
  }

  private fun buildRoutingBlock(routing: RoutingSettings, useFailover: Boolean): JsonObject {
    val targetKey = if (useFailover) "balancerTag" else "outboundTag"
    val targetValue = if (useFailover) "auto-failover" else "proxy"

    return buildJsonObject {
      put("domainStrategy", "IPIfNonMatch")
      if (useFailover) {
        put("balancers", buildJsonArray {
          add(buildJsonObject {
            put("tag", "auto-failover")
            put("selector", buildJsonArray { add(JsonPrimitive("proxy-")) })
            put("strategy", buildJsonObject { put("type", "leastPing") })
          })
        })
      }
      put("rules", buildJsonArray {
        // Always route private networks directly
        add(buildJsonObject {
          put("type", "field")
          put("ip", buildJsonArray { add(JsonPrimitive("geoip:private")) })
          put("outboundTag", "direct")
        })
        add(buildJsonObject {
          put("type", "field")
          put("domain", buildJsonArray { add(JsonPrimitive("geosite:private")) })
          put("outboundTag", "direct")
        })

        // Custom user direct whitelist always takes precedence in smart/global modes
        val safeDirect = routing.directDomains.mapNotNull { VlessUriParser.sanitizeUserDomain(it) }
        val safeProxy = routing.proxyDomains.mapNotNull { VlessUriParser.sanitizeUserDomain(it) }

        when (routing.mode) {
          RoutingMode.SMART_RU_WHITELIST -> {
            if (safeProxy.isNotEmpty()) {
              add(buildJsonObject {
                put("type", "field")
                put("domain", buildJsonArray {
                  safeProxy.forEach { add(JsonPrimitive("domain:$it")) }
                })
                put(targetKey, targetValue)
              })
            }
            add(buildJsonObject {
              put("type", "field")
              put("domain", buildJsonArray {
                add(JsonPrimitive("geosite:category-ru"))
                add(JsonPrimitive("domain:ru"))
                add(JsonPrimitive("domain:xn--p1ai"))
                add(JsonPrimitive("domain:su"))
                add(JsonPrimitive("domain:by"))
                safeDirect.forEach { add(JsonPrimitive("domain:$it")) }
              })
              put("outboundTag", "direct")
            })
            add(buildJsonObject {
              put("type", "field")
              put("ip", buildJsonArray { add(JsonPrimitive("geoip:ru")) })
              put("outboundTag", "direct")
            })
            add(buildJsonObject {
              put("type", "field")
              put("network", "tcp,udp")
              put(targetKey, targetValue)
            })
          }

          RoutingMode.GLOBAL_PROXY -> {
            if (safeDirect.isNotEmpty()) {
              add(buildJsonObject {
                put("type", "field")
                put("domain", buildJsonArray {
                  safeDirect.forEach { add(JsonPrimitive("domain:$it")) }
                })
                put("outboundTag", "direct")
              })
            }
            add(buildJsonObject {
              put("type", "field")
              put("network", "tcp,udp")
              put(targetKey, targetValue)
            })
          }

          RoutingMode.WHITELIST_ONLY -> {
            if (safeProxy.isNotEmpty()) {
              add(buildJsonObject {
                put("type", "field")
                put("domain", buildJsonArray {
                  safeProxy.forEach { add(JsonPrimitive("domain:$it")) }
                })
                put(targetKey, targetValue)
              })
            }
            add(buildJsonObject {
              put("type", "field")
              put("network", "tcp,udp")
              put("outboundTag", "direct")
            })
          }
        }
      })
    }
  }
}
