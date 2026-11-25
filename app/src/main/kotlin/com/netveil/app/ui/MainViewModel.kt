package com.netveil.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netveil.app.BuildConfig
import com.netveil.app.core.AppLogger
import com.netveil.app.core.AppUpdater
import com.netveil.app.core.LogEntry
import com.netveil.app.core.ReleaseInfo
import com.netveil.app.core.SubscriptionFetcher
import com.netveil.app.core.VlessUriParser
import com.netveil.app.core.XrayConfigBuilder
import com.netveil.app.crypto.SecureVault
import com.netveil.app.model.RoutingMode
import com.netveil.app.model.RoutingSettings
import com.netveil.app.model.ServerProfile
import com.netveil.app.model.SplitAppMode
import com.netveil.app.model.VaultData
import com.netveil.app.model.VpnConnectionState
import com.netveil.app.model.VpnStatusSnapshot
import com.netveil.app.vpn.NetVeilVpnService
import com.netveil.app.vpn.VpnStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libv2ray.Libv2ray
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

data class InstalledAppInfo(
  val packageName: String,
  val label: String
)

data class MainUiState(
  val vault: VaultData = VaultData(),
  val isBusy: Boolean = false,
  val pingingIds: Set<String> = emptySet(),
  val bannerMessage: String? = null,
  val bannerIsError: Boolean = false,
  val installedApps: List<InstalledAppInfo> = emptyList(),
  val coreVersion: String = "",
  val appVersion: String = BuildConfig.VERSION_NAME,
  val isCheckingUpdate: Boolean = false,
  val availableRelease: ReleaseInfo? = null,
  val updateDownloadPct: Int? = null,
  val readyUpdateApk: File? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val vaultStore = SecureVault(application)
  private val stateMutex = Mutex()
  private var pingAllJob: Job? = null

  private val _uiState = MutableStateFlow(
    MainUiState(
      vault = vaultStore.load(),
      coreVersion = runCatching { Libv2ray.checkVersionX() }.getOrDefault("Xray-core")
    )
  )
  val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

  val vpnStatus: StateFlow<VpnStatusSnapshot> = VpnStateBus.status
  val logs: StateFlow<List<LogEntry>> = AppLogger.logs

  init {
    loadInstalledApps()
  }

  fun dismissBanner() {
    _uiState.value = _uiState.value.copy(bannerMessage = null, bannerIsError = false)
  }

  private fun showBanner(message: String, isError: Boolean = false) {
    _uiState.value = _uiState.value.copy(bannerMessage = message, bannerIsError = isError)
  }

  private fun updateVault(transform: (VaultData) -> VaultData) {
    var savedVault: VaultData? = null
    _uiState.update { state ->
      val updated = transform(state.vault)
      savedVault = updated
      state.copy(vault = updated)
    }
    savedVault?.let { vaultStore.save(it) }
  }

  fun importInput(rawInput: String) {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      showBanner("Вставьте ссылку vless:// или HTTPS-подписку", isError = true)
      return
    }

    if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
      viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isBusy = true)
        try {
          val fetched = SubscriptionFetcher.fetch(trimmed)
          updateVault { current ->
            val mergedProfiles = current.profiles + fetched.profiles
            current.copy(
              profiles = mergedProfiles,
              subscriptions = current.subscriptions + fetched.group,
              activeProfileId = current.activeProfileId ?: fetched.profiles.firstOrNull()?.id
            )
          }
          AppLogger.info("Импортирована подписка '${fetched.group.name}': ${fetched.profiles.size} узлов")
          val extra = if (fetched.rejectedCount > 0) " (отфильтровано небезопасных: ${fetched.rejectedCount})" else ""
          showBanner("Подписка добавлена: ${fetched.profiles.size} узлов$extra")
        } catch (e: Exception) {
          AppLogger.warn("Ошибка импорта подписки: ${e.message}")
          showBanner(e.message ?: "Не удалось загрузить подписку", isError = true)
        } finally {
          _uiState.value = _uiState.value.copy(isBusy = false)
        }
      }
      return
    }

    val batch = VlessUriParser.parseSubscriptionPayload(trimmed)
    if (batch.profiles.isEmpty()) {
      showBanner("Не найдено корректных VLESS-конфигов", isError = true)
      return
    }

    updateVault { current ->
      current.copy(
        profiles = current.profiles + batch.profiles,
        activeProfileId = current.activeProfileId ?: batch.profiles.first().id
      )
    }
    AppLogger.info("Добавлено VLESS-конфигов: ${batch.profiles.size}")
    val extra = if (batch.rejectedCount > 0) " (отклонено: ${batch.rejectedCount})" else ""
    showBanner("Добавлено серверов: ${batch.profiles.size}$extra")
  }

  fun refreshSubscription(groupId: String) {
    val group = _uiState.value.vault.subscriptions.firstOrNull { it.id == groupId } ?: return
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isBusy = true)
      try {
        val fetched = SubscriptionFetcher.fetch(group.url, existingGroup = group)
        updateVault { current ->
          val keptProfiles = current.profiles.filterNot { it.subscriptionId == groupId }
          val newProfiles = keptProfiles + fetched.profiles
          val activeStillExists = newProfiles.any { it.id == current.activeProfileId }
          current.copy(
            profiles = newProfiles,
            subscriptions = current.subscriptions.map { if (it.id == groupId) fetched.group else it },
            activeProfileId = if (activeStillExists) current.activeProfileId else fetched.profiles.firstOrNull()?.id
          )
        }
        AppLogger.info("Обновлена подписка '${fetched.group.name}': ${fetched.profiles.size} узлов")
        showBanner("Обновлено: ${fetched.profiles.size} узлов")
      } catch (e: Exception) {
        AppLogger.warn("Ошибка обновления подписки: ${e.message}")
        showBanner(e.message ?: "Ошибка обновления подписки", isError = true)
      } finally {
        _uiState.value = _uiState.value.copy(isBusy = false)
      }
    }
  }

  fun deleteSubscription(groupId: String) {
    updateVault { current ->
      val remaining = current.profiles.filterNot { it.subscriptionId == groupId }
      val activeId = if (remaining.any { it.id == current.activeProfileId }) {
        current.activeProfileId
      } else {
        remaining.firstOrNull()?.id
      }
      current.copy(
        profiles = remaining,
        subscriptions = current.subscriptions.filterNot { it.id == groupId },
        activeProfileId = activeId
      )
    }
  }

  fun selectProfile(profileId: String) {
    val target = _uiState.value.vault.profiles.firstOrNull { it.id == profileId } ?: return
    if (_uiState.value.vault.activeProfileId == profileId) return

    updateVault { it.copy(activeProfileId = profileId) }
    val state = vpnStatus.value.state
    if (state == VpnConnectionState.CONNECTED ||
      state == VpnConnectionState.CONNECTING ||
      state == VpnConnectionState.RECONNECTING
    ) {
      NetVeilVpnService.start(getApplication())
      showBanner("Переключение на ${target.name}...")
    }
  }

  fun deleteProfile(profileId: String) {
    updateVault { current ->
      val remaining = current.profiles.filterNot { it.id == profileId }
      val nextActive = if (current.activeProfileId == profileId) remaining.firstOrNull()?.id else current.activeProfileId
      current.copy(profiles = remaining, activeProfileId = nextActive)
    }
  }

  fun pingAllServers() {
    val profiles = _uiState.value.vault.profiles
    if (profiles.isEmpty()) return

    pingAllJob?.cancel()
    pingAllJob = viewModelScope.launch {
      val semaphore = Semaphore(10)
      _uiState.value = _uiState.value.copy(pingingIds = profiles.map { it.id }.toSet())

      try {
        profiles.map { profile ->
          async(Dispatchers.IO) {
            semaphore.withPermit {
              val delayMs = measureProfileDelay(profile)
              val testedAt = System.currentTimeMillis()
              stateMutex.withLock {
                _uiState.update { state ->
                  val updatedVault = state.vault.copy(
                    profiles = state.vault.profiles.map { p ->
                      if (p.id == profile.id) p.copy(latencyMs = delayMs, lastTestedAt = testedAt) else p
                    }
                  )
                  state.copy(
                    vault = updatedVault,
                    pingingIds = state.pingingIds - profile.id
                  )
                }
              }
            }
          }
        }.awaitAll()
      } finally {
        stateMutex.withLock {
          vaultStore.save(_uiState.value.vault)
          _uiState.value = _uiState.value.copy(pingingIds = emptySet())
        }
      }
    }
  }

  fun pingSingle(profileId: String) {
    val profile = _uiState.value.vault.profiles.firstOrNull { it.id == profileId } ?: return
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(pingingIds = _uiState.value.pingingIds + profileId)
      val delayMs = withContext(Dispatchers.IO) { measureProfileDelay(profile) }
      val now = System.currentTimeMillis()
      stateMutex.withLock {
        val updated = _uiState.value.vault.copy(
          profiles = _uiState.value.vault.profiles.map {
            if (it.id == profileId) it.copy(latencyMs = delayMs, lastTestedAt = now) else it
          }
        )
        vaultStore.save(updated)
        _uiState.value = _uiState.value.copy(
          vault = updated,
          pingingIds = _uiState.value.pingingIds - profileId
        )
      }
    }
  }

  private suspend fun measureProfileDelay(profile: ServerProfile): Long {
    // Fast TCP reachability pre-check (2.5s) so dead nodes don't stall Go for 12s each
    val tcpReachable = withContext(Dispatchers.IO) {
      runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(profile.host, profile.port), 2500)
        }
        true
      }.getOrDefault(false)
    }
    if (!tcpReachable) return -1L

    return withTimeoutOrNull(6500L) {
      withContext(Dispatchers.IO) {
        try {
          val config = XrayConfigBuilder.buildPingConfig(profile)
          Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
        } catch (_: Exception) {
          -1L
        }
      }
    } ?: -1L
  }

  fun checkForUpdates() {
    if (_uiState.value.isCheckingUpdate) return
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(isCheckingUpdate = true)
      try {
        val release = AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
        _uiState.value = _uiState.value.copy(availableRelease = release)
        if (release == null) {
          showBanner("У вас установлена последняя версия (${BuildConfig.VERSION_NAME})")
        } else {
          showBanner("Доступно обновление: v${release.versionName}")
        }
      } catch (e: Exception) {
        AppLogger.warn("Ошибка проверки обновлений: ${e.message}")
        showBanner(e.message ?: "Не удалось проверить обновления", isError = true)
      } finally {
        _uiState.value = _uiState.value.copy(isCheckingUpdate = false)
      }
    }
  }

  fun downloadAvailableUpdate() {
    val release = _uiState.value.availableRelease ?: return
    if (_uiState.value.updateDownloadPct != null) return
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(updateDownloadPct = 0, readyUpdateApk = null)
      try {
        val apkFile = AppUpdater.downloadAndVerifyApk(
          context = getApplication(),
          release = release,
          onProgress = { pct ->
            _uiState.value = _uiState.value.copy(updateDownloadPct = pct)
          }
        )
        AppLogger.info("Обновление v${release.versionName} скачано и прошло проверку подписи")
        _uiState.value = _uiState.value.copy(updateDownloadPct = null, readyUpdateApk = apkFile)
      } catch (e: Exception) {
        AppLogger.error("Ошибка обновления: ${e.message}")
        _uiState.value = _uiState.value.copy(updateDownloadPct = null)
        showBanner(e.message ?: "Не удалось скачать обновление", isError = true)
      }
    }
  }

  fun updateRoutingMode(mode: RoutingMode) {
    updateRouting { it.copy(mode = mode) }
  }

  fun toggleAutoFailover(enabled: Boolean) {
    updateRouting { it.copy(autoFailover = enabled) }
  }

  fun toggleTlsFragment(enabled: Boolean) {
    updateRouting { it.copy(tlsFragment = enabled) }
  }

  fun toggleBlockIpv6Leaks(enabled: Boolean) {
    updateRouting { it.copy(blockIpv6Leaks = enabled) }
  }

  fun addDomainRule(rawDomain: String, isDirect: Boolean) {
    val sanitized = VlessUriParser.sanitizeUserDomain(rawDomain)
    if (sanitized == null) {
      showBanner("Некорректный формат домена", isError = true)
      return
    }
    updateRouting { r ->
      if (isDirect) {
        r.copy(directDomains = (r.directDomains + sanitized).distinct())
      } else {
        r.copy(proxyDomains = (r.proxyDomains + sanitized).distinct())
      }
    }
  }

  fun removeDomainRule(domain: String, isDirect: Boolean) {
    updateRouting { r ->
      if (isDirect) {
        r.copy(directDomains = r.directDomains.filterNot { it == domain })
      } else {
        r.copy(proxyDomains = r.proxyDomains.filterNot { it == domain })
      }
    }
  }

  fun setSplitAppMode(mode: SplitAppMode) {
    updateRouting { it.copy(splitAppMode = mode) }
  }

  fun toggleAppPackage(packageName: String) {
    updateRouting { r ->
      val next = if (packageName in r.selectedAppPackages) {
        r.selectedAppPackages - packageName
      } else {
        r.selectedAppPackages + packageName
      }
      r.copy(selectedAppPackages = next)
    }
  }

  private fun updateRouting(transform: (RoutingSettings) -> RoutingSettings) {
    updateVault { current -> current.copy(routing = transform(current.routing)) }
    val state = vpnStatus.value.state
    if (state == VpnConnectionState.CONNECTED ||
      state == VpnConnectionState.CONNECTING ||
      state == VpnConnectionState.RECONNECTING
    ) {
      NetVeilVpnService.start(getApplication())
    }
  }

  fun clearLogs() {
    AppLogger.clear()
  }

  private fun loadInstalledApps() {
    viewModelScope.launch(Dispatchers.IO) {
      val pm = getApplication<Application>().packageManager
      val selfPkg = getApplication<Application>().packageName
      val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
      val resolved = pm.queryIntentActivities(intent, 0)
      val apps = resolved
        .mapNotNull { info ->
          val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
          if (pkg == selfPkg) return@mapNotNull null
          val label = info.loadLabel(pm).toString().trim().ifBlank { pkg }
          InstalledAppInfo(pkg, label)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }

      _uiState.value = _uiState.value.copy(installedApps = apps)
    }
  }
}
