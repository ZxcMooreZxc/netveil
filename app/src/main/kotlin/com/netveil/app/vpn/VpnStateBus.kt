package com.netveil.app.vpn

import com.netveil.app.model.TrafficStats
import com.netveil.app.model.VpnConnectionState
import com.netveil.app.model.VpnStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object VpnStateBus {
  private val _status = MutableStateFlow(VpnStatusSnapshot())
  val status: StateFlow<VpnStatusSnapshot> = _status.asStateFlow()

  fun update(transform: (VpnStatusSnapshot) -> VpnStatusSnapshot) {
    _status.value = transform(_status.value)
  }

  fun resetDisconnected(error: String? = null) {
    _status.value = VpnStatusSnapshot(
      state = if (error == null) VpnConnectionState.DISCONNECTED else VpnConnectionState.ERROR,
      errorMessage = error,
      traffic = TrafficStats()
    )
  }
}
