package app.lordserial.tv

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
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

private const val SERIES_URL = "https://ochen-strannye-dela-lordserial.club/"
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
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullscreen = false
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
            val episodeUrl = if (selectedEpisode == 0) null else
                "${SERIES_URL}season-$selectedSeason/episode-$selectedEpisode"

            fun openEpisode(season: Int, episode: Int) {
                selectedSeason = season
                selectedEpisode = episode
                lastWatched = season to episode
                watched = watched + "$season:$episode"
                progress.edit()
                    .putInt(KEY_SEASON, season)
                    .putInt(KEY_EPISODE, episode)
                    .putStringSet(KEY_WATCHED, watched)
                    .apply()
            }

            fun closeEpisode() {
                closePlayer()
                selectedEpisode = 0
            }

            BackHandler(enabled = selectedEpisode != 0) {
                val view = webView
                when {
                    isFullscreen -> exitFullscreen()
                    pageState == PageState.READY && view?.canGoBack() == true -> view.goBack()
                    else -> closeEpisode()
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
                                createWebView().also {
                                    webView = it
                                    it.loadUrl(episodeUrl)
                                }
                            },
                            // A WebView per episode: leaving the episode releases it, so its audio
                            // stops and Back never walks into the previous episode's history.
                            onRelease = { it.destroy() }
                        )
                        when (pageState) {
                            PageState.LOADING -> LoadingOverlay(selectedSeason, selectedEpisode)
                            PageState.ERROR -> ErrorOverlay(
                                detail = pageError,
                                onRetry = { webView?.loadUrl(episodeUrl) },
                                onClose = { closeEpisode() }
                            )
                            PageState.READY -> Unit
                        }
                    } else {
                        SeriesHome(
                            selectedSeason = selectedSeason,
                            lastWatched = lastWatched,
                            watched = watched,
                            onSeasonSelected = { selectedSeason = it },
                            onEpisodeSelected = { season, episode -> openEpisode(season, episode) }
                        )
                    }
                }
            }
        }
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
                Text(
                    text = "Выбери сезон и серию",
                    fontSize = 18.sp,
                    color = MUTED
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "СЕЗОНЫ",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ACCENT
            )
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
                            text = "Сезон $season",
                            fontSize = 15.sp,
                            fontWeight = if (season == selectedSeason) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "СЕРИИ · СЕЗОН $selectedSeason",
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
                            text = if ("$selectedSeason:$episode" in watched) "Серия $episode  ✓" else "Серия $episode",
                            fontSize = 17.sp
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun LoadingOverlay(season: Int, episode: Int) {
        Box(
            modifier = Modifier.fillMaxSize().background(BACKGROUND),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Загрузка…", fontSize = 28.sp, color = ComposeColor.White)
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Сезон $season, серия $episode", fontSize = 18.sp, color = MUTED)
            }
        }
    }

    @Composable
    private fun ErrorOverlay(detail: String, onRetry: () -> Unit, onClose: () -> Unit) {
        val retryFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
        Box(
            modifier = Modifier.fillMaxSize().background(BACKGROUND),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Не удалось открыть серию",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = ComposeColor.White
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "Проверь интернет и VPN", fontSize = 18.sp, color = MUTED)
                if (detail.isNotBlank()) {
                    Text(text = detail, fontSize = 14.sp, color = MUTED)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.focusRequester(retryFocus),
                        colors = chipColors()
                    ) { Text(text = "Повторить", fontSize = 17.sp) }
                    Button(onClick = onClose, colors = chipColors()) {
                        Text(text = "К списку серий", fontSize = 17.sp)
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
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
                if (pageState == PageState.LOADING) {
                    pageState = PageState.READY
                    view.requestFocus()
                }
                // Player 1 is the only option verified to work on the target TV.
                // Select it when an episode page finishes loading, unless the site already did:
                // clicking the active player reloads its iframe for nothing.
                if (url.contains("/season-") && url.contains("/episode-")) {
                    view.evaluateJavascript(
                        """
                        (function() {
                          if (window.__lordSerialPlayer1Selected) return;
                          var controls = Array.from(document.querySelectorAll(
                            'button, a, [role="button"], [onclick]'
                          ));
                          var player1 = controls.find(function(el) {
                            return (el.innerText || el.textContent || '')
                              .replace(/\\s+/g, ' ').trim().toLowerCase() === 'плеер 1';
                          });
                          if (player1) {
                            window.__lordSerialPlayer1Selected = true;
                            if (!player1.classList.contains('active')) player1.click();
                          }
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                isFullscreen = true
                (window.decorView as ViewGroup).addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                applyImmersiveMode()
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    /** Stops the episode's WebView at once; the AndroidView destroys it when it leaves the screen. */
    private fun closePlayer() {
        exitFullscreen()
        webView?.apply {
            stopLoading()
            onPause()
            loadUrl("about:blank")
            webChromeClient = null
        }
        webView = null
        pageState = PageState.LOADING
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        (window.decorView as ViewGroup).removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        isFullscreen = false
        applyImmersiveMode()
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
        // The WebView itself is destroyed by the AndroidView's onRelease.
        exitFullscreen()
        webView = null
        super.onDestroy()
    }
}
