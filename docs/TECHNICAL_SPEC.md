# Technical specification

## Product
A personal Android TV application for watching «Очень странные дела». The application must behave as a standalone TV player, not as a website wrapper. The website is only a playback source; its pages and controls must never be shown to the user.

## Device
Xiaomi Mi TV 4A, model identifier MiTV-MOOQ1, Android TV 11.

## Product experience
- Launch opens directly into the native player interface.
- Native Compose UI owns all navigation: continue watching, season selection, episode selection, playback controls and settings.
- All interaction is designed for a TV remote: D-pad, OK and Back. No mouse cursor or touch-only interactions.
- Remember the playback position independently for every episode when the embedded player exposes a same-origin HTML5 video element; otherwise retain the last selected episode and report the resume limitation honestly.
- Persist selected season/episode and watched progress locally.
- Modern dark TV-first design with large readable typography, clear focus states and generous spacing.
- No visible website, browser chrome, HTML buttons, website navigation or cursor.

## Architecture
- Kotlin + Jetpack Compose for TV for the complete user interface.
- AndroidX Media3/ExoPlayer is the preferred playback engine when a direct media stream can be resolved.
- If the verified Player 1 source cannot be consumed directly, a player-only embedded playback surface may be used internally, but the website page/UI must remain hidden. Native Compose controls remain the only user-facing controls.
- No account, credentials, backend, or public release.
- System VPN/network settings remain untouched.
- Store viewing progress locally, keyed by season and episode.

## Playback source
- Player 1 is the only source currently verified by the user to play on the target TV.
- The Player 1 embed endpoint currently used by the app is `https://api.namy.ws/embed/movie/354?episode={episode}&season={season}`; this is the embed URL, not a verified direct media-stream URL.
- The app loads that endpoint inside a minimal black full-screen shell. The LordSerial site page is not loaded or displayed.
- Players 2 and 3 are not supported in the app.
- The underlying media URL, DRM requirements, and player messaging protocol remain unverified. Do not claim native Media3 playback until those details are established.
- Preserve the website's required cookies/session/network behavior where needed, without exposing its page UI.

## Acceptance criteria
1. App appears in Android TV launcher and opens directly into the native player screen.
2. All navigation is usable with D-pad/OK/Back; focus is always visible and predictable.
3. User can choose all available seasons and episodes from native UI.
4. Selecting an episode starts playback through Player 1 without showing the website.
5. Playback controls use native Compose controls and remote media keys where the embed exposes a controllable HTML5 video element; embedded fullscreen requests are handled by the app.
6. Progress is saved per episode and restored when the embedded video element is accessible from the wrapper's origin. Cross-origin provider frames may prevent progress inspection and native seek/pause.
7. Continue Watching resumes the last episode at its saved position.
8. Back returns from playback to the native episode/season interface, never to a website page.
9. Test on Xiaomi Mi TV 4A / Android TV 11; successful CI build alone is not acceptance.
