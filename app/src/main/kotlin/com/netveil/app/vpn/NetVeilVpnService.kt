package com.netveil.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netveil.app.MainActivity
import com.netveil.app.R
import com.netveil.app.core.AppLogger
import com.netveil.app.core.XrayConfigBuilder
import com.netveil.app.crypto.SecureVault
import com.netveil.app.model.SplitAppMode
import com.netveil.app.model.TrafficStats
import com.netveil.app.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class NetVeilVpnService : VpnService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var vpnInterface: ParcelFileDescriptor? = null
  private var coreController: CoreController? = null
  private var statsJob: Job? = null

  private var totalUp = 0L
  private var totalDown = 0L

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DISCONNECT) {
      stopVpn(clearError = true)
      return START_NOT_STICKY
    }

    ensureNotificationChannel()
    val initialNotification = buildNotification("Подключение...", "Инициализация защищённого туннеля")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, initialNotification)
    }

    serviceScope.launch {
      startVpnInternal()
    }
    return START_STICKY
  }

  private fun startVpnInternal() {
    val vault = SecureVault(applicationContext).load()
    val active = vault.profiles.firstOrNull { it.id == vault.activeProfileId }
      ?: vault.profiles.firstOrNull()

    if (active == null) {
      AppLogger.error("Нет доступных серверов для подключения")
      VpnStateBus.resetDisconnected("Добавьте хотя бы один VLESS-сервер")
      stopSelf()
      return
    }

    val failoverPool = if (active.subscriptionId != null) {
      vault.profiles.filter { it.subscriptionId == active.subscriptionId }
    } else {
      vault.profiles
    }
    val useFailover = vault.routing.autoFailover && failoverPool.size > 1

    VpnStateBus.update {
      it.copy(
        state = VpnConnectionState.CONNECTING,
        activeProfileName = active.name,
        failoverActive = useFailover,
        errorMessage = null
      )
    }

    try {
      stopCoreOnly()

      val tunPfd = buildTunInterface(vault.routing) ?: run {
        AppLogger.error("Не удалось создать TUN-интерфейс (проверьте разрешение VPN)")
        VpnStateBus.resetDisconnected("Система не выдала разрешение VPN")
        stopSelf()
        return
      }
      vpnInterface = tunPfd

      val configJson = XrayConfigBuilder.buildVpnConfig(active, failoverPool, vault.routing)
      val controller = Libv2ray.newCoreController(object : CoreCallbackHandler {
        override fun startup(): Long {
          AppLogger.info("Xray-core запущен (${active.name}${if (useFailover) ", Auto-Failover" else ""})")
          return 0
        }

        override fun shutdown(): Long {
          AppLogger.info("Xray-core остановлен")
          return 0
        }

        override fun onEmitStatus(p0: Long, p1: String?): Long {
          if (!p1.isNullOrBlank()) {
            AppLogger.info(p1)
          }
          return 0
        }
      })
      coreController = controller
      controller.startLoop(configJson, tunPfd.fd)

      totalUp = 0L
      totalDown = 0L
      val startedAt = System.currentTimeMillis()
      VpnStateBus.update {
        it.copy(
          state = VpnConnectionState.CONNECTED,
          activeProfileName = active.name,
          failoverActive = useFailover,
          connectedSinceMs = startedAt,
          errorMessage = null
        )
      }

      updateNotification(
        title = "NetVeil защищает трафик",
        text = "${active.name} · ${vault.routing.mode.title}"
      )
      startStatsLoop(controller)
    } catch (e: Exception) {
      val msg = e.message ?: "Ошибка запуска ядра"
      AppLogger.error("Ошибка VPN: $msg")
      stopVpn(clearError = false, errorMessage = msg)
    }
  }

  private fun buildTunInterface(routing: com.netveil.app.model.RoutingSettings): ParcelFileDescriptor? {
    val builder = Builder()
      .setSession("NetVeil")
      .setMtu(1500)
      .addAddress("10.13.37.1", 30)
      .addRoute("0.0.0.0", 0)
      .addDnsServer("1.1.1.1")
      .addDnsServer("8.8.8.8")

    // IPv6 leak prevention: capture IPv6 into TUN so OS does not bypass VPN on dual-stack networks
    if (routing.blockIpv6Leaks) {
      builder.addAddress("fd00:13:37::1", 126)
      builder.addRoute("::", 0)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setMetered(false)
    }

    // Never route NetVeil itself through TUN (prevents routing loop)
    val selfPkg = packageName
    val selected = routing.selectedAppPackages
    if (selected.isEmpty()) {
      runCatching { builder.addDisallowedApplication(selfPkg) }
    } else {
      when (routing.splitAppMode) {
        SplitAppMode.BYPASS_SELECTED -> {
          (selected + selfPkg).forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
          }
        }
        SplitAppMode.PROXY_ONLY_SELECTED -> {
          val allowed = selected - selfPkg
          if (allowed.isEmpty()) {
            runCatching { builder.addDisallowedApplication(selfPkg) }
          } else {
            allowed.forEach { pkg ->
              runCatching { builder.addAllowedApplication(pkg) }
            }
          }
        }
      }
    }

    return builder.establish()
  }

  private fun startStatsLoop(controller: CoreController) {
    statsJob?.cancel()
    statsJob = serviceScope.launch {
      while (isActive) {
        delay(1000)
        val raw = runCatching { controller.queryAllOutboundTrafficStats() }.getOrDefault("")
        var deltaUp = 0L
        var deltaDown = 0L
        if (raw.isNotBlank()) {
          for (chunk in raw.split(';')) {
            val parts = chunk.split(',')
            if (parts.size != 3) continue
            val tag = parts[0]
            val dir = parts[1]
            val bytes = parts[2].toLongOrNull() ?: 0L
            if (tag.startsWith("proxy")) {
              if (dir == "uplink") deltaUp += bytes
              if (dir == "downlink") deltaDown += bytes
            }
          }
        }
        totalUp += deltaUp
        totalDown += deltaDown
        VpnStateBus.update {
          it.copy(
            traffic = TrafficStats(
              uplinkBytes = totalUp,
              downlinkBytes = totalDown,
              uplinkRateBytesPerSec = deltaUp,
              downlinkRateBytesPerSec = deltaDown
            )
          )
        }
      }
    }
  }

  private fun stopCoreOnly() {
    statsJob?.cancel()
    statsJob = null
    runCatching {
      if (coreController?.isRunning == true) {
        coreController?.stopLoop()
      }
    }
    coreController = null
    runCatching { vpnInterface?.close() }
    vpnInterface = null
  }

  private fun stopVpn(clearError: Boolean, errorMessage: String? = null) {
    stopCoreOnly()
    VpnStateBus.resetDisconnected(if (clearError) null else errorMessage)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  override fun onRevoke() {
    AppLogger.warn("Системное разрешение VPN отозвано")
    stopVpn(clearError = true)
    super.onRevoke()
  }

  override fun onDestroy() {
    stopCoreOnly()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun ensureNotificationChannel() {
    val mgr = getSystemService(NotificationManager::class.java)
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "NetVeil VPN Статус",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        setShowBadge(false)
      }
      mgr.createNotificationChannel(channel)
    }
  }

  private fun updateNotification(title: String, text: String) {
    val mgr = getSystemService(NotificationManager::class.java)
    mgr.notify(NOTIFICATION_ID, buildNotification(title, text))
  }

  private fun buildNotification(title: String, text: String): Notification {
    val openAppIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val disconnectIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, NetVeilVpnService::class.java).apply { action = ACTION_DISCONNECT },
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_tile_shield)
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setContentIntent(openAppIntent)
      .addAction(0, "Отключить", disconnectIntent)
      .build()
  }

  companion object {
    const val ACTION_CONNECT = "com.netveil.app.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "com.netveil.app.ACTION_DISCONNECT"
    private const val CHANNEL_ID = "netveil_vpn_channel"
    private const val NOTIFICATION_ID = 1001

    fun start(context: Context) {
      val intent = Intent(context, NetVeilVpnService::class.java).apply {
        action = ACTION_CONNECT
      }
      context.startForegroundService(intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, NetVeilVpnService::class.java).apply {
        action = ACTION_DISCONNECT
      }
      context.startService(intent)
    }
  }
}
