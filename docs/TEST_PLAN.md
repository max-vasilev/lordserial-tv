# TV test plan

On the MiTV-MOOQ1 (Android TV 11):

1. Install the debug APK using the existing Wi-Fi ADB workflow.
2. Confirm LordSerial TV appears in the launcher with its icon.
3. Open the app and activate «Открыть сериал» with the remote.
4. Confirm the site loads and the page can be navigated with the remote.
5. Open an episode and test each of the three player options.
6. Check playback, pause/resume, seeking, fullscreen and audio.
7. Press Back once from fullscreen, then from the player/page, then return to the native start screen.
8. Report player name and exact failure (if any), plus whether VPN was enabled.

Do not change the TV's VPN settings for this test.
