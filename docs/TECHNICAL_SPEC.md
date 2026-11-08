# Technical specification

## Product
A personal Android TV application for watching «Очень странные дела». The application must behave as a standalone TV player, not as a website wrapper. The LordSerial site pages are never shown; the Player 1 player itself is shown exactly as the site shows it, operated with the remote.

## Device
Xiaomi Mi TV 4A, model identifier MiTV-MOOQ1, Android TV 11.

## Product experience
- Launch opens directly into the native player interface.
- Native Compose UI owns continue watching, season selection and episode selection. During playback the player's own controls are used, unchanged.
- All interaction is designed for a TV remote: D-pad, OK and Back. No mouse cursor or touch-only interactions.
- The player keeps the playback position per episode itself. The app also notes it when leaving the player, for the progress bars, watched marks and the Continue button, and retains the last selected episode.
- Persist selected season/episode and watched progress locally.
- Modern dark TV-first design with large readable typography, clear focus states and generous spacing.
- No visible website page, browser chrome, website navigation or cursor. The player's own controls are shown, with a visible focus ring.

## Architecture
- Kotlin + Jetpack Compose for TV for the complete user interface.
- AndroidX Media3/ExoPlayer is the preferred playback engine when a direct media stream can be resolved.
- If the verified Player 1 source cannot be consumed directly, the Player 1 embed page is shown full screen as-is. The app only adds remote navigation between its controls (focus ring, OK to press, Left/Right seek on the progress bar) and does not restyle or hide any of it.
- No account, credentials, backend, or public release.
- System VPN/network settings remain untouched.
- Store viewing progress locally, keyed by season and episode.

## Playback source
- Player 1 is the only source currently verified by the user to play on the target TV.
- The Player 1 embed endpoint currently used by the app is `https://api.namy.ws/embed/movie/354?episode={episode}&season={season}`; this is the embed URL, not a verified direct media-stream URL.
- The app loads that endpoint directly, full screen, with "Mobile" removed from the WebView user agent so the player uses its TV layout. The LordSerial site page is not loaded or displayed.
- Players 2 and 3 are not supported in the app.
- The underlying media URL, DRM requirements, and player messaging protocol remain unverified. Do not claim native Media3 playback until those details are established.
- Preserve the website's required cookies/session/network behavior where needed, without exposing its page UI.

## Acceptance criteria
1. App appears in Android TV launcher and opens directly into the native player screen.
2. All navigation is usable with D-pad/OK/Back; focus is always visible and predictable.
3. User can choose all available seasons and episodes from native UI.
4. Selecting an episode starts playback through Player 1 without showing the website page.
5. Every Player 1 control (play/pause, next episode, season/episode/voice lists, volume, settings with quality/speed/subtitles/scale, progress bar) is reachable and usable with D-pad/OK/Back. The app is always full screen; the player's own fullscreen request is declined.
6. Progress per episode is kept by the player itself and survives reopening the episode.
7. Continue Watching opens the last episode; the player resumes it at its saved position.
8. Back returns from playback to the native episode/season interface, never to a website page.
9. Test on Xiaomi Mi TV 4A / Android TV 11; successful CI build alone is not acceptance.
