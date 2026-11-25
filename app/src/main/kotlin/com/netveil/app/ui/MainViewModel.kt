package com.netveil.app.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netveil.app.core.AppLogger
import com.netveil.app.core.LogEntry
import com.netveil.app.core.SubscriptionFetcher
import com.netveil.app.core.VlessUriParser
import com.netveil.app.core.XrayConfigBuilder
import com.netveil.app.crypto.SecureVault
import com.netveil.app.model.RoutingMode
import com.netveil.app.model.RoutingSettings
import com.netveil.app.model.ServerProfile
import com.netveil.app.model.SplitAppMode
import com.netveil.app.model.VaultData
import com.netveil.app.model.VpnStatusSnapshot
import com.netveil.app.vpn.VpnStateBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray

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
  val coreVersion: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val vaultStore = SecureVault(application)

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
    val updated = transform(_uiState.value.vault)
    vaultStore.save(updated)
    _uiState.value = _uiState.value.copy(vault = updated)
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
    updateVault { it.copy(activeProfileId = profileId) }
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

    viewModelScope.launch {
      val semaphore = Semaphore(4)
      _uiState.value = _uiState.value.copy(pingingIds = profiles.map { it.id }.toSet())

      val results = profiles.map { profile ->
        async(Dispatchers.IO) {
          semaphore.withPermit {
            val delayMs = measureProfileDelay(profile)
            profile.id to delayMs
          }
        }
      }.awaitAll().toMap()

      val now = System.currentTimeMillis()
      updateVault { current ->
        current.copy(
          profiles = current.profiles.map { p ->
            if (results.containsKey(p.id)) {
              p.copy(latencyMs = results[p.id], lastTestedAt = now)
            } else {
              p
            }
          }
        )
      }
      _uiState.value = _uiState.value.copy(pingingIds = emptySet())
    }
  }

  fun pingSingle(profileId: String) {
    val profile = _uiState.value.vault.profiles.firstOrNull { it.id == profileId } ?: return
    viewModelScope.launch {
      _uiState.value = _uiState.value.copy(pingingIds = _uiState.value.pingingIds + profileId)
      val delayMs = withContext(Dispatchers.IO) { measureProfileDelay(profile) }
      val now = System.currentTimeMillis()
      updateVault { current ->
        current.copy(
          profiles = current.profiles.map {
            if (it.id == profileId) it.copy(latencyMs = delayMs, lastTestedAt = now) else it
          }
        )
      }
      _uiState.value = _uiState.value.copy(pingingIds = _uiState.value.pingingIds - profileId)
    }
  }

  private fun measureProfileDelay(profile: ServerProfile): Long {
    return try {
      val config = XrayConfigBuilder.buildPingConfig(profile)
      Libv2ray.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
    } catch (_: Exception) {
      -1L
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
