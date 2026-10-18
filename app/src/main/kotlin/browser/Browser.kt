// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.browser

import android.annotation.SuppressLint
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.StorageCleaner
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Встроенный браузер с ИИ: домашняя страница - Duck.ai (бесплатный чат с ИИ от DuckDuckGo,
 * без регистрации). Адресная строка (адрес или поиск DuckDuckGo), вперёд/назад, обновить,
 * быстрые кнопки, загрузка файлов в «Загрузки», режим ПК, очистка кэша/данных.
 * Вход в аккаунты (куки) сохраняется между запусками.
 */
object Browser {
    const val HOME = "https://duck.ai"
    private const val SEARCH = "https://duckduckgo.com/?q="

    private val quick = listOf(
        "Duck.ai" to HOME,
        "DuckDuckGo" to "https://duckduckgo.com",
        "YouTube" to "https://m.youtube.com",
        "Pinterest" to "https://www.pinterest.com/",
        "Wikipedia" to "https://ru.m.wikipedia.org",
        "GitHub" to "https://github.com",
    )

    /** Адрес из того, что ввёл человек: ссылка как есть, иначе - поиск. */
    fun toUrl(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return HOME
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (!t.contains(' ') && t.contains('.') && !t.endsWith('.')) return "https://$t"
        return SEARCH + Uri.encode(t)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(context: Context, url: String = HOME) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF121214.toInt() else 0xFFF7F7FA.toInt()
        val fg = if (night) Color.WHITE else 0xFF1B1B1F.toInt()
        val field = if (night) 0xFF26262B.toInt() else 0xFFE8E8EE.toInt()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar)
        val root = FrameLayout(context).apply { setBackgroundColor(bg) }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val web = WebView(context)
        var desktop = false

