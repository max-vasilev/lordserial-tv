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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val SERIES_URL = "https://ochen-strannye-dela-lordserial.club/"

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
            var showWeb by remember { mutableStateOf(false) }

            BackHandler(enabled = showWeb) {
                val view = webView
                if (isFullscreen) {
                    exitFullscreen()
                } else if (view?.canGoBack() == true) {
                    view.goBack()
                } else {
                    showWeb = false
                }
            }

            MaterialTheme {
                Box(
                    modifier = Modifier.fillMaxSize().background(ComposeColor(0xFF090B12)),
                    contentAlignment = Alignment.Center
                ) {
                    if (showWeb) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                (webView ?: createWebView().also { webView = it }).apply {
                                    if (url.isNullOrBlank()) loadUrl(SERIES_URL)
                                }
                            },
                            update = { }
                        )
                    } else {
                        Column(
                            modifier = Modifier.padding(48.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            Text(
                                text = "ОЧЕНЬ СТРАННЫЕ ДЕЛА",
                                fontSize = 34.sp,
                                color = ComposeColor.White
                            )
                            Text(
                                text = "Личная версия LordSerial TV",
                                fontSize = 18.sp,
                                color = ComposeColor(0xFFB8BBC7)
                            )
                            Button(
                                onClick = { showWeb = true },
                                modifier = Modifier.width(260.dp)
                            ) {
                                Text("Открыть сериал")
                            }
                        }
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
            webViewClient = null
            destroy()
        }
        webView = null
        super.onDestroy()
    }
}
