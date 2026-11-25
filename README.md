# NetVeil

Modern, security-hardened Android VLESS / Reality / Subscription VPN client built with **Kotlin**, **Jetpack Compose (Material 3)**, and **Xray-core** (`AndroidLibXrayLite`).

## Features

- **VLESS + Reality + Vision / WS / gRPC / HTTPUpgrade / XHTTP** — Paste single `vless://` links, Base64 blobs, or HTTPS subscription URLs (including clipboard quick-import and `vless://` deep links).
- **Smart Routing & Whitelists**
  - **Smart RU Whitelist**: Routes `geosite:category-ru`, `.ru`, `.рф`, `geoip:ru`, and custom bypass domains directly so local banking and services work without interference.
  - **Global Proxy** or **Strict Whitelist-Only** modes.
  - **Per-App Split Tunneling**: Exclude or include specific installed apps.
- **Anti-Outage Resilience**
  - **Auto-Failover (`leastPing`)**: Automatically switches traffic across healthy nodes in a subscription/pool via Xray `observatory` + `balancers` when a server drops or degrades.
  - **Optional TLS Fragment**: Splits ClientHello packets for unstable networks.
  - **Batch Latency Test**: Concurrent real delay testing across all servers.
- **Quick Settings Tile**: Toggle VPN directly from Android Quick Settings.

## Security Hardening

- **Android Keystore Encryption**: All server profiles, UUIDs, Reality keys, and subscription URLs are encrypted at rest with a 256-bit `AES/GCM/NoPadding` key in the Android Keystore (`SecureVault`).
- **HTTPS-Only Subscriptions + SSRF Guard**: Rejects `http://`, redirects, private/loopback IP targets, and responses over 2 MB.
- **Strict URI Sanitization**: Never executes untrusted remote Xray JSON directly; enforces `127.0.0.1`-only local SOCKS binding and disables `allowInsecure`.
- **Leak Protection**: Captures IPv6 inside TUN (`blockIpv6Leaks`) and routes DNS inside the Xray core (`queryStrategy: UseIPv4` / DoH).
- **No Backup / No Cleartext**: `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, and automatic redaction of UUIDs, public IPs, and Reality keys in logs (`LogSanitizer`).

## Setup & Build

1. Place `libv2ray.aar` from [2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite/releases) into `app/libs/libv2ray.aar`.
2. Run unit tests:
   ```bash
   ./gradlew testDebugUnitTest
   ```
3. Build debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
