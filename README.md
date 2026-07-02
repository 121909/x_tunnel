# XTunnel Android Client

This repository now contains three layers:

- `xtunnel` Go package: reusable tunnel runtime and local SOCKS5 proxy client
- `cmd/xtunnel`: CLI wrapper for desktop/server-side testing
- `android/`: Android app shell that embeds the Go bridge AAR

## What The Android App Does

This Android app is a local proxy client, not a VPN.

- It starts a local listener such as `socks5://127.0.0.1:1080`
- It forwards traffic through your configured `ws://` or `wss://` tunnel server
- It does not capture device-wide traffic
- It is meant for apps or tools that can use a manual proxy

## Key Go Entry Points

- Library runtime: [runtime.go](./runtime.go)
- Android bridge: [mobile/bridge/bridge.go](./mobile/bridge/bridge.go)
- CLI main: [cmd/xtunnel/main.go](./cmd/xtunnel/main.go)

The bridge exports:

- `start(configJson)`
- `stop()`
- `statusJSON()`
- `logs()`

## Build Requirements

- Go 1.26+
- JDK 17+
- Android SDK
- Android NDK `29.0.14206865` or compatible
- `gomobile` and `gobind`

Environment defaults used by the scripts:

```bash
ANDROID_HOME=/opt/android-sdk
ANDROID_NDK_VERSION=29.0.14206865
```

## Build The Android AAR

```bash
./tools/build-android-aar.sh
```

Output:

- `android/app/libs/xtunnel.aar`

## Build The Debug APK

```bash
./tools/build-android-debug.sh
```

Output:

- `android/app/build/outputs/apk/debug/app-debug.apk`

## Generate A Release Keystore

```bash
./tools/generate-release-keystore.sh
```

This creates local-only signing material:

- `android/signing/xtunnel-release.jks`
- `android/keystore.properties`

Use [keystore.properties.example](./android/keystore.properties.example) as the format reference if you want to replace the generated key with your own.

## Build Signed Release Artifacts

```bash
./tools/build-android-release.sh
```

Outputs:

- `android/app/build/outputs/apk/release/app-release.apk`
- `android/app/build/outputs/bundle/release/app-release.aab`

Notes:

- `android/keystore.properties` is ignored on purpose.
- The generated keystore is suitable for local release builds, but you should rotate it before distributing a production app.

## GitHub Actions Release

The repository includes [release.yml](./.github/workflows/release.yml).

- Trigger: push a tag like `v1.0.0`, or run it manually with `workflow_dispatch`
- Outputs: signed `apk`, signed `aab`, and `xtunnel.aar`
- The workflow publishes a GitHub Release on tag pushes

Required repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

You can create `ANDROID_KEYSTORE_BASE64` from a local keystore with:

```bash
base64 -w0 android/signing/xtunnel-release.jks
```

## Run The Android App

1. Open the app.
2. Fill in the forward URL, token, local SOCKS5 port, and TLS/ECH settings.
3. Tap `Start`.
4. Point a proxy-capable client at:
   - SOCKS5: `127.0.0.1:1080`

## Default Config Shape

```json
{
  "listen_addrs": [
    "socks5://127.0.0.1:1080"
  ],
  "forward_addr": "wss://example.com/tunnel",
  "token": "",
  "connection_num": 3,
  "dns_server": "https://doh.pub/dns-query",
  "ech_domain": "cloudflare-ech.com",
  "fallback": false,
  "insecure": false,
  "udp_block_ports": [443]
}
```

## Notes

- `insecure=true` is only for testing.
- `fallback=true` disables ECH and uses standard TLS.
- `target_ips` and `ip_strategy` are optional advanced routing controls.
- Without TUN/VpnService, apps must opt into manual proxy usage themselves.
