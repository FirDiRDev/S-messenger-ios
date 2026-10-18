// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import com.stoyko.smessenger.ui.safeDismiss
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.fitSystemBars

/** Ссылки YouTube в сообщениях: превью и просмотр прямо в мессенджере. */
object YouTube {
    private val ID = Regex(
        "(?:youtube\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/)|youtu\\.be/|m\\.youtube\\.com/watch\\?(?:[^\\s]*&)?v=)([A-Za-z0-9_-]{11})",
    )

    /** ID видео из первой ссылки YouTube в тексте (или null). */
    fun videoId(text: String): String? = ID.find(text)?.groupValues?.get(1)

    fun isShorts(text: String) = text.contains("/shorts/")

    fun thumbnail(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** Проигрыватель на весь экран, без перехода в приложение YouTube. */
    fun play(context: Context, id: String) {
        // Встроенный проигрыватель YouTube требует «адрес сайта»-источника - иначе ошибка 152/153.
        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
            </head><body>
            <iframe src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&origin=$ORIGIN"
              allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
              referrerpolicy="strict-origin-when-cross-origin"></iframe>
            </body></html>
        """.trimIndent()
        WebScreen(context, context.getString(R.string.youtube), "https://www.youtube.com/watch?v=$id") { web ->
            web.loadDataWithBaseURL(ORIGIN, html, "text/html", "utf-8", null)
        }.show()
    }

    /** Вкладка YouTube: лента шортсов и любые видео, вход в аккаунт сохраняется. */
    fun browse(context: Context, shorts: Boolean = true) {
        val url = if (shorts) "https://m.youtube.com/shorts" else "https://m.youtube.com"
        WebScreen(context, context.getString(R.string.youtube), url) { it.loadUrl(url) }.show()
    }

    /** Любая страница YouTube (канал, плейлист, шортсы) - во встроенном браузере. */
    fun open(context: Context, url: String) {
        val u = url.replace("://youtube.com", "://m.youtube.com").replace("://www.youtube.com", "://m.youtube.com")
        WebScreen(context, context.getString(R.string.youtube), url) { it.loadUrl(u) }.show()
    }

    private const val ORIGIN = "https://smessenger.stoyko.app"
}

/**
 * Экран со встроенным браузером: полноэкранное видео, куки (вход в аккаунт) сохраняются,
 * «Назад» - назад по страницам, кнопка «Открыть в браузере».
 */
private class WebScreen(
    private val context: Context,
    private val title: String,
    private val externalUrl: String,
    private val load: (WebView) -> Unit,
) {
    @SuppressLint("SetJavaScriptEnabled")
    fun show() {
        val d = context.resources.displayMetrics.density
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0F0F0F.toInt())
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        val web = WebView(context)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Без пометки «wv»: иначе часть сайтов считает браузер устаревшим.
            userAgentString = userAgentString.replace("; wv", "")
        }
        // Через VPN приложения (если он включён) - иначе в России YouTube часто не грузится.
        com.stoyko.smessenger.browser.WebProxy.prepare(web)
        com.stoyko.smessenger.browser.WebProxy.apply()
        val progress = android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFF4D4D.toInt())
            visibility = View.GONE
        }
        // Не загрузилось - понятная заглушка: «Повторить» и «Включить VPN».
        val errorBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF20F0F0F.toInt())
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
            visibility = View.GONE
            isClickable = true
        }
        val errorText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        errorBox.addView(TextView(context).apply { text = "📡"; textSize = 44f; gravity = Gravity.CENTER })
        errorBox.addView(errorText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (12 * d).toInt() })
        var failed = false
        var pageLoaded = false
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun showError(slow: Boolean) {
            failed = true
            progress.visibility = View.GONE
            val vpnOn = com.stoyko.smessenger.vpn.VpnState.running
            errorText.text = context.getString(if (slow) R.string.web_slow else R.string.web_failed) +
                if (vpnOn) "\n\n" + context.getString(R.string.web_try_other_mode) else "\n\n" + context.getString(R.string.web_need_vpn)
            if (errorBox.visibility != View.VISIBLE) {
                errorBox.alpha = 0f
                errorBox.visibility = View.VISIBLE
                errorBox.animate().alpha(1f).setDuration(220).start()
            }
        }
        fun retry() {
            failed = false
            pageLoaded = false
            errorBox.animate().alpha(0f).setDuration(160).withEndAction { errorBox.visibility = View.GONE }.start()
            com.stoyko.smessenger.browser.WebProxy.apply()
            if (web.url.isNullOrBlank() || web.url == "about:blank") load(web) else web.reload()
        }
        val btnRow = com.stoyko.smessenger.ui.Btn.row(
            context,
            listOf(
                com.stoyko.smessenger.ui.Btn.primary(context, context.getString(R.string.web_retry)) { retry() },
                com.stoyko.smessenger.ui.Btn.tonal(context, context.getString(R.string.web_vpn), 0xFF10B981.toInt()) {
                    com.stoyko.smessenger.vpn.VpnScreen(context).show()
                },
            ),
            height = 48,
        )
        errorBox.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * d).toInt() })
        // Долго нет ответа (провайдер «душит» трафик) - не держим чёрный экран, а подсказываем.
        val slowCheck = Runnable { if (!pageLoaded && !failed) showError(slow = true) }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // intent:// и vnd.youtube - открываем внутри, а не во внешнем приложении.
                return u.scheme != "http" && u.scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                progress.visibility = View.VISIBLE
                main.removeCallbacks(slowCheck)
                main.postDelayed(slowCheck, 25_000)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!failed) pageLoaded = true
                progress.visibility = View.GONE
                main.removeCallbacks(slowCheck)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) {
                    main.removeCallbacks(slowCheck)
                    showError(slow = false)
                }
            }
        }
        // VPN включился/переключился - сразу пускаем страницу через него и перезагружаем.
        var lastVpn = com.stoyko.smessenger.vpn.VpnState.status
        val vpnListener: () -> Unit = {
            val st = com.stoyko.smessenger.vpn.VpnState.status
            if (st != lastVpn) {
                lastVpn = st
                com.stoyko.smessenger.browser.WebProxy.apply()
                if (st == com.stoyko.smessenger.vpn.VpnState.Status.On && (failed || !pageLoaded)) retry()
            }
        }
        com.stoyko.smessenger.vpn.VpnState.listen(vpnListener)
        var custom: View? = null
        val chrome = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                custom = view
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                column.visibility = View.GONE
            }

            override fun onHideCustomView() {
                custom?.let { root.removeView(it) }
                custom = null
                column.visibility = View.VISIBLE
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (android.os.Build.VERSION.SDK_INT >= 24) progress.setProgress(newProgress, true) else progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }
        }
        web.webChromeClient = chrome
        bar.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()),
        )
        bar.addView(
            TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 17f
                paint.isFakeBoldText = true
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // Скачать открытое видео в галерею.
        bar.addView(
            TextView(context).apply {
                text = "⬇"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setOnClickListener {
                    com.stoyko.smessenger.media.VideoDownloader.show(context, web.url ?: externalUrl)
                }
            },
            LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()),
        )
        bar.addView(
            TextView(context).apply {
                setText(R.string.open_in_browser)
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
                setOnClickListener {
                    val url = web.url?.takeIf { it.startsWith("http") && !it.startsWith("https://smessenger.") } ?: externalUrl
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            },
        )
        column.addView(bar)
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * d).toInt()))
        val webBox = FrameLayout(context)
        webBox.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webBox.addView(errorBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(webBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.let {
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                when {
                    custom != null -> {
                        chrome.onHideCustomView()
                        true
                    }
                    web.canGoBack() -> {
                        web.goBack()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
        dialog.setOnDismissListener {
            com.stoyko.smessenger.vpn.VpnState.unlisten(vpnListener)
            main.removeCallbacks(slowCheck)
            CookieManager.getInstance().flush()
            runCatching {
                web.stopLoading()
                web.destroy()
            }
        }
        load(web)
        dialog.show()
    }
}
