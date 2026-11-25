package com.netveil.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class RoutingMode(val title: String, val description: String) {
  SMART_RU_WHITELIST(
    title = "Умный RU-белый список",
    description = "Сайты РФ (.ru, .рф, банки, Госуслуги) идут напрямую, остальное — через VPN"
  ),
  GLOBAL_PROXY(
    title = "Весь трафик через VPN",
    description = "Все внешние соединения идут через VPN (кроме локальной сети)"
  ),
  WHITELIST_ONLY(
    title = "Только выбранные домены",
    description = "Через VPN идут только указанные домены, весь остальной трафик — напрямую"
  )
}

@Serializable
enum class SplitAppMode(val title: String) {
  BYPASS_SELECTED("Исключить выбранные приложения"),
  PROXY_ONLY_SELECTED("Только выбранные приложения")
}

@Serializable
data class ServerProfile(
  val id: String,
  val name: String,
  val host: String,
  val port: Int,
  val uuid: String,
  val network: String = "tcp",
  val security: String = "reality",
  val flow: String = "",
  val sni: String = "",
  val fingerprint: String = "chrome",
  val publicKey: String = "",
  val shortId: String = "",
  val spiderX: String = "",
  val wsPath: String = "",
  val wsHost: String = "",
  val grpcServiceName: String = "",
  val alpn: String = "",
  val allowInsecure: Boolean = false,
  val subscriptionId: String? = null,
  val latencyMs: Long? = null,
  val lastTestedAt: Long? = null
) {
  val endpointSummary: String
    get() = buildString {
      append(network.uppercase())
      if (security != "none") {
        append(" · ")
        append(security.uppercase())
      }
      if (flow.isNotBlank()) {
        append(" · Vision")
      }
    }
}

@Serializable
data class SubscriptionGroup(
  val id: String,
  val name: String,
  val url: String,
  val updatedAt: Long,
  val updateIntervalHours: Int = 24,
  val usedBytes: Long? = null,
  val totalBytes: Long? = null,
  val expireEpochSec: Long? = null
)

@Serializable
data class RoutingSettings(
  val mode: RoutingMode = RoutingMode.SMART_RU_WHITELIST,
  val autoFailover: Boolean = false,
  val tlsFragment: Boolean = false,
  val blockIpv6Leaks: Boolean = true,
  val directDomains: List<String> = listOf("gosuslugi.ru", "sberbank.ru", "tbank.ru", "nalog.ru", "yandex.ru"),
  val proxyDomains: List<String> = listOf("youtube.com", "googlevideo.com", "discord.com", "instagram.com", "x.com"),
  val splitAppMode: SplitAppMode = SplitAppMode.BYPASS_SELECTED,
  val selectedAppPackages: Set<String> = emptySet(),
  val dnsUpstream: String = "https://1.1.1.1/dns-query"
)

@Serializable
data class VaultData(
  val profiles: List<ServerProfile> = emptyList(),
  val subscriptions: List<SubscriptionGroup> = emptyList(),
  val activeProfileId: String? = null,
  val routing: RoutingSettings = RoutingSettings(),
  val schemaVersion: Int = 0
)

enum class VpnConnectionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  RECONNECTING,
  ERROR
}

data class TrafficStats(
  val uplinkBytes: Long = 0L,
  val downlinkBytes: Long = 0L,
  val uplinkRateBytesPerSec: Long = 0L,
  val downlinkRateBytesPerSec: Long = 0L
)

data class VpnStatusSnapshot(
  val state: VpnConnectionState = VpnConnectionState.DISCONNECTED,
  val activeProfileName: String? = null,
  val failoverActive: Boolean = false,
  val connectedSinceMs: Long? = null,
  val errorMessage: String? = null,
  val traffic: TrafficStats = TrafficStats()
)
