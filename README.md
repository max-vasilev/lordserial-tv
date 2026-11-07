# LordSerial TV

Personal Android TV application for opening «Очень странные дела» on LordSerial.

## Target device
- Xiaomi Mi TV 4A (MiTV-MOOQ1)
- Android TV 11
- Minimum Android: 6.0 (API 23)

## Build
Requires JDK 17 and Android SDK Platform 36.

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Features
- Android TV launcher entry and TV banner
- Focusable native start screen
- Embedded WebView for the selected series
- JavaScript, DOM storage and third-party cookies for site/player compatibility
- Hardware Back navigation and fullscreen HTML5 video
- Uses the device's existing network/VPN configuration

This is a personal project. No public app distribution is planned.
