package com.netveil.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.netveil.app.model.ServerProfile
import com.netveil.app.model.SubscriptionGroup
import com.netveil.app.ui.theme.VeilAmber
import com.netveil.app.ui.theme.VeilBorder
import com.netveil.app.ui.theme.VeilCyan
import com.netveil.app.ui.theme.VeilEmerald
import com.netveil.app.ui.theme.VeilMuted
import com.netveil.app.ui.theme.VeilRed
import com.netveil.app.ui.theme.VeilSurface

@Composable
fun ServersScreen(
  uiState: MainUiState,
  onSelectProfile: (String) -> Unit,
  onDeleteProfile: (String) -> Unit,
  onPingSingle: (String) -> Unit,
  onPingAll: () -> Unit,
  onRefreshSubscription: (String) -> Unit,
  onDeleteSubscription: (String) -> Unit,
  onOpenImportDialog: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text("Серверы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
          text = "Узлов: ${uiState.vault.profiles.size} · Подписок: ${uiState.vault.subscriptions.size}",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
          onClick = onPingAll,
          enabled = uiState.vault.profiles.isNotEmpty() && uiState.pingingIds.isEmpty(),
          border = BorderStroke(1.dp, VeilBorder),
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
          Icon(Icons.Rounded.Speed, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Пинг")
        }
        Button(
          onClick = onOpenImportDialog,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
          Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("Добавить")
        }
      }
    }

    Spacer(Modifier.height(14.dp))

    LazyColumn(
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = PaddingValues(bottom = 24.dp)
    ) {
      if (uiState.vault.subscriptions.isNotEmpty()) {
        item {
          Text(
            text = "ПОДПИСКИ",
            style = MaterialTheme.typography.labelSmall,
            color = VeilMuted,
            modifier = Modifier.padding(vertical = 4.dp)
          )
        }
        items(uiState.vault.subscriptions, key = { "sub_${it.id}" }) { sub ->
          SubscriptionCard(
            group = sub,
            nodeCount = uiState.vault.profiles.count { it.subscriptionId == sub.id },
            isBusy = uiState.isBusy,
            onRefresh = { onRefreshSubscription(sub.id) },
            onDelete = { onDeleteSubscription(sub.id) }
          )
        }
      }

      item {
        Text(
          text = "УЗЛЫ VLESS",
          style = MaterialTheme.typography.labelSmall,
          color = VeilMuted,
          modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
        )
      }

      if (uiState.vault.profiles.isEmpty()) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = VeilSurface),
            border = BorderStroke(1.dp, VeilBorder),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text("Список серверов пуст", style = MaterialTheme.typography.titleMedium)
              Spacer(Modifier.height(4.dp))
              Text(
                "Нажмите «Добавить» и вставьте vless:// ссылку или HTTPS-подписку.",
                style = MaterialTheme.typography.bodySmall,
                color = VeilMuted
              )
            }
          }
        }
      } else {
        items(uiState.vault.profiles, key = { it.id }) { profile ->
          val isSelected = profile.id == uiState.vault.activeProfileId
          val isPinging = profile.id in uiState.pingingIds
          ProfileCard(
            profile = profile,
            isSelected = isSelected,
            isPinging = isPinging,
            onSelect = { onSelectProfile(profile.id) },
            onPing = { onPingSingle(profile.id) },
            onDelete = { onDeleteProfile(profile.id) }
          )
        }
      }
    }
  }
}

@Composable
private fun SubscriptionCard(
  group: SubscriptionGroup,
  nodeCount: Int,
  isBusy: Boolean,
  onRefresh: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = VeilSurface),
    border = BorderStroke(1.dp, VeilBorder),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier.fillMaxWidth()
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          text = "Узлов: $nodeCount · HTTPS",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )
      }
      Row {
        IconButton(onClick = onRefresh, enabled = !isBusy) {
          Icon(Icons.Rounded.Refresh, contentDescription = "Обновить", tint = VeilCyan)
        }
        IconButton(onClick = onDelete, enabled = !isBusy) {
          Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить", tint = VeilMuted)
        }
      }
    }
  }
}

@Composable
private fun ProfileCard(
  profile: ServerProfile,
  isSelected: Boolean,
  isPinging: Boolean,
  onSelect: () -> Unit,
  onPing: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = VeilSurface),
    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) VeilEmerald else VeilBorder),
    shape = RoundedCornerShape(14.dp),
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onSelect() }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (isSelected) {
            Icon(
              Icons.Rounded.CheckCircle,
              contentDescription = null,
              tint = VeilEmerald,
              modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
          }
          Text(
            text = profile.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(Modifier.height(3.dp))
        Text(
          text = "${profile.endpointSummary} · ${profile.host}:${profile.port}",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Spacer(Modifier.width(8.dp))

      if (isPinging) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
      } else {
        LatencyBadge(latencyMs = profile.latencyMs, onClick = onPing)
      }

      IconButton(onClick = onDelete) {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Удалить", tint = VeilMuted)
      }
    }
  }
}

@Composable
private fun LatencyBadge(latencyMs: Long?, onClick: () -> Unit) {
  val (text, color) = when {
    latencyMs == null -> "Пинг" to VeilMuted
    latencyMs < 0 -> "Таймаут" to VeilRed
    latencyMs < 180 -> "${latencyMs} ms" to VeilEmerald
    latencyMs < 450 -> "${latencyMs} ms" to VeilAmber
    else -> "${latencyMs} ms" to VeilRed
  }
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = color.copy(alpha = 0.14f),
    modifier = Modifier.clickable { onClick() }
  ) {
    Text(
      text = text,
      style = MaterialTheme.typography.labelSmall,
      color = color,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
    )
  }
}
