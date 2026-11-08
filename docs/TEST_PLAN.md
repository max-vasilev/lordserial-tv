# TV test plan

## Native interface
- Launch app: native player screen appears immediately; no website is visible.
- Navigate with D-pad only; verify focus indicator and predictable focus order.
- Open each season and episode; verify labels and episode counts.
- Back returns to the previous native screen.
- Continue Watching opens the last episode.

## Playback
- Verify Player 1 playback on Xiaomi Mi TV 4A / Android TV 11 with the existing system VPN.
- Verify the player looks as on the site (TV layout), with a visible focus ring.
- With the remote only: show the controls, play/pause, next episode, open and pick from the season/episode/voice lists, volume, seek on the progress bar.
- Settings: change the quality during playback (the SD/HD/FHD badge follows), speed, subtitles and scale.
- Back steps out of a settings submenu, then closes the open menu or list, then returns to the episode screen.
- Verify no website page, browser chrome or mouse cursor is shown.
- Verify Player 2 and Player 3 are not exposed in the app.

## Resume
- Start an episode, watch for at least 30 seconds, leave playback and reopen it.
- Verify playback resumes near the saved position.
- Verify progress is independent for different episodes.
- Verify progress survives app force-stop and TV restart.

## Regression
- Audio stops when leaving playback.
- No audio continues in background.
- Network/VPN configuration is not modified.
- Test cold launch, app restart, and temporary network failure.
