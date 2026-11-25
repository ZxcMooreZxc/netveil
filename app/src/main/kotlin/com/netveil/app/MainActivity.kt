package com.netveil.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.netveil.app.model.VpnConnectionState
import com.netveil.app.ui.HomeScreen
import com.netveil.app.ui.LogsScreen
import com.netveil.app.ui.MainViewModel
import com.netveil.app.ui.RoutingScreen
import com.netveil.app.ui.ServersScreen
import com.netveil.app.ui.theme.NetVeilTheme
import com.netveil.app.ui.theme.VeilBg
import com.netveil.app.ui.theme.VeilSurface
import com.netveil.app.vpn.NetVeilVpnService

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    handleDeepLink(intent)

    setContent {
      NetVeilTheme {
        NetVeilRoot(viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleDeepLink(intent)
  }

  private fun handleDeepLink(intent: Intent?) {
    val data = intent?.data ?: return
    if (data.scheme.equals("vless", ignoreCase = true)) {
      viewModel.importInput(data.toString())
    }
  }
}

@Composable
private fun NetVeilRoot(viewModel: MainViewModel) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val vpnStatus by viewModel.vpnStatus.collectAsStateWithLifecycle()
  val logs by viewModel.logs.collectAsStateWithLifecycle()

  var currentTab by rememberSaveable { mutableIntStateOf(0) }
  var showImportDialog by rememberSaveable { mutableStateOf(false) }
  val snackbarHostState = remember { SnackbarHostState() }

  val notificationLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { }

  val vpnPermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      NetVeilVpnService.start(context)
    }
  }

  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  LaunchedEffect(uiState.bannerMessage) {
    val msg = uiState.bannerMessage ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(msg)
    viewModel.dismissBanner()
  }

  val toggleConnect = {
    val isActive = vpnStatus.state == VpnConnectionState.CONNECTED ||
      vpnStatus.state == VpnConnectionState.CONNECTING ||
      vpnStatus.state == VpnConnectionState.RECONNECTING
    if (isActive) {
      NetVeilVpnService.stop(context)
    } else {
      val prepareIntent = VpnService.prepare(context)
      if (prepareIntent != null) {
        vpnPermissionLauncher.launch(prepareIntent)
      } else {
        NetVeilVpnService.start(context)
      }
    }
  }

  val pasteFromClipboard = {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clipText = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    viewModel.importInput(clipText)
  }

  Scaffold(
    containerColor = VeilBg,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    bottomBar = {
      NavigationBar(containerColor = VeilSurface) {
        NavigationBarItem(
          selected = currentTab == 0,
          onClick = { currentTab = 0 },
          icon = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
          label = { Text("VPN") }
        )
        NavigationBarItem(
          selected = currentTab == 1,
          onClick = { currentTab = 1 },
          icon = { Icon(Icons.Rounded.Dns, contentDescription = null) },
          label = { Text("Серверы") }
        )
        NavigationBarItem(
          selected = currentTab == 2,
          onClick = { currentTab = 2 },
          icon = { Icon(Icons.Rounded.Route, contentDescription = null) },
          label = { Text("Маршруты") }
        )
        NavigationBarItem(
          selected = currentTab == 3,
          onClick = { currentTab = 3 },
          icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
          label = { Text("Журнал") }
        )
      }
    }
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
    ) {
      when (currentTab) {
        0 -> HomeScreen(
          uiState = uiState,
          vpnStatus = vpnStatus,
          onToggleConnect = toggleConnect,
          onPasteFromClipboard = pasteFromClipboard,
          onOpenImportDialog = { showImportDialog = true },
          onSelectRoutingMode = viewModel::updateRoutingMode,
          onOpenServersTab = { currentTab = 1 }
        )
        1 -> ServersScreen(
          uiState = uiState,
          onSelectProfile = viewModel::selectProfile,
          onDeleteProfile = viewModel::deleteProfile,
          onPingSingle = viewModel::pingSingle,
          onPingAll = viewModel::pingAllServers,
          onRefreshSubscription = viewModel::refreshSubscription,
          onDeleteSubscription = viewModel::deleteSubscription,
          onOpenImportDialog = { showImportDialog = true }
        )
        2 -> RoutingScreen(
          uiState = uiState,
          onSelectRoutingMode = viewModel::updateRoutingMode,
          onToggleAutoFailover = viewModel::toggleAutoFailover,
          onToggleTlsFragment = viewModel::toggleTlsFragment,
          onToggleBlockIpv6 = viewModel::toggleBlockIpv6Leaks,
          onAddDomain = viewModel::addDomainRule,
          onRemoveDomain = viewModel::removeDomainRule,
          onSetSplitAppMode = viewModel::setSplitAppMode,
          onToggleAppPackage = viewModel::toggleAppPackage,
          onCheckUpdates = viewModel::checkForUpdates,
          onDownloadUpdate = viewModel::downloadAvailableUpdate,
          onInstallReadyUpdate = {
            val apkFile = uiState.readyUpdateApk ?: return@RoutingScreen
            val uri = FileProvider.getUriForFile(
              context,
              "${context.packageName}.fileprovider",
              apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
              setDataAndType(uri, "application/vnd.android.package-archive")
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
          }
        )
        3 -> LogsScreen(
          coreVersion = uiState.coreVersion,
          logs = logs,
          onCopyLogs = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            val text = logs.joinToString("\n") { "${it.timestamp} [${it.level}] ${it.message}" }
            cm?.setPrimaryClip(ClipData.newPlainText("NetVeil Logs", text))
          },
          onClearLogs = viewModel::clearLogs
        )
      }
    }
  }

  if (showImportDialog) {
    ImportConfigDialog(
      onDismiss = { showImportDialog = false },
      onImport = { raw ->
        viewModel.importInput(raw)
        showImportDialog = false
      }
    )
  }
}

@Composable
private fun ImportConfigDialog(
  onDismiss: () -> Unit,
  onImport: (String) -> Unit
) {
  var text by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Добавить сервер или подписку") },
    text = {
      Column {
        Text("Поддерживаются vless:// ссылки, несколько строк, Base64 и HTTPS-ссылки подписок.")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          placeholder = { Text("vless://... или https://...") },
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(onClick = { onImport(text) }) {
        Text("Импортировать")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена")
      }
    }
  )
}
