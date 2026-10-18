// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.ChatManager

/**
 * «Запрет скриншотов и пересылки» в личном чате - общий для обоих собеседников.
 *
 * Включён: экран чата и всё, что из него открыто (фото, видео, музыка), нельзя снять скриншотом
 * или записью экрана (FLAG_SECURE, в списке недавних приложений - пустая карточка); сообщения и
 * файлы этого чата нельзя переслать, скопировать или сохранить - у обоих.
 *
 * Настройка уходит собеседнику обычным сообщением (как смена таймера автоудаления): у него она
 * применяется и показывается строкой по центру «🛡 Собеседник включил запрет…». Состояние - в
 * конце текста невидимыми символами (старые версии увидят просто понятную фразу).
 */
object ScreenGuard {
    private const val INV = "⁣"
    private const val HEAD = "$INV🛡"
    private const val ON = "$INV‌$INV"
    private const val OFF = "$INV‍$INV"

    private val _changes = MutableStateFlow(0)

    /** Меняется при любом включении/выключении - экраны обновляют флаг окна и значок. */
    val changes: StateFlow<Int> = _changes

    /** Чат с включённой защитой, открытый сейчас (окна, открытые из него, тоже защищаются). */
    @Volatile
    var activeChat: String? = null
        private set

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("chatfx_guard", Context.MODE_PRIVATE)

    fun isOn(ctx: Context, pk: String): Boolean = pk.isNotEmpty() && runCatching { prefs(ctx).getBoolean(pk, false) }.getOrDefault(false)

    fun isMarker(text: String): Boolean = text.startsWith(HEAD) && (text.endsWith(ON) || text.endsWith(OFF))

    private fun parse(text: String): Boolean? = if (!text.startsWith(HEAD)) null else when {
        text.endsWith(ON) -> true
        text.endsWith(OFF) -> false
        else -> null
    }

    private fun wire(ctx: Context, on: Boolean): String =
        HEAD + " " + ctx.getString(if (on) R.string.chatfx_guard_wire_on else R.string.chatfx_guard_wire_off) + if (on) ON else OFF

    private fun store(ctx: Context, pk: String, on: Boolean) {
        prefs(ctx).edit().apply { if (on) putBoolean(pk, true) else remove(pk) }.apply()
        _changes.value = _changes.value + 1
    }

    /** Переключили в меню чата: применяем и сообщаем собеседнику (дойдёт и после подключения). */
    fun setByUser(ctx: Context, chatManager: ChatManager, pk: PublicKey, on: Boolean) {
        if (isOn(ctx, pk.string()) == on) return
        store(ctx, pk.string(), on)
        chatManager.sendMessage(pk, wire(ctx, on))
    }

    /** Сообщение собеседника о защите - применить. */
    fun onIncoming(ctx: Context, pk: String, text: String) {
        val on = parse(text) ?: return
        if (isOn(ctx, pk) != on) store(ctx, pk, on)
    }

    fun systemText(ctx: Context, text: String, outgoing: Boolean): String {
        val on = parse(text) ?: return text
        return ctx.getString(
            when {
                on && outgoing -> R.string.chatfx_guard_you_on
                on -> R.string.chatfx_guard_peer_on
                outgoing -> R.string.chatfx_guard_you_off
                else -> R.string.chatfx_guard_peer_off
            },
        )
    }

    // ---------------- окна ----------------

    fun secure(window: Window?, on: Boolean) {
        runCatching {
            if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Окно (просмотр фото, плеер), открытое из защищённого чата, - тоже под защитой. */
    fun secureIfActive(window: Window?) {
        if (activeChat != null) secure(window, true)
    }

    /** Нельзя пересылать / копировать / сохранять (из защищённого чата). */
    val blocked: Boolean get() = activeChat != null

    private var installed = false

    /**
     * Экран чата открыт/закрыт или защиту переключили. Отдельные окна (видеоплеер) открываются
     * своими Activity - их защищаем через обратные вызовы приложения.
     */
    fun setActive(activity: Activity?, pk: String?) {
        activeChat = pk
        val app = activity?.application ?: return
        secure(activity.window, pk != null)
        if (!installed) {
            installed = true
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    if (activeChat != null) secure(a.window, true)
                }
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityResumed(a: Activity) {
                    if (activeChat != null) secure(a.window, true)
                }
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivityStopped(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            })
        }
    }
}
