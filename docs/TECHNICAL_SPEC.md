# Technical specification

## Product
A personal Android TV app for a single title: «Очень странные дела» on the configured LordSerial website. No public release and no backend.

## Device
Xiaomi Mi TV 4A, model identifier MiTV-MOOQ1, Android TV 11.

## Architecture
- Kotlin + Jetpack Compose for TV for the native launch surface.
- Android WebView for the existing website and its embedded player.
- No account, credentials, or site scraping assumptions.
- System VPN/network settings remain untouched.

## WebView policy
JavaScript, DOM storage and cookies are enabled for site/player compatibility. Third-party cookies are enabled. File/content access is disabled, mixed content is blocked, and non-HTTP(S) top-level navigation is blocked. No JavaScript bridge is exposed.

## MVP acceptance
1. App appears in Android TV launcher.
2. Remote can focus and activate the start button.
3. Series website loads through the TV's existing network connection.
4. Back returns through WebView history and then to the native start screen.
5. HTML5 video can enter and leave fullscreen.
6. Test all three available player options before choosing a native playback implementation.
