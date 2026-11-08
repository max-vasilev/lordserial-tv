package app.lordserial.tv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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

private const val PLAYER_HOST = "api.namy.ws"
private const val MOVIE_ID = 354
private const val PLAYER_BASE_URL = "https://$PLAYER_HOST/embed/movie/$MOVIE_ID"
private const val LAST_EPISODE_KEY = "vp$MOVIE_ID"
private val EPISODE_COUNTS = listOf(8, 9, 8, 9, 8)
private val BACKGROUND = ComposeColor(0xFF090B12)
private val SURFACE = ComposeColor(0xFF1C1F2B)
private val MUTED = ComposeColor(0xFFB8BBC7)
private val ACCENT = ComposeColor(0xFFB52A36)

private const val PREFS = "progress"
private const val KEY_SEASON = "last_season"
private const val KEY_EPISODE = "last_episode"

// The site's player has a TV layout but expects the browser to move focus between its buttons,
// which Android WebView does not do. This script adds only that: the arrows move a focus ring to
// the nearest visible control, OK clicks it, and the first press while the controls are hidden
// just shows them. On the progress bar Left/Right seek and OK plays or pauses. Back steps out of
// the player's menus. Nothing else on the page is changed.
private val REMOTE_NAVIGATION_JS = """
    (function () {
      if (window.__lordSerialRemote) return;
      window.__lordSerialRemote = true;
      var style = document.createElement('style');
      style.textContent = '.lordserial-focus{outline:3px solid #fff!important;outline-offset:3px!important;' +
        'box-shadow:0 0 0 7px rgba(0,0,0,.45)!important}';
      (document.head || document.documentElement).appendChild(style);

      var current = null, lastRect = null, beforeSlider = null, openers = [];
      var SEMANTIC = 'button,a[href],[role="button"],[role="menuitem"],[role="option"],input:not([type="hidden"]),select';
      // The progress bar itself ignores pointer events and its parts inherit the pointer cursor,
      // so it is matched by its class name instead.
      var TIMELINE = /(^|\s)timeline_/;

      function shown(el, anyPointer) {
        var r = el.getBoundingClientRect();
        if (r.width < 4 || r.height < 4) return false;
        if (r.right <= 0 || r.bottom <= 0 || r.left >= innerWidth || r.top >= innerHeight) return false;
        for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
          var cs = getComputedStyle(n);
          if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1) return false;
        }
        if (!anyPointer && getComputedStyle(el).pointerEvents === 'none') return false;
        var x = Math.min(innerWidth - 1, Math.max(0, r.left + r.width / 2));
        var y = Math.min(innerHeight - 1, Math.max(0, r.top + r.height / 2));
        var top = document.elementFromPoint(x, y);
        return !!top && (top === el || el.contains(top));
      }

      // A pointer cursor is inherited, so only the element where it starts counts, plus list
      // items, which are the options inside a clickable menu row (the settings submenus).
      function clickable(el) {
        if (el.matches(SEMANTIC)) return true;
        if (getComputedStyle(el).cursor !== 'pointer') return false;
        if (el.tagName === 'LI') return true;
        var p = el.parentElement;
        return !p || getComputedStyle(p).cursor !== 'pointer';
      }

      function isSlider(el) { return TIMELINE.test(String(el.className)); }

      function candidates() {
        var area = innerWidth * innerHeight, found = [];
        var all = document.body ? document.body.getElementsByTagName('*') : [];
        for (var i = 0; i < all.length; i++) {
          var el = all[i];
          if (el.tagName === 'VIDEO' || el.tagName === 'IFRAME') continue;
          var slider = isSlider(el);
          if (!slider && !clickable(el)) continue;
          var r = el.getBoundingClientRect();
          if (r.width * r.height > area * 0.4) continue;
          if (shown(el, slider)) found.push(el);
        }
        // A row that holds other controls (an open submenu) is not a target itself; its options are.
        return found.filter(function (el) {
          return !found.some(function (other) { return other !== el && el.contains(other); });
        });
      }

      function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

      function nearest(list, rect) {
        var c = center(rect), best = null, bestD = Infinity;
        list.forEach(function (el) {
          var p = center(el.getBoundingClientRect()), d = Math.hypot(p.x - c.x, p.y - c.y);
          if (d < bestD) { bestD = d; best = el; }
        });
        return best;
      }

      // With no earlier focus, start on Play/Pause: the leftmost button of the bottom bar that is
      // not a previous/next episode button.
      function playButton(list) {
        var buttons = list.filter(function (el) {
          return el.tagName === 'BUTTON' && el.getBoundingClientRect().top > innerHeight / 2 &&
            !/(^|\s)episode-/.test(String(el.className));
        });
        buttons.sort(function (a, b) { return a.getBoundingClientRect().left - b.getBoundingClientRect().left; });
        return buttons[0] || list[0] || null;
      }

      function pick(from, dir, list) {
        var a = from.getBoundingClientRect(), ca = center(a), best = null, bestScore = Infinity;
        list.forEach(function (el) {
          if (el === from) return;
          var b = el.getBoundingClientRect(), cb = center(b), gap, ortho, overlap;
          if (dir === 'left' || dir === 'right') {
            // The progress bar spans the whole width; it is reached with Up/Down only.
            if (isSlider(el)) return;
            if (dir === 'right' ? cb.x <= ca.x + 2 : cb.x >= ca.x - 2) return;
            gap = dir === 'right' ? b.left - a.right : a.left - b.right;
            overlap = b.top < a.bottom && b.bottom > a.top;
            ortho = overlap ? 0 : Math.abs(cb.y - ca.y);
          } else {
            if (dir === 'down' ? cb.y <= ca.y + 2 : cb.y >= ca.y - 2) return;
            gap = dir === 'down' ? b.top - a.bottom : a.top - b.bottom;
            overlap = b.left < a.right && b.right > a.left;
            ortho = overlap ? 0 : Math.abs(cb.x - ca.x);
          }
          var score = Math.max(0, gap) + ortho * 3 + Math.hypot(cb.x - ca.x, cb.y - ca.y) * 0.1;
          if (score < bestScore) { bestScore = score; best = el; }
        });
        return best;
      }

      function setCurrent(el) {
        if (current) current.classList.remove('lordserial-focus');
        current = el;
        if (!el) return;
        el.classList.add('lordserial-focus');
        lastRect = el.getBoundingClientRect();
        try { el.focus({ preventScroll: true }); } catch (e) {}
      }

      function wake() {
        var x = innerWidth / 2 + (Math.random() * 6 - 3), y = innerHeight / 2;
        var target = document.elementFromPoint(x, y) || document.body;
        target.dispatchEvent(new MouseEvent('mousemove', { bubbles: true, clientX: x, clientY: y }));
      }

      function layout(list) {
        return list.map(function (el) {
          var r = el.getBoundingClientRect();
          return Math.round(r.left) + ',' + Math.round(r.top);
        }).join(';');
      }

      // Controls slide and fade in, so wait until they are visible and have stopped moving.
      function whenSettled(done) {
        var tries = 0, previous = null;
        (function check() {
          var list = candidates(), now = layout(list);
          if ((!list.length || now !== previous) && tries++ < 20) {
            previous = now;
            setTimeout(check, 100);
            return;
          }
          done(list);
        })();
      }

      // Click the way a mouse would: on the element under the centre of the focused control. The
      // focused element can be a wrapper whose handler sits on a child (the settings menu rows).
      function press(el) {
        var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
        var target = document.elementFromPoint(x, y);
        if (!target || !el.contains(target)) target = el;
        ['mousedown', 'mouseup', 'click'].forEach(function (type) {
          target.dispatchEvent(new MouseEvent(type, {
            bubbles: true, cancelable: true, view: window, clientX: x, clientY: y, button: 0
          }));
        });
      }

      // Open, even while the player has faded its controls out (that fade is only opacity): laid
      // out, not display:none or visibility:hidden, and not inside the player's "hidden_" class,
      // which is how it closes its menus.
      function present(el) {
        if (!el || !el.isConnected) return false;
        var r = el.getBoundingClientRect();
        if (r.width < 1 || r.height < 1) return false;
        for (var n = el; n && n.nodeType === 1; n = n.parentElement) {
          var cs = getComputedStyle(n);
          if (cs.display === 'none' || cs.visibility === 'hidden') return false;
          if (/(^|\s)hidden_/.test(String(n.className))) return false;
        }
        return true;
      }

      function togglePlay() {
        var v = document.querySelector('video');
        if (v) { if (v.paused) v.play(); else v.pause(); }
      }

      // Called by the app on Back: steps out of an open settings submenu, then closes whatever the
      // last OK opened (a season/episode/voice list, the settings menu). False when nothing is open.
      window.__lordSerialBack = function () {
        var subs = document.querySelectorAll('[class*="submenu_"]'), target = null;
        for (var i = 0; i < subs.length && !target; i++) {
          if (present(subs[i]) && present(subs[i].firstElementChild)) target = subs[i].firstElementChild;
        }
        if (!target) {
          // Drop what has closed since (a submenu closed from its header); the menu under it may
          // still be open.
          while (openers.length) {
            var top = openers[openers.length - 1];
            if (present(top.el) && top.opened.some(present)) break;
            openers.pop();
          }
          if (!openers.length) return false;
          target = openers.pop().el;
        }
        wake();
        press(target);
        whenSettled(function (list) {
          setCurrent(list.indexOf(target) >= 0 ? target : (list.length ? nearest(list, lastRect) : null));
        });
        return true;
      };

      var DIRS = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };
      window.addEventListener('keydown', function (e) {
        var dir = DIRS[e.key], ok = e.key === 'Enter' || e.keyCode === 13;
        if (e.key === 'MediaPlayPause') {
          togglePlay();
          e.preventDefault();
          return;
        }
        if (!dir && !ok) return;
        e.preventDefault();
        e.stopPropagation();
        var list = candidates(), hidden = !current || list.indexOf(current) < 0;
        wake();
        if (hidden) {
          var previous = current;
          whenSettled(function (again) {
            if (previous && again.indexOf(previous) >= 0) setCurrent(previous);
            else setCurrent(previous && lastRect && again.length ? nearest(again, lastRect) : playButton(again));
          });
          return;
        }
        if (isSlider(current)) {
          if (ok) { togglePlay(); return; }
          if (dir === 'left' || dir === 'right') {
            var video = document.querySelector('video');
            if (video && isFinite(video.duration)) {
              video.currentTime = Math.max(0, Math.min(video.duration - 1, video.currentTime + (dir === 'left' ? -10 : 10)));
            }
            return;
          }
        }
        if (ok) {
          var clicked = current;
          press(clicked);
          whenSettled(function (again) {
            var opened = again.filter(function (el) { return list.indexOf(el) < 0; });
            if (opened.length) openers.push({ el: clicked, opened: opened });
            if (again.indexOf(clicked) >= 0) setCurrent(clicked);
            else if (again.length) setCurrent(nearest(again, lastRect));
          });
          return;
        }
        var next = isSlider(current) && beforeSlider && list.indexOf(beforeSlider) >= 0 &&
          (dir === 'up' ? center(beforeSlider.getBoundingClientRect()).y < lastRect.top
                        : center(beforeSlider.getBoundingClientRect()).y > lastRect.bottom)
          ? beforeSlider : pick(current, dir, list);
        if (next && isSlider(next)) beforeSlider = current;
        if (next) setCurrent(next);
      }, true);
    })();
""".trimIndent()

class MainActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var loadError by mutableStateOf<String?>(null)
    private val progress by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()

        setContent {
            var selectedSeason by remember { mutableIntStateOf(progress.getInt(KEY_SEASON, 1)) }
            var selectedEpisode by remember { mutableIntStateOf(0) }
            var lastWatched by remember { mutableStateOf(readLastWatched()) }
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
            }

            BackHandler(enabled = selectedEpisode != 0) {
                val player = webView
                when {
                    player == null -> selectedEpisode = 0
                    // An open menu in the player closes first. Otherwise read the episode the player
                    // is on, which may have changed inside the player.
                    else -> player.evaluateJavascript(
                        "(function(){if(window.__lordSerialBack&&window.__lordSerialBack())return 'list';" +
                            "try{return localStorage.getItem('$LAST_EPISODE_KEY')||''}catch(e){return ''}})()"
                    ) { result ->
                        val value = result?.trim('"').orEmpty()
                        if (value != "list") {
                            val parts = value.split(":").mapNotNull { it.toIntOrNull() }
                            if (parts.size == 2 && parts[0] in 1..EPISODE_COUNTS.size &&
                                parts[1] in 1..EPISODE_COUNTS[parts[0] - 1]
                            ) {
                                openEpisode(parts[0], parts[1])
                            }
                            closePlayer()
                            selectedEpisode = 0
                        }
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
                                createPlayerWebView().also { player ->
                                    webView = player
                                    loadError = null
                                    // The player reopens the episode it saved last (localStorage
                                    // "vp<id>" = "season:episode") and ignores the one in the URL,
                                    // so a page on its origin stores the chosen one, then opens it.
                                    player.loadDataWithBaseURL(
                                        "https://$PLAYER_HOST/",
                                        "<script>try{localStorage.setItem('$LAST_EPISODE_KEY'," +
                                            "'$selectedSeason:$selectedEpisode')}catch(e){}" +
                                            "location.replace('$episodeUrl')</script>",
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                    player.requestFocus()
                                }
                            },
                            onRelease = { closePlayer() }
                        )
                        loadError?.let { error ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Не удалось открыть плеер", fontSize = 22.sp, color = ComposeColor.White)
                                Text(error, fontSize = 15.sp, color = MUTED)
                                Text("Назад — к сериям", fontSize = 15.sp, color = MUTED)
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun createPlayerWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            // The player picks its layout from the user agent: with "Mobile" in it, it shows the
            // touch layout, whose controls only appear on a tap. Without it, this TV's model name
            // ("MiTV") selects the player's own TV layout, the one the site shows on a TV.
            userAgentString = WebSettings.getDefaultUserAgent(context).replace(" Mobile", "")
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme != "https" && scheme != "http") return true
                // Ads in the player may try to take over the page; keep it on the player.
                return request.isForMainFrame && url.host != PLAYER_HOST
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadError = null
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    loadError = error.description?.toString().orEmpty()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(REMOTE_NAVIGATION_JS, null)
                view.requestFocus()
            }
        }
        webChromeClient = object : WebChromeClient() {
            // The app is already full screen. The player asks for fullscreen when playback starts,
            // and WebView would then take the first Back press to leave it, so it is declined.
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                callback.onCustomViewHidden()
            }
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
        loadError = null
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
