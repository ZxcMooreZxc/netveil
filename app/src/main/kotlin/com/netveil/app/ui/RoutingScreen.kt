package com.netveil.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.netveil.app.model.RoutingMode
import com.netveil.app.model.SplitAppMode
import com.netveil.app.ui.theme.VeilBorder
import com.netveil.app.ui.theme.VeilCyan
import com.netveil.app.ui.theme.VeilEmerald
import com.netveil.app.ui.theme.VeilMuted
import com.netveil.app.ui.theme.VeilSurface

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutingScreen(
  uiState: MainUiState,
  onSelectRoutingMode: (RoutingMode) -> Unit,
  onToggleAutoFailover: (Boolean) -> Unit,
  onToggleTlsFragment: (Boolean) -> Unit,
  onToggleBlockIpv6: (Boolean) -> Unit,
  onAddDomain: (String, Boolean) -> Unit,
  onRemoveDomain: (String, Boolean) -> Unit,
  onSetSplitAppMode: (SplitAppMode) -> Unit,
  onToggleAppPackage: (String) -> Unit,
  onCheckUpdates: () -> Unit,
  onDownloadUpdate: () -> Unit,
  onInstallReadyUpdate: () -> Unit
) {
  val routing = uiState.vault.routing
  var directInput by remember { mutableStateOf("") }
  var proxyInput by remember { mutableStateOf("") }
  var showAppsDialog by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Text("Маршруты и защита", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

    // Resilience & Anti-DPI toggles
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Устойчивость и анти-утечки", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        SettingSwitchRow(
          title = "Auto-Failover (leastPing)",
          subtitle = "Автоматически выбирает самый быстрый узел из подписки (может переключить с выбранного вручную сервера)",
          checked = routing.autoFailover,
          onCheckedChange = onToggleAutoFailover
        )
        SettingSwitchRow(
          title = "TLS Fragment",
          subtitle = "Дробление TLS ClientHello для нестабильных сетей (может снижать скорость)",
          checked = routing.tlsFragment,
          onCheckedChange = onToggleTlsFragment
        )
        SettingSwitchRow(
          title = "Блокировка утечек IPv6",
          subtitle = "Перехват IPv6 внутри TUN и приоритет IPv4 для защиты от обхода VPN",
          checked = routing.blockIpv6Leaks,
          onCheckedChange = onToggleBlockIpv6
        )
      }
    }

    // Domain Whitelists
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Белый список (напрямую, мимо VPN)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = directInput,
            onValueChange = { directInput = it },
            placeholder = { Text("например: ozon.ru") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          IconButton(onClick = {
            if (directInput.isNotBlank()) {
              onAddDomain(directInput, true)
              directInput = ""
            }
          }) {
            Icon(Icons.Rounded.Add, contentDescription = "Добавить домен", tint = VeilCyan)
          }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          routing.directDomains.forEach { domain ->
            InputChip(
              selected = false,
              onClick = { onRemoveDomain(domain, true) },
              label = { Text(domain) },
              trailingIcon = { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(14.dp)) }
            )
          }
        }

        Spacer(Modifier.height(14.dp))
        Text("Через VPN (приоритетный список)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = proxyInput,
            onValueChange = { proxyInput = it },
            placeholder = { Text("например: github.com") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          IconButton(onClick = {
            if (proxyInput.isNotBlank()) {
              onAddDomain(proxyInput, false)
              proxyInput = ""
            }
          }) {
            Icon(Icons.Rounded.Add, contentDescription = "Добавить домен", tint = VeilCyan)
          }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          routing.proxyDomains.forEach { domain ->
            InputChip(
              selected = false,
              onClick = { onRemoveDomain(domain, false) },
              label = { Text(domain) },
              trailingIcon = { Icon(Icons.Rounded.Close, null, modifier = Modifier.size(14.dp)) }
            )
          }
        }
      }
    }

    // Per-App Split Tunneling
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp)) {
        Text("Приложения (Split Tunneling)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SplitAppMode.entries.forEach { mode ->
          FilterChip(
            selected = routing.splitAppMode == mode,
            onClick = { onSetSplitAppMode(mode) },
            label = { Text(mode.title) },
            modifier = Modifier.fillMaxWidth()
          )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
          onClick = { showAppsDialog = true },
          border = BorderStroke(1.dp, VeilBorder),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Text("Выбрать приложения (выбрано: ${routing.selectedAppPackages.size})")
        }
      }
    }

    // Security Audit Card
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilEmerald.copy(alpha = 0.4f)),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.VerifiedUser, null, tint = VeilEmerald, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Аудит безопасности клиента", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = VeilEmerald)
        }
        AuditLine("Шифрование профилей: Android Keystore AES-256-GCM")
        AuditLine("Обновление подписок: только HTTPS + защита от SSRF")
        AuditLine("Санитизация URI: allowInsecure отключён, bind только 127.0.0.1")
        AuditLine("Резервное копирование (allowBackup): отключено")
        AuditLine("Маскировка UUID, ключей Reality и IP в логах")
      }
    }

    // In-App Secure Updater Card
    Card(
      colors = CardDefaults.cardColors(containerColor = VeilSurface),
      border = BorderStroke(1.dp, VeilBorder),
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.fillMaxWidth()
    ) {
      Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Rounded.SystemUpdate, null, tint = VeilCyan, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text(
            text = "Обновления (v${uiState.appVersion})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
        }
        Text(
          text = "Загрузка только с GitHub Releases (HTTPS) с проверкой SHA-256 сертификата подписи.",
          style = MaterialTheme.typography.bodySmall,
          color = VeilMuted
        )

        val rel = uiState.availableRelease
        val dlPct = uiState.updateDownloadPct
        val readyApk = uiState.readyUpdateApk

        if (readyApk != null) {
          Button(
            onClick = onInstallReadyUpdate,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Установить v${rel?.versionName ?: ""} (подпись проверена)")
          }
        } else if (dlPct != null) {
          LinearProgressIndicator(
            progress = { dlPct / 100f },
            modifier = Modifier.fillMaxWidth()
          )
          Text("Скачивание и проверка: $dlPct%", style = MaterialTheme.typography.bodySmall, color = VeilMuted)
        } else if (rel != null) {
          Text(
            text = "Доступна v${rel.versionName}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = VeilEmerald
          )
          Button(
            onClick = onDownloadUpdate,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text("Скачать v${rel.versionName}")
          }
        } else {
          OutlinedButton(
            onClick = onCheckUpdates,
            enabled = !uiState.isCheckingUpdate,
            border = BorderStroke(1.dp, VeilBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(if (uiState.isCheckingUpdate) "Проверка..." else "Проверить обновления")
          }
        }
      }
    }

    Spacer(Modifier.height(12.dp))
  }

  if (showAppsDialog) {
    AppPickerDialog(
      apps = uiState.installedApps,
      selected = routing.selectedAppPackages,
      onToggle = onToggleAppPackage,
      onDismiss = { showAppsDialog = false }
    )
  }
}

@Composable
private fun SettingSwitchRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = VeilMuted)
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

@Composable
private fun AuditLine(text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Rounded.Lock, null, tint = VeilEmerald, modifier = Modifier.size(13.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, style = MaterialTheme.typography.bodySmall, color = VeilMuted)
  }
}

@Composable
private fun AppPickerDialog(
  apps: List<InstalledAppInfo>,
  selected: Set<String>,
  onToggle: (String) -> Unit,
  onDismiss: () -> Unit
) {
  var query by remember { mutableStateOf("") }
  val filtered = remember(apps, query) {
    if (query.isBlank()) apps
    else apps.filter {
      it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Выбор приложений") },
    text = {
      Column(modifier = Modifier.height(380.dp)) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("Поиск приложения...") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          filtered.forEach { app ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(app.packageName) }
                .padding(vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Checkbox(
                checked = app.packageName in selected,
                onCheckedChange = { onToggle(app.packageName) }
              )
              Column {
                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = VeilMuted)
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Готово")
      }
    }
  )
}
