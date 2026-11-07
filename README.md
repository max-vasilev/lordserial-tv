# LordSerial TV

Personal Android TV player for «Очень странные дела».

The app uses a native Compose for TV interface for season/episode selection and playback controls. The LordSerial website itself is not displayed. Player 1 is loaded inside a minimal full-screen playback shell.

## Target device
- Xiaomi Mi TV 4A (MiTV-MOOQ1)
- Android TV 11
- Minimum Android: 6.0 (API 23)

## Current playback implementation
- Player 1 embed only; Players 2 and 3 are intentionally excluded.
- Existing system VPN/network settings are used unchanged.
- Playback runs in Android WebView because the provider's direct media stream and player-control protocol have not yet been verified for Media3.
- Native play/pause and ±10-second controls work when the embedded player exposes a same-origin HTML5 video element. Remote media keys are also forwarded to the player.
- Per-episode resume is saved when the embedded video element is accessible. Cross-origin provider frames may limit native controls and progress recovery.
- Embedded fullscreen requests are handled by the app.

## Build
Requires JDK 17 and Android SDK Platform 36.

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

This is a personal project. No public app distribution is planned.
