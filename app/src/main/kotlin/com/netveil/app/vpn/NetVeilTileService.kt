package com.netveil.app.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.netveil.app.MainActivity
import com.netveil.app.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NetVeilTileService : TileService() {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observerJob: Job? = null

  override fun onStartListening() {
    super.onStartListening()
    observerJob?.cancel()
    observerJob = scope.launch {
      VpnStateBus.status.collect { snapshot ->
        val tile = qsTile ?: return@collect
        tile.state = when (snapshot.state) {
          VpnConnectionState.CONNECTED,
          VpnConnectionState.CONNECTING,
          VpnConnectionState.RECONNECTING -> Tile.STATE_ACTIVE
          else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = snapshot.activeProfileName ?: "Отключено"
        tile.updateTile()
      }
    }
  }

  override fun onStopListening() {
    observerJob?.cancel()
    super.onStopListening()
  }

  override fun onClick() {
    super.onClick()
    val current = VpnStateBus.status.value.state
    if (current == VpnConnectionState.CONNECTED || current == VpnConnectionState.CONNECTING) {
      NetVeilVpnService.stop(this)
    } else {
      val prepare = VpnService.prepare(this)
      if (prepare == null) {
        NetVeilVpnService.start(this)
      } else {
        val openIntent = Intent(this, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
          )
          startActivityAndCollapse(pending)
        } else {
          @Suppress("DEPRECATION")
          startActivityAndCollapse(openIntent)
        }
      }
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
