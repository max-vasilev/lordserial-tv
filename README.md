# LordSerial TV

Personal Android TV player for «Очень странные дела».

The app uses a native Compose for TV interface for season/episode selection. Playback shows Player 1 exactly as the site does, full screen, operated with the remote instead of a cursor. The LordSerial website page itself is not displayed.

## Series screen
Laid out like the TV apps of streaming services: the focused episode's still fills the background, the title and a Continue button sit at the top, and season tabs and a row of episode cards run along the bottom.
- Seasons, episodes and durations come from the player's own playlist (its embed page), so new episodes appear without an app update. The last copy is kept in the app's files, so the screen opens complete at once and works offline; a built-in list is used only before the first successful load.
- Stills are chosen frames built into the APK, so they show at once. `tools/fetch_stills.py fetch` (needs ffmpeg) takes the frame at the second listed for each episode in `tools/stills.json` from the episode's own 720p stream into `app/src/main/assets/stills/`; `tools/fetch_stills.py candidates DIR` makes contact sheets of six frames per episode (from its first half, so no still gives the ending away) to choose from. The stills are frames of the series, so that folder is git-ignored: run the fetch before building. An episode with no built-in still (one added since, or a build without the fetch) gets one made from its stream on the TV, cached, with its number shown until then. The background still changes only once focus rests on a card for 0.3 s.
- The app notes the position of the episode the player is on every 30 seconds and when you leave it (Back or Home), so an episode the player moved on from by itself is marked too: cards show a progress bar or a watched mark, and Continue offers the next episode once one is finished.
- Focusing a season tab switches the row; focus enters the tabs on the current season and the row on the last focused card.

## Target device
- Xiaomi Mi TV 4A (MiTV-MOOQ1)
- Android TV 11
- Minimum Android: 6.0 (API 23)

## Current playback implementation
- Player 1 embed only; Players 2 and 3 are intentionally excluded.
- Existing system VPN/network settings are used unchanged.
- Playback runs in Android WebView because the provider's direct media stream and player-control protocol have not yet been verified for Media3.
- The Player 1 embed page is loaded directly and left unchanged. The WebView user agent drops "Mobile", so the player picks its own TV layout instead of the touch layout.
- Remote control: the arrows move a focus ring between the player's own controls (including the options inside its settings menus), OK presses the focused one, and the first press while the controls are hidden shows them. On the progress bar Left/Right seek 10 seconds and OK plays or pauses. Back steps out of an open settings submenu, then closes an open menu or season/episode/voice list, then returns to the episode screen.
- The player keeps its own playback position per episode; the app remembers the last opened episode for Continue Watching.
- The player's own fullscreen request is declined: the app is already full screen, and WebView would otherwise take the first Back press to leave it.

## Build
Requires JDK 17 and Android SDK Platform 36.

```bash
./gradlew clean testDebugUnitTest assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. For the built-in episode stills, run `tools/fetch_stills.py fetch` first (see Series screen).

This is a personal project. No public app distribution is planned.
