package app.lordserial.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val SERIES_URL = "https://ochen-strannye-dela-lordserial.club/"
private val EPISODE_COUNTS = listOf(8, 9, 8, 9, 8)
private val BACKGROUND = ComposeColor(0xFF090B12)
private val MUTED = ComposeColor(0xFFB8BBC7)
private val ACCENT = ComposeColor(0xFFB52A36)

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isFullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContent {
            var selectedSeason by remember { mutableIntStateOf(1) }
            var selectedEpisode by remember { mutableIntStateOf(0) }
            val episodeUrl = if (selectedEpisode == 0) null else
                "${SERIES_URL}season-$selectedSeason/episode-$selectedEpisode"

            BackHandler(enabled = selectedEpisode != 0) {
                val view = webView
                if (isFullscreen) {
                    exitFullscreen()
                } else if (view?.canGoBack() == true) {
                    view.goBack()
                } else {
                    selectedEpisode = 0
                }
            }

            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(BACKGROUND),
                    contentAlignment = Alignment.Center
                ) {
                    if (episodeUrl != null) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                (webView ?: createWebView().also { webView = it }).apply {
                                    if (url != episodeUrl) loadUrl(episodeUrl)
                                }
                            },
                            update = { view ->
                                if (view.url != episodeUrl) view.loadUrl(episodeUrl)
                            }
                        )
                    } else {
                        SeriesHome(
                            selectedSeason = selectedSeason,
                            onSeasonSelected = { selectedSeason = it },
                            onEpisodeSelected = { selectedEpisode = it }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SeriesHome(
        selectedSeason: Int,
        onSeasonSelected: (Int) -> Unit,
        onEpisodeSelected: (Int) -> Unit
    ) {
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
            Text(
                text = "Выбери сезон и серию",
                fontSize = 18.sp,
                color = MUTED
            )

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
                (1..5).forEach { season ->
                    Button(
                        onClick = { onSeasonSelected(season) },
                        modifier = Modifier.width(112.dp)
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
                        onClick = { onEpisodeSelected(episode) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Серия $episode",
                            fontSize = 17.sp
                        )
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

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Player 1 is the only option verified to work on the target TV.
                // Select it automatically when an episode page finishes loading.
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
                            player1.click();
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
                (window.decorView as android.view.ViewGroup).addView(
                    view,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                applyImmersiveMode()
            }

            override fun onHideCustomView() = exitFullscreen()
        }
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        (window.decorView as android.view.ViewGroup).removeView(view)
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
        exitFullscreen()
        webView?.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
