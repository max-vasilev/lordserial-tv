package app.lordserial.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
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
private const val KEY_WATCHED = "watched"

private enum class PageState { LOADING, READY, ERROR }

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var pageState by mutableStateOf(PageState.LOADING)
    private var pageError by mutableStateOf("")
    private val progress by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContent {
            var selectedSeason by remember { mutableIntStateOf(progress.getInt(KEY_SEASON, 1)) }
            var selectedEpisode by remember { mutableIntStateOf(0) }
            var lastWatched by remember { mutableStateOf(readLastWatched()) }
            var watched by remember {
                mutableStateOf(progress.getStringSet(KEY_WATCHED, null)?.toSet() ?: emptySet())
            }
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
                saveCurrentPosition(selectedSeason, selectedEpisode)
                closePlayer()
                selectedEpisode = 0
            }

            BackHandler(enabled = selectedEpisode != 0) {
                if (controlsVisible) controlsVisible = false else closeEpisode()
            }

            // Poll only the top-level HTML5 video. If the provider nests its player in a
            // cross-origin iframe, the browser security model intentionally prevents access.
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
                            factory = {
                                pageState = PageState.LOADING
                                createPlayerWebView().also {
                                    webView = it
                                    it.loadUrl(episodeUrl)
                                }
                            },
                            onRelease = {
                                saveCurrentPosition(selectedSeason, selectedEpisode)
                                it.stopLoading()
                                it.onPause()
                                it.destroy()
                                if (webView === it) webView = null
                            }
                        )

                        if (controlsVisible) {
                            PlayerOverlay(
                                season = selectedSeason,
                                episode = selectedEpisode,
                                state = pageState,
                                error = pageError,
                                onPlayPause = { runPlayerCommand("toggle") },
                                onBack = { closeEpisode() },
                                onRetry = { webView?.reload() },
                                onHide = { controlsVisible = false }
                            )
                        } else {
                            // The player remains the only visible content. OK brings back
                            // native controls; Back returns to the native episode selector.
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.BottomEnd
                            ) {
                                Button(
                                    onClick = { controlsVisible = true },
                                    modifier = Modifier.padding(28.dp),
                                    colors = chipColors()
                                ) { Text("Управление", fontSize = 16.sp) }
                            }
                        }
                    } else {
                        SeriesHome(
                            selectedSeason = selectedSeason,
                            lastWatched = lastWatched,
                            watched = watched,
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

    private fun saveCurrentPosition(season: Int, episode: Int) {
        if (episode == 0) return
        val key = progressKey(season, episode)
        webView?.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v?Math.floor(v.currentTime||0):0})()"
        ) { raw ->
            val seconds = raw?.trim()?.trim('"')?.toLongOrNull() ?: 0L
            if (seconds > 0L) progress.edit().putLong(key, seconds).apply()
        }
    }

    private fun runPlayerCommand(command: String) {
        val saved = progress.getLong(progressKey(
            progress.getInt(KEY_SEASON, 1),
            progress.getInt(KEY_EPISODE, 1)
        ), 0L)
        val js = when (command) {
            "toggle" -> """
                (function(){
                  var v=document.querySelector('video');
                  if(v){ if(v.paused) v.play(); else v.pause(); return 'video'; }
                  document.dispatchEvent(new KeyboardEvent('keydown',{key:' ',code:'Space',bubbles:true}));
                  return 'keyboard';
                })()
            """.trimIndent()
            "resume" -> "var v=document.querySelector('video'); if(v){v.currentTime=$saved;v.play()}"
            else -> ""
        }
        webView?.evaluateJavascript(js, null)
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
        watched: Set<String>,
        onSeasonSelected: (Int) -> Unit,
        onEpisodeSelected: (Int, Int) -> Unit
    ) {
        val continueFocus = remember { FocusRequester() }
        if (lastWatched != null) {
            LaunchedEffect(Unit) { runCatching { continueFocus.requestFocus() } }
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
                        modifier = Modifier.width(112.dp),
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
                            if ("$selectedSeason:$episode" in watched) "Серия $episode  ✓" else "Серия $episode",
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
        onBack: () -> Unit,
        onRetry: () -> Unit,
        onHide: () -> Unit
    ) {
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
                        Button(onClick = onRetry, colors = chipColors()) { Text("Повторить") }
                    } else {
                        Button(onClick = onPlayPause, colors = chipColors()) { Text("Пауза / Play") }
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
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@apply, true)
        }
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
                pageState = PageState.READY
                // Keep the embed viewport focused on its playback surface where possible.
                view.evaluateJavascript(
                    """
                    (function(){
                      var css = 'html,body{margin:0!important;padding:0!important;background:#000!important;overflow:hidden!important}' +
                        'video,iframe{position:absolute!important;inset:0!important;width:100%!important;height:100%!important;border:0!important;object-fit:contain!important}';
                      var style=document.createElement('style');style.textContent=css;
                      (document.head||document.documentElement).appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )
                view.requestFocus()
                runPlayerCommand("resume")
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // The embed may request fullscreen; retain the native activity's immersive mode.
                applyImmersiveMode()
            }
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            ) {
                runPlayerCommand("toggle")
                true
            } else false
        }
    }

    private fun closePlayer() {
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

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
