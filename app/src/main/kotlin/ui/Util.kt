// SPDX-FileCopyrightText: 2019-2022 Robin Lindén <dev@robinlinden.eu>
// SPDX-FileCopyrightText: 2021-2022 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Contact
import com.stoyko.smessenger.core.vo.UserStatus

internal fun colorFromStatus(context: Context, status: UserStatus) = when (status) {
    UserStatus.None -> ContextCompat.getColor(context, R.color.statusAvailable)
    UserStatus.Away -> ContextCompat.getColor(context, R.color.statusAway)
    UserStatus.Busy -> ContextCompat.getColor(context, R.color.statusBusy)
}

internal sealed interface Size

@JvmInline
internal value class Px(val px: Int) : Size

@JvmInline
internal value class Dp(val dp: Float) : Size {
    fun asPx(res: Resources): Px =
        Px(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, res.displayMetrics).toInt())
}

internal fun contactListSorter(contact: Contact) = when {
    contact.lastMessage != 0L -> contact.lastMessage
    contact.connectionStatus == ConnectionStatus.None -> -1000L
    else -> -contact.status.ordinal.toLong()
}

/** Параметры оформления, которые нужны спискам сообщений без доступа к настройкам. */
object UiPrefs {
    @Volatile
    var messageTextSize = 16f

    @Volatile
    var sendByEnter = false

    @Volatile
    var animations = true

    fun load(settings: com.stoyko.smessenger.settings.Settings) {
        // Слабое устройство (Redmi A, POCO C, nova Y, Galaxy A0x/M1x…): один раз при первом
        // запуске включаем облегчённые анимации. Пользователь может вернуть их в настройках.
        runCatching {
            val ctx = settings.appContext()
            val p = ctx.getSharedPreferences("device_profile", android.content.Context.MODE_PRIVATE)
            if (!p.getBoolean("lite_checked", false)) {
                p.edit().putBoolean("lite_checked", true).apply()
                if (DeviceProfile.lite(ctx)) settings.animations = false
            }
        }
        messageTextSize = settings.messageTextSize.toFloat()
        sendByEnter = settings.sendByEnter
        animations = settings.animations
        GlassFrameLayout.liquidEnabled = settings.animations
        Kinetics.enabled = settings.animations
        Accessibility.applyRuntime(settings.appContext(), fresh = true)
    }
}

/** «Отправка по Enter» (Настройки → Чаты): клавиша Enter/«Отправить» на клавиатуре сразу отправляет. */
fun applySendByEnter(edit: android.widget.EditText, send: android.view.View) {
    if (!UiPrefs.sendByEnter) return
    edit.imeOptions = (edit.imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION.inv()) or
        android.view.inputmethod.EditorInfo.IME_ACTION_SEND
    edit.setRawInputType(edit.inputType and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv())
    edit.setHorizontallyScrolling(false)
    edit.maxLines = 6
    edit.setOnEditorActionListener { _, actionId, event ->
        val enter = event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
            event.action == android.view.KeyEvent.ACTION_DOWN && !event.isShiftPressed
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
            send.performClick()
            true
        } else {
            false
        }
    }
}

/**
 * Закрыть окно без падения: если экран уже пересоздан (поворот, смена темы, выход) и окно
 * отвязано от системы, обычный dismiss() бросает исключение «not attached to window manager».
 */
fun android.app.Dialog.safeDismiss() {
    runCatching {
        if (isShowing && window?.decorView?.isAttachedToWindow == true) dismiss()
    }
}
