package com.netveil.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle


import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt


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

import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    // --- Security hardening -------------------------------------------------
    // Prevent screenshots / recent‑apps thumbnail leaks (Play Protect friendly)
    private fun applyScreenSecurity() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    // Prompt biometric auth before showing the UI. If the device has no biometric,
    // the prompt is skipped (fallback to normal launch). This avoids loading the
    // vault without user verification.
    private fun maybeShowBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = androidx.biometric.BiometricPrompt(this, executor,
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If user cancels or device has no biometric, just continue.
                    onSuccess()
                }
            })
        val promptInfo = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Аутентификация")
            .setSubtitle("Разблокировать доступ к VPN‑профилям")
            .setNegativeButtonText("Отмена")
            .build()
        biometricPrompt.authenticate(promptInfo)
    }
    // ------------------------------------------------------------------------

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Apply security hardening before UI is shown
        applyScreenSecurity()
        // Show biometric auth (if available) before loading the app UI
        maybeShowBiometricPrompt {
            enableEdgeToEdge()
            handleDeepLink(intent)
            setContent {
                NetVeilTheme {
                    NetVeilRoot(viewModel = viewModel)
                }
            }
        }
    }
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
          onOpenReleasePage = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