        fun iconButton(icon: Int, click: (View) -> Unit) = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(fg)
            background = null
            setOnClickListener(click)
        }

        // --- Адресная строка ---
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val address = EditText(context).apply {
            setSingleLine()
            textSize = 15f
            setTextColor(fg)
            setHintTextColor(0xFF8A8A93.toInt())
            setHint(R.string.browser_hint)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20 * d
                setColor(field)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setSelectAllOnFocus(true)
        }
        fun go(input: String) {
            web.loadUrl(toUrl(input))
            address.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(address.windowToken, 0)
        }
        address.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                go(v.text.toString())
                true
            } else {
                false
            }
        }
        bar.addView(iconButton(R.drawable.ic_close) { dialog.dismiss() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(address, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton(R.drawable.ic_refresh) { web.reload() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val more = iconButton(R.drawable.ic_tune) {}
        bar.addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))

        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }

        // --- Быстрые кнопки ---
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        for ((label, link) in quick) {
            chips.addView(
                TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(fg)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * d
                        setColor(field)
                    }
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener { web.loadUrl(link) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(6)
                },
            )
        }
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }

        // --- Нижняя панель: назад / вперёд / домой ---
        val nav = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }
        val back = iconButton(R.drawable.ic_back) { if (web.canGoBack()) web.goBack() }
        val forward = iconButton(R.drawable.ic_back) { if (web.canGoForward()) web.goForward() }.apply { rotation = 180f }
        val home = iconButton(R.drawable.ic_chat) { web.loadUrl(HOME) }
        val share = iconButton(R.drawable.ic_share) {
            val u = web.url ?: return@iconButton
            runCatching {
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        listOf(back, forward, home, share).forEach {
            nav.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        fun updateNav() {
            back.alpha = if (web.canGoBack()) 1f else 0.35f
            forward.alpha = if (web.canGoForward()) 1f else 0.35f
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // Без меток WebView («; wv», «Version/4.0»): иначе Pinterest и некоторые сайты считают
        // браузер «встроенным» и показывают пустую страницу или «откройте в приложении».
        val mobileUa = web.settings.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        val desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            // Экономия трафика: кэш используется, если страница не изменилась.
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = mobileUa
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            loadsImagesAutomatically = true
            @Suppress("DEPRECATION")
            allowFileAccess = false
        }
        WebProxy.prepare(web)
        // Браузер ходит через встроенный VPN (сам мессенджер исключён из туннеля) - иначе
        // заблокированные сайты (картинки Pinterest и т. п.) не грузятся.
        WebProxy.apply()
        // VPN включился - сразу пускаем страницу через него и обновляем её (Pinterest и др.
        // иначе так и остаются недогруженными).
        var lastVpnOn = com.stoyko.smessenger.vpn.VpnState.running
        val vpnListener: () -> Unit = {
            WebProxy.apply()
            val on = com.stoyko.smessenger.vpn.VpnState.running
            if (on != lastVpnOn) {
                lastVpnOn = on
                if (on && !web.url.isNullOrBlank()) runCatching { web.reload() }
            }
        }
        com.stoyko.smessenger.vpn.VpnState.listen(vpnListener)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https") return false
                // intent://… (Pinterest, YouTube и др. зовут своё приложение): если есть веб-адрес
                // для браузера - открываем его здесь же, а не уходим из мессенджера.
                if (u.scheme == "intent") {
                    val fallback = runCatching {
                        Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                    }.getOrNull()
                    if (!fallback.isNullOrBlank() && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                        view.loadUrl(fallback)
                    }
                    return true
                }
                // tel:, mailto:, market: и т. п. - в системные приложения.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!address.hasFocus()) address.setText(url.orEmpty())
                updateNav()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!address.hasFocus()) address.setText(url.orEmpty())
                updateNav()
                CookieManager.getInstance().flush()
            }
        }
        var custom: View? = null
        // Скрепка на сайтах (в т. ч. в чате с ИИ): выбор фото, видео и файлов с телефона.
        var fileCallback: android.webkit.ValueCallback<Array<Uri>>? = null
        val registry = (context as? androidx.activity.ComponentActivity)?.activityResultRegistry
        val chooser = registry?.register(
            "browser_files_${System.nanoTime()}",
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
        ) { res ->
            val cb = fileCallback
            fileCallback = null
            val data = res.data
            val uris: Array<Uri>? = when {
                res.resultCode != android.app.Activity.RESULT_OK -> null
                data?.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                else -> WebChromeClient.FileChooserParams.parseResult(res.resultCode, data)
            }
            cb?.onReceiveValue(uris)
        }
        val chrome = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: android.webkit.ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val launcher = chooser
                if (launcher == null) {
                    fileCallback = null
                    return false
                }
                // Что просит сайт (картинки, видео…) - или любые файлы; несколько - если сайт разрешает.
                val types = params.acceptTypes.orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (types.size == 1 && '/' in types[0]) types[0] else "*/*"
                    val mimes = types.filter { '/' in it }
                    if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return runCatching {
                    launcher.launch(Intent.createChooser(pick, context.getString(R.string.attach)))
                    true
                }.getOrElse {
                    fileCallback = null
                    false
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

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
        }
        web.webChromeClient = chrome
        web.setDownloadListener { link, ua, disposition, mime, _ ->
            try {
                val name = URLUtil.guessFileName(link, disposition, mime)
                val req = DownloadManager.Request(Uri.parse(link))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", ua)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(link).orEmpty())
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(context, context.getString(R.string.browser_downloading, name), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }

        more.setOnClickListener { v ->
            val p = PopupMenu(context, v)
            p.menu.add(0, 1, 0, if (desktop) R.string.browser_mobile_mode else R.string.browser_desktop_mode)
            p.menu.add(0, 2, 1, R.string.open_in_browser)
            p.menu.add(0, 3, 2, R.string.browser_copy_link)
            p.menu.add(0, 4, 3, R.string.browser_clear_cache_only)
            p.menu.add(0, 5, 4, R.string.browser_clear_everything)
            // Видео YouTube/Pinterest с этой страницы - в галерею.
            if (com.stoyko.smessenger.media.VideoDownloader.supports(web.url)) p.menu.add(0, 6, 0, R.string.vdl_title)
            p.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    6 -> com.stoyko.smessenger.media.VideoDownloader.show(context, web.url)
                    1 -> {
                        desktop = !desktop
                        web.settings.userAgentString = if (desktop) desktopUa else mobileUa
                        web.reload()
                    }
                    2 -> web.url?.let { u ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    3 -> web.url?.let { u ->
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", u))
                        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        web.clearCache(true)
                        Thread { StorageCleaner.clearBrowserCache(context) }.start()
                        Toast.makeText(context, R.string.storage_cleared, Toast.LENGTH_SHORT).show()
                    }
                    5 -> {
                        web.clearCache(true)
                        web.clearHistory()
                        StorageCleaner.clearBrowserAll(context)
                        Toast.makeText(context, R.string.storage_cleared, Toast.LENGTH_SHORT).show()
                        web.loadUrl(HOME)
                    }
                }
                true
            }
            p.show()
        }

        column.addView(bar)
        column.addView(chipsScroll)
        column.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        column.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(nav)
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
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
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            runCatching { chooser?.unregister() }
            com.stoyko.smessenger.vpn.VpnState.unlisten(vpnListener)
            CookieManager.getInstance().flush()
            runCatching {
                web.stopLoading()
                web.destroy()
            }
        }
        web.loadUrl(url)
        dialog.show()
    }
}

/**
 * Прокси для встроенного браузера: когда включён встроенный VPN, страницы идут через
 * HTTP-прокси ядра Xray (127.0.0.1:10809); выключили VPN - напрямую.
 */
object WebProxy {
    private var applied: Boolean? = null

    /** Не отправлять сайтам имя пакета приложения (X-Requested-With) - Pinterest и др. из-за него «ломаются». */
    fun prepare(web: WebView) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(web.settings, emptySet())
            }
        }
    }

    fun apply() {
        val want = com.stoyko.smessenger.vpn.VpnState.proxyReady
        if (applied == want) return
        runCatching {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return
            val pc = androidx.webkit.ProxyController.getInstance()
            if (want) {
                val cfg = androidx.webkit.ProxyConfig.Builder()
                    .addProxyRule("127.0.0.1:${com.stoyko.smessenger.vpn.VpnLinks.HTTP_PORT}")
                    .addBypassRule("<local>")
                    .build()
                pc.setProxyOverride(cfg, { it.run() }, {})
            } else {
                pc.clearProxyOverride({ it.run() }, {})
            }
            applied = want
        }.onFailure { android.util.Log.w("WebProxy", "proxy: $it") }
    }
}
