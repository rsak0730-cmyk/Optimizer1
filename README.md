# Smart RAM Optimizer

A Kotlin Android starter project for a Shizuku-assisted smart focus optimizer.

## Included
- RAM and storage overview.
- Installed-app list with selection.
- Shizuku availability/permission status.
- Usage Access setup shortcut for foreground-app detection.
- Smart Focus toggle and a protected-app list.
- Manual force-stop action for selected apps using Shizuku shell, with confirmation.
- GitHub Actions workflow to build a debug APK.

## Important limitations
Android does not expose a universal API to freeze every other app's RAM use. Cached RAM is often beneficial and Android manages it itself. This app uses `am force-stop` only for apps the user explicitly selects and confirms; it does not automatically kill every app. Force-stopping an app can stop notifications/background work until the app is opened again. Do not select phone, launcher, accessibility, keyboard, or other critical apps.

Shizuku must be installed and started separately. On non-rooted devices, start it through Wireless debugging (Android 11+) or ADB. Shizuku's ADB identity is limited and commands can vary by Android version/manufacturer.

## Build APK
Push the project to GitHub. Open **Actions** → **Build Debug APK** → **Run workflow**. When complete, download the `SmartRAMOptimizer-debug-apk` artifact. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install
Install the downloaded APK, open it, grant Usage Access when prompted, and start Shizuku separately. Use the app list to select only apps you are comfortable force-stopping.
