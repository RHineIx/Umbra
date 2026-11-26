# Umbra

A lightweight Xposed module for tweaking Android apps. Uses DexKit to keep hooks dynamic across app updates.

## Features

- **Gboard** — Custom clipboard limits, retention and spacebar icons.
- **Google Play** — Account switching for in-app purchases.
- **Pydroid 3** — App tweaks.
- **Grafika** — UI cleanup and tweaks.
- **Showly** — App tweaks.

## Requirements

- Android 8.0+
- ARM64 (arm64-v8a)
- Xposed framework with API 102 support.

## Installation

1. Grab the latest APK from [Releases](https://github.com/RHineIx/Umbra/releases).
2. Enable Umbra and select the required app scopes.
3. Restart the target apps.

Built with Kotlin, Compose, libxposed and DexKit.
