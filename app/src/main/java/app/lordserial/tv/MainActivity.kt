package app.lordserial.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay

private const val PLAYER_BASE_URL = "https://api.namy.ws/embed/movie/354"
private val EPISODE_COUNTS = listOf(8, 9, 8, 9, 8)
private val BACKGROUND = ComposeColor(0xFF090B12)
private val SURFACE = ComposeColor(0xFF1C1F2B)
private val MUTED = ComposeColor(0xFFB8BBC7)
private val ACCENT = ComposeColor(0xFFB52A36)

private const val PREFS = "progress"
private const val KEY_SEASON = "last_season"
private const val KEY_EPISODE = "last_episode"

private enum class PageState { LOADING, READY, ERROR }

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pageState by mutableStateOf(PageState.LOADING)
    private var pageError by mutableStateOf("")
    private var isFullscreen by mutableStateOf(false)
    private var playerContainer: FrameLayout? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val progress by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContent {
            var selectedSeason by remember { mutableIntStateOf(progress.getInt(KEY_SEASON, 1)) }
            var selectedEpisode by remember { mutableIntStateOf(0) }
            var lastWatched by remember { mutableStateOf(readLastWatched()) }
            var controlsVisible by remember { mutableStateOf(true) }
            val episodeUrl = if (selectedEpisode == 0) null else
                "$PLAYER_BASE_URL?episode=$selectedEpisode&season=$selectedSeason"

            fun openEpisode(season: Int, episode: Int) {
                selectedSeason = season
                selectedEpisode = episode
                lastWatched = season to episode
                progress.edit()
                    .putInt(KEY_SEASON, season)
                    .putInt(KEY_EPISODE, episode)
                    .apply()
                controlsVisible = true
            }

            fun closeEpisode() {
                val season = selectedSeason
                val episode = selectedEpisode
                readCurrentPosition { seconds ->
                    if (seconds != null && episode != 0) {
                        progress.edit().putLong(progressKey(season, episode), seconds).apply()
                    }
                    closePlayer()
                    selectedEpisode = 0
                }
            }

            BackHandler(enabled = selectedEpisode != 0) {
                if (fullscreenView != null) hideCustomView() else closeEpisode()
            }

            // Poll only the top-level HTML5 video. If the provider nests its player in a
            // cross-origin iframe, the browser security model intentionally prevents access.
            LaunchedEffect(selectedSeason, selectedEpisode) {
                if (selectedEpisode != 0) {
                    delay(30_000)
                    if (pageState == PageState.LOADING) {
                        pageError = "Плеер не ответил за 30 секунд. Проверь сеть и VPN."
                        pageState = PageState.ERROR
                    }
                }
            }

            LaunchedEffect(selectedSeason, selectedEpisode) {
                if (selectedEpisode != 0) {
                    while (true) {
                        saveCurrentPosition(selectedSeason, selectedEpisode)
                        delay(5_000)
                    }
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = ACCENT,
                    background = BACKGROUND,
                    surface = BACKGROUND,
                    onSurface = ComposeColor.White
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().background(BACKGROUND),
                    contentAlignment = Alignment.Center
                ) {
                    if (episodeUrl != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                pageState = PageState.LOADING
                                FrameLayout(context).apply {
                                    setBackgroundColor(Color.BLACK)
                                    playerContainer = this
                                    val player = createPlayerWebView()
                                    webView = player
                                    addView(
                                        player,
                                        FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                    )
                                    player.loadDataWithBaseURL(
                                        "https://api.namy.ws/",
                                        playerShellHtml(episodeUrl),
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            },
                            onRelease = {
                                saveCurrentPosition(selectedSeason, selectedEpisode)
                                closePlayer()
                                playerContainer = null
                            }
                        )

                        if (controlsVisible && !isFullscreen) {
                            PlayerOverlay(
                                season = selectedSeason,
                                episode = selectedEpisode,
                                state = pageState,
                                error = pageError,
                                onPlayPause = { runPlayerCommand("toggle", selectedSeason, selectedEpisode) },
                                onSeekBack = { runPlayerCommand("seek-back", selectedSeason, selectedEpisode) },
                                onSeekForward = { runPlayerCommand("seek-forward", selectedSeason, selectedEpisode) },
                                onBack = { closeEpisode() },
                                onRetry = { webView?.reload() },
                                onHide = { controlsVisible = false }
                            )
                        } else {
                            // The player remains the only visible content. Back always returns
                            // to the native episode selector.
                            val controlsFocus = remember { FocusRequester() }
                            LaunchedEffect(selectedEpisode, controlsVisible) {
                                runCatching { controlsFocus.requestFocus() }
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Button(
                                    onClick = { controlsVisible = true },
                                    modifier = Modifier.padding(28.dp).focusRequester(controlsFocus),
                                    colors = chipColors()
                                ) { Text("Управление", fontSize = 16.sp) }
                            }
                        }
                    } else {
                        SeriesHome(
                            selectedSeason = selectedSeason,
                            lastWatched = lastWatched,
                            onSeasonSelected = { selectedSeason = it },
                            onEpisodeSelected = { season, episode ->
                                openEpisode(season, episode)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun progressKey(season: Int, episode: Int) = "position_${season}_${episode}"

    private fun readCurrentPosition(onRead: (Long?) -> Unit) {
        val currentWebView = webView
        if (currentWebView == null) {
            onRead(null)
            return
        }
        currentWebView.evaluateJavascript(
            """
            (function(){
              function findVideo(w, depth) {
                if (depth > 4) return null;
                try {
                  var v = w.document.querySelector('video');
                  if (v) return v;
                  var frames = w.document.querySelectorAll('iframe');
                  for (var i = 0; i < frames.length; i++) {
                    try {
                      var found = findVideo(frames[i].contentWindow, depth + 1);
                      if (found) return found;
                    } catch (e) {}
                  }
                } catch (e) {}
                return null;
              }
              var v = findVideo(window, 0);
              return v ? Math.floor(v.currentTime || 0) : null;
            })()
            """.trimIndent()
        ) { raw ->
            onRead(raw?.trim()?.trim('"')?.toLongOrNull())
        }
    }

    private fun saveCurrentPosition(season: Int, episode: Int) {
        if (episode == 0) return
        readCurrentPosition { seconds ->
            if (seconds != null) {
                progress.edit().putLong(progressKey(season, episode), seconds).apply()
            }
        }
    }

    private fun findVideoJs(): String = """
        function findVideo(w, depth) {
          if (depth > 4) return null;
          try {
            var v = w.document.querySelector('video');
            if (v) {
              if (!w.document.getElementById('lordserial-tv-player-style')) {
                var style = w.document.createElement('style');
                style.id = 'lordserial-tv-player-style';
                style.textContent =
                  'html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#000!important}' +
                  'body *{visibility:hidden!important}' +
                  'video{visibility:visible!important;position:fixed!important;inset:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;object-fit:contain!important;background:#000!important}' +
                  'video::-webkit-media-controls{display:none!important}';
                (w.document.head || w.document.documentElement).appendChild(style);
              }
              return v;
            }
            var frames = w.document.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try {
                var found = findVideo(frames[i].contentWindow, depth + 1);
                if (found) return found;
              } catch (e) {}
            }
          } catch (e) {}
          return null;
        }
    """.trimIndent()

    private fun runPlayerCommand(command: String, season: Int, episode: Int) {
        val saved = progress.getLong(progressKey(season, episode), 0L)
        val js = when (command) {
            "toggle" -> """
                (function(){
                  ${findVideoJs()}
                  var v = findVideo(window, 0);
                  if (v) { if (v.paused) v.play(); else v.pause(); return 'video'; }
                  var f = document.querySelector('iframe');
                  if (f) { try { f.contentWindow.focus(); } catch(e) {} }
                  return 'player-frame';
                })()
            """.trimIndent()
            "play" -> """
                (function(){ ${findVideoJs()} var v=findVideo(window,0); if(v) v.play().catch(function(){}); })()
            """.trimIndent()
            "pause" -> """
                (function(){ ${findVideoJs()} var v=findVideo(window,0); if(v) v.pause(); })()
            """.trimIndent()
            "seek-back" -> """
                (function(){ ${findVideoJs()} var v=findVideo(window,0); if(v) v.currentTime=Math.max(0,v.currentTime-10); })()
            """.trimIndent()
            "seek-forward" -> """
                (function(){ ${findVideoJs()} var v=findVideo(window,0); if(v) v.currentTime=Math.min(v.duration||1e9,v.currentTime+10); })()
            """.trimIndent()
            "resume" -> """
                (function(){
                  ${findVideoJs()}
                  var saved = $saved;
                  var attempts = 0;
                  function restore() {
                    var v = findVideo(window, 0);
                    if (v && v.readyState >= 1) {
                      if (saved > 0 && Math.abs(v.currentTime - saved) > 3) v.currentTime = saved;
                      v.play().catch(function(){});
                      return;
                    }
                    if (attempts++ < 40) setTimeout(restore, 500);
                  }
                  restore();
                })()
            """.trimIndent()
            else -> ""
        }
        webView?.evaluateJavascript(js) { raw ->
            val fallbackKey = when {
                command == "toggle" && raw?.contains("player-frame") == true -> KeyEvent.KEYCODE_SPACE
                command == "seek-back" && (raw == null || raw == "null") -> KeyEvent.KEYCODE_DPAD_LEFT
                command == "seek-forward" && (raw == null || raw == "null") -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> null
            }
            if (fallbackKey != null) dispatchPlayerKey(fallbackKey)
        }
    }

    private fun dispatchPlayerKey(keyCode: Int) {
        val target = webView ?: return
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        target.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun playerShellHtml(embedUrl: String): String {
        val safeUrl = embedUrl.replace("&", "&amp;").replace("\"", "&quot;")
        return """
            <!doctype html>
            <html>
              <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
                <meta charset="utf-8">
                <style>
                  * { box-sizing: border-box; }
                  html, body { margin:0; padding:0; width:100%; height:100%; overflow:hidden; background:#000; }
                  iframe { position:fixed; inset:0; width:100%; height:100%; border:0; background:#000; }
                </style>
              </head>
              <body>
                <iframe id="player" src="$safeUrl"
                  onload="console.log('LORDSERIAL_PLAYER_FRAME_READY')"
                  onerror="console.log('LORDSERIAL_PLAYER_FRAME_ERROR')"
                  allow="autoplay; fullscreen; picture-in-picture; encrypted-media"
                  allowfullscreen></iframe>
              </body>
            </html>
        """.trimIndent()
    }

    private fun readLastWatched(): Pair<Int, Int>? {
        val season = progress.getInt(KEY_SEASON, 0)
        val episode = progress.getInt(KEY_EPISODE, 0)
        return if (season in 1..EPISODE_COUNTS.size && episode in 1..EPISODE_COUNTS[season - 1])
            season to episode else null
    }

    @Composable
    private fun chipColors(selected: Boolean = false): ButtonColors = ButtonDefaults.colors(
        containerColor = if (selected) ACCENT else SURFACE,
        contentColor = ComposeColor.White,
        focusedContainerColor = ComposeColor.White,
        focusedContentColor = BACKGROUND,
        pressedContainerColor = MUTED,
        pressedContentColor = BACKGROUND
    )

    @Composable
    private fun SeriesHome(
        selectedSeason: Int,
        lastWatched: Pair<Int, Int>?,
        onSeasonSelected: (Int) -> Unit,
        onEpisodeSelected: (Int, Int) -> Unit
    ) {
        val continueFocus = remember { FocusRequester() }
        val firstSeasonFocus = remember { FocusRequester() }
        LaunchedEffect(lastWatched) {
            if (lastWatched != null) {
                runCatching { continueFocus.requestFocus() }
            } else {
                runCatching { firstSeasonFocus.requestFocus() }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 38.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "ОЧЕНЬ СТРАННЫЕ ДЕЛА",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = ComposeColor.White
            )
            if (lastWatched != null) {
                val (season, episode) = lastWatched
                Button(
                    onClick = { onEpisodeSelected(season, episode) },
                    modifier = Modifier.focusRequester(continueFocus),
                    colors = chipColors()
                ) {
                    Text(
                        text = "▶  Продолжить · Сезон $season, серия $episode",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Text("Выбери сезон и серию", fontSize = 18.sp, color = MUTED)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text("СЕЗОНЫ", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = ACCENT)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (season in 1..EPISODE_COUNTS.size) {
                    Button(
                        onClick = { onSeasonSelected(season) },
                        modifier = Modifier.width(112.dp).then(
                            if (season == 1) Modifier.focusRequester(firstSeasonFocus) else Modifier
                        ),
                        colors = chipColors(selected = season == selectedSeason)
                    ) {
                        Text(
                            "Сезон $season",
                            fontSize = 15.sp,
                            fontWeight = if (season == selectedSeason) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "СЕРИИ · СЕЗОН $selectedSeason",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ACCENT
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items((1..EPISODE_COUNTS[selectedSeason - 1]).toList()) { episode ->
                    Button(
                        onClick = { onEpisodeSelected(selectedSeason, episode) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = chipColors()
                    ) {
                        Text(
                            "Серия $episode",
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PlayerOverlay(
        season: Int,
        episode: Int,
        state: PageState,
        error: String,
        onPlayPause: () -> Unit,
        onSeekBack: () -> Unit,
        onSeekForward: () -> Unit,
        onBack: () -> Unit,
        onRetry: () -> Unit,
        onHide: () -> Unit
    ) {
        val firstControlFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { firstControlFocus.requestFocus() } }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().background(ComposeColor(0xDD090B12)).padding(
                    horizontal = 42.dp, vertical = 22.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "ОЧЕНЬ СТРАННЫЕ ДЕЛА",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.White
                        )
                        Text("Сезон $season · Серия $episode", fontSize = 15.sp, color = MUTED)
                    }
                    when (state) {
                        PageState.LOADING -> Text("Подключаем плеер…", color = MUTED)
                        PageState.READY -> Text("Player 1", color = ComposeColor(0xFFB8E6C2))
                        PageState.ERROR -> Text("Ошибка загрузки", color = ComposeColor(0xFFFF7777))
                    }
                }
                if (state == PageState.ERROR) {
                    Text(error.ifBlank { "Проверь подключение и VPN" }, color = MUTED, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state == PageState.ERROR) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.focusRequester(firstControlFocus),
                            colors = chipColors()
                        ) { Text("Повторить") }
                    } else {
                        Button(onClick = onSeekBack, colors = chipColors()) { Text("−10 сек") }
                        Button(
                            onClick = onPlayPause,
                            modifier = Modifier.focusRequester(firstControlFocus),
                            colors = chipColors()
                        ) { Text("Пауза / Play") }
                        Button(onClick = onSeekForward, colors = chipColors()) { Text("+10 сек") }
                    }
                    Button(onClick = onHide, colors = chipColors()) { Text("Скрыть панель") }
                    Button(onClick = onBack, colors = chipColors()) { Text("К сериям") }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayerWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageError = ""
                pageState = PageState.LOADING
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    pageError = error.description?.toString().orEmpty()
                    pageState = PageState.ERROR
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    pageError = "HTTP ${errorResponse.statusCode}"
                    pageState = PageState.ERROR
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // The shell finishes after its Player 1 iframe, so READY may already be set
                // here; onPageStarted has put the state back to LOADING for this load.
                view.requestFocus()
                runPlayerCommand("resume", this@MainActivity.progress.getInt(KEY_SEASON, 1), this@MainActivity.progress.getInt(KEY_EPISODE, 1))
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                when (message.message()) {
                    "LORDSERIAL_PLAYER_FRAME_READY" -> pageState = PageState.READY
                    "LORDSERIAL_PLAYER_FRAME_ERROR" -> {
                        pageError = "Не удалось загрузить Player 1."
                        pageState = PageState.ERROR
                    }
                }
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                view.setBackgroundColor(Color.BLACK)
                playerContainer?.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                view.bringToFront()
                isFullscreen = true
                applyImmersiveMode()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_SPACE)
            ) {
                runPlayerCommand(
                    "toggle",
                    this@MainActivity.progress.getInt(KEY_SEASON, 1),
                    this@MainActivity.progress.getInt(KEY_EPISODE, 1)
                )
                true
            } else false
        }
    }

    private fun hideCustomView() {
        fullscreenView?.let { view ->
            playerContainer?.removeView(view)
        }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        isFullscreen = false
        applyImmersiveMode()
    }

    private fun closePlayer() {
        hideCustomView()
        webView?.apply {
            stopLoading()
            onPause()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        pageState = PageState.LOADING
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            webView != null && this@MainActivity.progress.getInt(KEY_EPISODE, 0) != 0
        ) {
            val season = this@MainActivity.progress.getInt(KEY_SEASON, 1)
            val episode = this@MainActivity.progress.getInt(KEY_EPISODE, 1)
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    runPlayerCommand("toggle", season, episode)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    runPlayerCommand("play", season, episode)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    runPlayerCommand("pause", season, episode)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    runPlayerCommand("seek-back", season, episode)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    runPlayerCommand("seek-forward", season, episode)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        hideCustomView()
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
