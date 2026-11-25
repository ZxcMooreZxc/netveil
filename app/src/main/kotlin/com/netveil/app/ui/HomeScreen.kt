package com.netveil.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netveil.app.model.RoutingMode
import com.netveil.app.model.VpnConnectionState
import com.netveil.app.model.VpnStatusSnapshot
import com.netveil.app.ui.theme.VeilAmber
import com.netveil.app.ui.theme.VeilBorder
import com.netveil.app.ui.theme.VeilCyan
import com.netveil.app.ui.theme.VeilEmerald
import com.netveil.app.ui.theme.VeilMuted
import com.netveil.app.ui.theme.VeilRed
import com.netveil.app.ui.theme.VeilSurface
import com.netveil.app.ui.theme.VeilSurfaceHigh
import java.util.Locale

@Composable
fun HomeScreen(
  uiState: MainUiState,
  vpnStatus: VpnStatusSnapshot,
  onToggleConnect: () -> Unit,
  onPasteFromClipboard: () -> Unit,
  onOpenImportDialog: () -> Unit,
  onSelectRoutingMode: (RoutingMode) -> Unit,
  onOpenServersTab: () -> Unit
) {
  val activeProfile = uiState.vault.profiles.firstOrNull { it.id == uiState.vault.activeProfileId }
    ?: uiState.vault.profiles.firstOrNull()

  val isConnected = vpnStatus.state == VpnConnectionState.CONNECTED
  val isTransitioning = vpnStatus.state == VpnConnectionState.CONNECTING ||
    vpnStatus.state == VpnConnectionState.RECONNECTING

  val ringColor by animateColorAsState(
    targetValue = when (vpnStatus.state) {
      VpnConnectionState.CONNECTED -> VeilEmerald
      VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING -> VeilAmber
      VpnConnectionState.ERROR -> VeilRed
      VpnConnectionState.DISCONNECTED -> VeilCyan
    },
    label = "ringColor"
  )

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberCornerScrollState())
      .padding(horizontal = 20.dp, vertical = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "NetVeil",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold
        )
        Text(
          text = "Защищённый VLESS & Reality клиент",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )
      }
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = VeilEmerald.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, VeilEmerald.copy(alpha = 0.35f))
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Rounded.VerifiedUser,
            contentDescription = null,
            tint = VeilEmerald,
            modifier = Modifier.size(14.dp)
          )
          Spacer(Modifier.width(5.dp))
          Text(
            text = "Keystore + DNS Guard",
            style = MaterialTheme.typography.labelSmall,
            color = VeilEmerald
          )
        }
      }
    }

    Spacer(Modifier.height(28.dp))

    // Main Power Button
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(176.dp)
        .clip(CircleShape)
        .background(
          Brush.radialGradient(
            colors = listOf(ringColor.copy(alpha = 0.22f), Color.Transparent)
          )
        )
        .clickable(enabled = !isTransitioning) { onToggleConnect() }
    ) {
      Surface(
        shape = CircleShape,
        color = VeilSurface,
        border = BorderStroke(3.dp, ringColor),
        modifier = Modifier.size(142.dp)
      ) {
        Column(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            imageVector = Icons.Rounded.PowerSettingsNew,
            contentDescription = "Подключить",
            tint = ringColor,
            modifier = Modifier.size(44.dp)
          )
          Spacer(Modifier.height(6.dp))
          Text(
            text = when (vpnStatus.state) {
              VpnConnectionState.CONNECTED -> "ЗАЩИЩЕНО"
              VpnConnectionState.CONNECTING -> "ПОДКЛЮЧЕНИЕ..."
              VpnConnectionState.RECONNECTING -> "ПЕРЕКЛЮЧЕНИЕ..."
              VpnConnectionState.ERROR -> "ОШИБКА"
              VpnConnectionState.DISCONNECTED -> "ПОДКЛЮЧИТЬ"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = ringColor
          )
        }
      }
    }

    if (!vpnStatus.errorMessage.isNullOrBlank()) {
      Spacer(Modifier.height(12.dp))
      Surface(
        color = VeilRed.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, VeilRed.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(
          text = vpnStatus.errorMessage,
          color = Color(0xFFFCA5A5),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(12.dp)
        )
      }
    }

    Spacer(Modifier.height(22.dp))

    // Live Traffic Card
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
      ) {
        TrafficColumn(
          icon = Icons.Rounded.ArrowDownward,
          tint = VeilEmerald,
          label = "Скачано",
          speed = formatSpeed(vpnStatus.traffic.downlinkRateBytesPerSec),
          total = formatBytes(vpnStatus.traffic.downlinkBytes)
        )
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(44.dp)
            .background(VeilBorder)
        )
        TrafficColumn(
          icon = Icons.Rounded.ArrowUpward,
          tint = VeilCyan,
          label = "Отдано",
          speed = formatSpeed(vpnStatus.traffic.uplinkRateBytesPerSec),
          total = formatBytes(vpnStatus.traffic.uplinkBytes)
        )
      }
    }

    Spacer(Modifier.height(14.dp))

    // Active Server Card
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onOpenServersTab() }
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text("Активный сервер", style = MaterialTheme.typography.labelMedium, color = VeilMuted)
          if (uiState.vault.routing.autoFailover && uiState.vault.profiles.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Rounded.Bolt, null, tint = VeilEmerald, modifier = Modifier.size(14.dp))
              Spacer(Modifier.width(3.dp))
              Text("Auto-Failover", style = MaterialTheme.typography.labelSmall, color = VeilEmerald)
            }
          }
        }
        Spacer(Modifier.height(6.dp))
        if (activeProfile != null) {
          Text(
            text = activeProfile.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = "${activeProfile.endpointSummary} · ${activeProfile.host}:${activeProfile.port}",
            style = MaterialTheme.typography.bodySmall,
            color = VeilMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        } else {
          Text(
            text = "Серверы ещё не добавлены",
            style = MaterialTheme.typography.titleMedium,
            color = VeilMuted
          )
          Text(
            text = "Вставьте ссылку vless:// или URL подписки",
            style = MaterialTheme.typography.bodySmall,
            color = VeilMuted
          )
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    // Quick Import Buttons
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Button(
        onClick = onPasteFromClipboard,
        modifier = Modifier.weight(1f),
        colors = ButtonDefaults.buttonColors(
          containerColor = VeilSurfaceHigh,
          contentColor = com.netveil.app.ui.theme.VeilText
        ),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Из буфера", maxLines = 1)
      }
      OutlinedButton(
        onClick = onOpenImportDialog,
        modifier = Modifier.weight(1f),
        border = BorderStroke(1.dp, VeilBorder),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Вставить", maxLines = 1)
      }
    }

    Spacer(Modifier.height(18.dp))

    // Quick Routing Mode Switcher
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.Shield, null, tint = VeilCyan, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(
            text = "Режим маршрутизации",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
        }
        Spacer(Modifier.height(10.dp))
        RoutingMode.entries.forEach { mode ->
          val selected = uiState.vault.routing.mode == mode
          FilterChip(
            selected = selected,
            onClick = { onSelectRoutingMode(mode) },
            label = { Text(mode.title) },
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 2.dp)
          )
        }
        Spacer(Modifier.height(4.dp))
        Text(
          text = uiState.vault.routing.mode.description +
            if (isConnected) " (переподключите VPN для применения)" else "",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )
      }
    }
  }
}

@Composable
private fun rememberCornerScrollState() = rememberScrollState()

@Composable
private fun TrafficColumn(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  tint: Color,
  label: String,
  speed: String,
  total: String
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
      Spacer(Modifier.width(4.dp))
      Text(label, style = MaterialTheme.typography.labelSmall, color = VeilMuted)
    }
    Spacer(Modifier.height(4.dp))
    Text(speed, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(total, style = MaterialTheme.typography.bodySmall, color = VeilMuted)
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatSpeed(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
