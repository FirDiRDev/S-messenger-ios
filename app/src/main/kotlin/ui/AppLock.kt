// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import com.stoyko.smessenger.R
import com.stoyko.smessenger.settings.Settings

private const val MIN_PIN = 4
private const val MAX_PIN = 8

/** «Клавиатура инкогнито»: просим клавиатуру не учиться на том, что пишет пользователь. */
fun applyPrivateKeyboard(edit: EditText) {
    if (Settings(edit.context).incognitoKeyboard) {
        edit.imeOptions = edit.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
}

/**
 * Блокировка приложения PIN-кодом.
 * PIN не хранится - только его хэш. Экран блокировки появляется при запуске
 * и при возвращении в приложение после заданного времени в фоне.
 */
object AppLock {
    private var backgroundAt = 0L
    private var unlocked = false
    private var dialog: Dialog? = null

    fun hash(pin: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest("smessenger-lock:$pin".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    fun onStop(activity: Activity) {
        if (!activity.isChangingConfigurations && dialog == null) backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onStart(activity: Activity, settings: Settings) {
        if (!settings.appLockEnabled) {
            unlocked = true
            return
        }
        if (dialog != null) return
        val away = SystemClock.elapsedRealtime() - backgroundAt
        val needLock = !unlocked || (backgroundAt > 0 && away >= settings.appLockTimeoutSeconds * 1000L)
        if (needLock) show(activity, settings)
    }

    fun onDestroy() {
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    /** Сразу заблокировать (например, из меню). */
    fun lockNow(activity: Activity, settings: Settings) {
        if (!settings.appLockEnabled) return
        unlocked = false
        show(activity, settings)
    }

    private fun show(activity: Activity, settings: Settings) {
        val d = object : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
            @Deprecated("Deprecated in Java")
            override fun onBackPressed() {
                // Назад на экране блокировки - свернуть приложение, а не пропустить проверку.
                activity.moveTaskToBack(true)
            }
        }
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_pin, null, false)
        val dots = root.findViewById<TextView>(R.id.pinDots)
        val entered = StringBuilder()
        fun render() {
            dots.text = "●".repeat(entered.length)
        }
        fun check() {
            if (hash(entered.toString()) == settings.appLockPinHash) {
                unlocked = true
                backgroundAt = 0
                d.dismiss()
                dialog = null
            } else {
                entered.clear()
                render()
                dots.text = activity.getString(R.string.pin_wrong)
                root.findViewById<View>(R.id.pinTitle).animate().translationX(24f).setDuration(60).withEndAction {
                    root.findViewById<View>(R.id.pinTitle).animate().translationX(0f)
                        .setInterpolator(android.view.animation.OvershootInterpolator(8f)).setDuration(300).start()
                }.start()
                root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
        val digits = intArrayOf(R.id.pin0, R.id.pin1, R.id.pin2, R.id.pin3, R.id.pin4, R.id.pin5, R.id.pin6, R.id.pin7, R.id.pin8, R.id.pin9)
        digits.forEachIndexed { n, id ->
            root.findViewById<View>(id).setOnClickListener {
                if (entered.length < MAX_PIN) {
                    entered.append(n)
                    render()
                    // Совпало - открываем без нажатия «OK».
                    if (entered.length >= MIN_PIN && hash(entered.toString()) == settings.appLockPinHash) check()
                }
            }
        }
        root.findViewById<View>(R.id.pinDel).setOnClickListener {
            if (entered.isNotEmpty()) entered.setLength(entered.length - 1)
            render()
        }
        root.findViewById<View>(R.id.pinOk).setOnClickListener { if (entered.isNotEmpty()) check() }
        d.setCancelable(false)
        d.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Скриншоты запрещаем только если это включено в настройках («Запретить скриншоты»).
        // Раньше экран блокировки всегда ставил запрет - и снимок экрана «не работал».
        if (settings.disableScreenshots) {
            d.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog = d
        d.show()
    }

    private fun pinField(context: Context, hint: Int) = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(MAX_PIN))
        setHint(hint)
    }

    /** Задать новый PIN (два раза). */
    fun askNewPin(context: Context, onSet: (String) -> Unit, onCancel: () -> Unit = {}) {
        val first = pinField(context, R.string.pin_new)
        val second = pinField(context, R.string.pin_repeat)
        val box = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(first)
            addView(second)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.pin_set_title)
            .setMessage(R.string.pin_set_hint)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val a = first.text.toString()
                val b = second.text.toString()
                if (a.length < MIN_PIN || a != b) {
                    Toast.makeText(context, R.string.pin_mismatch, Toast.LENGTH_LONG).show()
                    onCancel()
                } else {
                    onSet(hash(a))
                    unlocked = true
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    /** Подтвердить текущий PIN (перед отключением/сменой). */
    fun askCurrentPin(context: Context, settings: Settings, onOk: () -> Unit, onCancel: () -> Unit = {}) {
        val field = pinField(context, R.string.pin_enter)
        val box = android.widget.FrameLayout(context).apply {
            val p = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.pin_enter)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (hash(field.text.toString()) == settings.appLockPinHash) {
                    onOk()
                } else {
                    Toast.makeText(context, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                    onCancel()
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }
}
