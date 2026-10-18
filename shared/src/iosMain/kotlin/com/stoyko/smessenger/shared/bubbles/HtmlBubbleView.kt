// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

@file:OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)

package com.stoyko.smessenger.shared.bubbles

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNumber
import platform.UIKit.UIColor
import platform.UIKit.UIPasteboard
import platform.WebKit.WKAudiovisualMediaTypeAll
import platform.WebKit.WKContentWorld
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * Прокладка моста `SB` для WKWebView.
 *
 * На Android страница зовёт синхронный @JavascriptInterface `SB`. В WKWebView мост асинхронный
 * (WKScriptMessageHandler), поэтому:
 *  - в мире страницы создаётся `window.SB` с теми же методами; `SB.lite()` отвечает константой;
 *  - вызовы уходят DOM-событием в ИЗОЛИРОВАННЫЙ мир (WKContentWorld "sb"), куда скрипт пузыря
 *    не дотягивается. Там же ловятся только настоящие касания (event.isTrusted), и к каждому
 *    вызову прикладывается «сколько мс назад человек касался пузыря». Подделать это страница
 *    не может - правило «только после касания» соблюдается так же строго, как на Android.
 */
internal object BubbleIosShim {
    fun pageWorld(lite: Boolean) = """
        (function(){
          function send(a,t){document.dispatchEvent(new CustomEvent('sb-native',{detail:JSON.stringify({a:a,t:String(t==null?'':t)})}))}
          var LITE=${if (lite) "true" else "false"};
          window.SB={reply:function(t){send('reply',t)},copy:function(t){send('copy',t)},open:function(u){send('open',u)},
            toast:function(t){send('toast',t)},lite:function(){return LITE},height:function(h){send('height',h)}};
        })();
    """.trimIndent()

    val isolatedWorld = """
        (function(){
          var last=-1;
          ['touchstart','touchend','click'].forEach(function(n){
            document.addEventListener(n,function(e){if(e.isTrusted)last=Date.now()},true)});
          document.addEventListener('sb-native',function(e){
            var m;try{m=JSON.parse(e.detail)}catch(_){return}
            if(!m||typeof m.a!=='string')return;
            window.webkit.messageHandlers.sb.postMessage({a:m.a,t:String(m.t).slice(0,4000),age:last<0?-1:Date.now()-last});
          },true);
        })();
    """.trimIndent()
}

/** Колбэки пузыря в сторону чата. */
class BubbleCallbacks(
    val onReply: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onToast: (String) -> Unit = {},
    val onHeight: (Int) -> Unit = {},
)

private class BubbleMessageHandler(private val owner: BubbleWebController) : NSObject(), WKScriptMessageHandlerProtocol {
    override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
        val body = didReceiveScriptMessage.body as? Map<*, *> ?: return
        val action = body["a"] as? String ?: return
        val text = body["t"] as? String ?: ""
        val age = (body["age"] as? NSNumber)?.longValue ?: -1L
        owner.handle(action, text, age)
    }
}

/** Сеть и переходы: разрешена только сама страница (about:blank), всё остальное - отмена. */
private class BubbleNavigationGuard : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val scheme = decidePolicyForNavigationAction.request.URL?.scheme
        decisionHandler(
            if (scheme == null || scheme == "about") WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            else WKNavigationActionPolicy.WKNavigationActionPolicyCancel
        )
    }
}

/** Держит WKWebView, его делегатов (у WKWebView они weak) и правила моста. */
class BubbleWebController {
    var callbacks = BubbleCallbacks()
    private val policy = BubbleBridgePolicy()
    private val handler = BubbleMessageHandler(this)
    private val navGuard = BubbleNavigationGuard()
    private var loaded: String? = null

    fun create(lite: Boolean): WKWebView {
        val world = WKContentWorld.worldWithName("sb")
        val ucc = WKUserContentController()
        ucc.addUserScript(
            WKUserScript(
                source = BubbleIosShim.pageWorld(lite),
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
                inContentWorld = WKContentWorld.pageWorld,
            )
        )
        ucc.addUserScript(
            WKUserScript(
                source = BubbleIosShim.isolatedWorld,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
                inContentWorld = world,
            )
        )
        ucc.addScriptMessageHandler(handler, contentWorld = world, name = "sb")

        val cfg = WKWebViewConfiguration()
        cfg.userContentController = ucc
        cfg.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore() // без cookies/кэша на диске
        cfg.allowsInlineMediaPlayback = true
        cfg.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeAll

        return WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = cfg).apply {
            opaque = false
            backgroundColor = UIColor.clearColor
            scrollView.backgroundColor = UIColor.clearColor
            scrollView.scrollEnabled = false
            scrollView.bounces = false
            navigationDelegate = navGuard
        }
    }

    fun load(web: WKWebView, page: String) {
        if (loaded == page) return
        loaded = page
        web.loadHTMLString(page, baseURL = null)
    }

    fun dispose(web: WKWebView) {
        web.stopLoading()
        web.navigationDelegate = null
        web.configuration.userContentController.removeAllScriptMessageHandlers()
        web.configuration.userContentController.removeAllUserScripts()
        loaded = null
    }

    internal fun handle(action: String, text: String, touchAgeMs: Long) {
        when (action) {
            "height" -> text.toDoubleOrNull()?.let { callbacks.onHeight(it.toInt()) }
            "reply" -> policy.acceptReply(text, touchAgeMs)?.let(callbacks.onReply)
            "copy" -> if (policy.gesture(touchAgeMs)) {
                UIPasteboard.generalPasteboard.string = text.take(4000)
                callbacks.onToast("Скопировано")
            }
            "open" -> policy.acceptUrl(text, touchAgeMs)?.let(callbacks.onOpenUrl) // подтверждение - на стороне UI
            "toast" -> if (policy.gesture(touchAgeMs)) callbacks.onToast(text.take(200))
        }
    }
}

/**
 * HTML-пузырь в Compose Multiplatform (iOS): WKWebView внутри UIKitView.
 * Высота подстраивается под содержимое через SB.height() из SIZE_JS.
 */
@Composable
fun HtmlBubbleView(
    html: String,
    modifier: Modifier = Modifier,
    paramsJson: String = "{}",
    lite: Boolean = false,
    callbacks: BubbleCallbacks = BubbleCallbacks(),
) {
    var heightPt by remember { mutableStateOf(80) }
    val controller = remember { BubbleWebController() }
    controller.callbacks = BubbleCallbacks(
        onReply = callbacks.onReply,
        onOpenUrl = callbacks.onOpenUrl,
        onToast = callbacks.onToast,
        onHeight = { h -> heightPt = h.coerceIn(24, 2000); callbacks.onHeight(h) },
    )
    val page = remember(html, paramsJson) { BubblePage.page(html, paramsJson) }
    UIKitView(
        factory = { controller.create(lite) },
        update = { web -> controller.load(web, page) },
        onRelease = { web -> controller.dispose(web) },
        // CSS-пиксель в WKWebView = 1 pt = 1 dp в Compose на iOS.
        modifier = modifier.fillMaxWidth().height(heightPt.dp),
        properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
    )
}
