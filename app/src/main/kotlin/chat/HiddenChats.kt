// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ai.t

/**
 * Скрытые чаты, как в Viber: чат не виден в списке, пока в поиске не ввести его PIN-код (4-8 цифр).
 *
 * 4.10.0 - переделано:
 *  - PIN хранится как PBKDF2 с солью этого телефона (раньше - SHA-256 с общей солью, 4 цифры
 *    подбирались мгновенно); старые скрытые чаты открываются прежним PIN и сами переходят на новый формат;
 *  - скрытый чат больше нельзя открыть в обход PIN: из уведомления, «следующим непрочитанным»,
 *    из профиля, пересылки и т. д. - чат сам спрашивает PIN ([gate]);
 *  - введённый PIN открывает чат до ухода из приложения (или выключения экрана), потом - снова PIN;
 *  - при скрытии PIN вводится дважды (опечатка больше не прячет чат навсегда);
 *  - после 5 неверных PIN подряд - пауза 30 с;
 *  - уведомление скрытого чата без имени, текста и кнопки «Ответить» и ведёт в список чатов, а не в чат.
 */
object HiddenChats {
    private const val ITER = 6_000
    private const val V2 = "v2:"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hidden_chats", Context.MODE_PRIVATE)
    private fun meta(ctx: Context) = ctx.getSharedPreferences("hidden_chats_meta", Context.MODE_PRIVATE)

    private fun legacyHash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest(("smessenger-hidden:" + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun pbkdf2(pin: String, salt: String): String = runCatching {
        val spec = PBEKeySpec(pin.toCharArray(), ("sm-hidden:" + salt).toByteArray(), ITER, 256)
        hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded)
    }.getOrElse { legacyHash(salt + pin) }

    /** Соль хранится рядом, но своя у каждого скрытого чата: одинаковые PIN не видны как одинаковые. */
    private fun newRecord(pin: String): String {
        val salt = ByteArray(12).also { SecureRandom().nextBytes(it) }.let { hex(it) }
        return V2 + salt + ":" + pbkdf2(pin, salt)
    }

    private fun matches(record: String?, pin: String): Boolean {
        if (record == null) return false
        if (record.startsWith(V2)) {
            val parts = record.removePrefix(V2).split(':')
            if (parts.size != 2) return false
            return MessageDigest.isEqual(pbkdf2(pin, parts[0]).toByteArray(), parts[1].toByteArray())
        }
        return MessageDigest.isEqual(legacyHash(pin).toByteArray(), record.toByteArray())
    }

    fun isValidPin(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    fun ids(ctx: Context): Set<String> = prefs(ctx).all.keys

    fun isHidden(ctx: Context, pk: String) = prefs(ctx).contains(pk)

    fun hide(ctx: Context, pk: String, pin: String) {
        prefs(ctx).edit().putString(pk, newRecord(pin)).apply()
        session.remove(pk)
        lastPin = ""
    }

    fun unhide(ctx: Context, pk: String) {
        prefs(ctx).edit().remove(pk).apply()
        session.remove(pk)
        lastPin = ""
    }

    // ---------- Открытые в этом сеансе ----------

    private val session: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** Можно ли сейчас показать чат (не скрыт или открыт PIN в этом сеансе). */
    fun canOpen(ctx: Context, pk: String) = !isHidden(ctx, pk) || pk in session

    /** Для списков (пересылка, приглашения, «Поделиться»): скрытые - только если открыты PIN. */
    fun visible(ctx: Context, pk: String) = canOpen(ctx, pk)

    private var started = 0

    /** App.onCreate: забыть открытые PIN-ом чаты, когда приложение ушло в фон или погас экран. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
            }

            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // Поворот экрана - не уход из приложения.
                if (started == 0 && !a.isChangingConfigurations) session.clear()
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    // ---------- Проверка PIN ----------

    @Volatile
    private var lastPin = ""
    @Volatile
    private var lastResult: List<String> = emptyList()

    /** Чаты, открываемые этим PIN (и они запоминаются как открытые в этом сеансе). */
    fun unlock(ctx: Context, pin: String): List<String> {
        if (!isValidPin(pin)) return emptyList()
        // Поиск зовёт это на каждую букву: тот же PIN не пересчитываем.
        if (pin == lastPin && lastResult.all { isHidden(ctx, it) }) return lastResult.also { session.addAll(it) }
        val p = prefs(ctx)
        val found = ArrayList<String>()
        val upgrade = p.edit()
        var upgraded = false
        for ((pk, v) in p.all) {
            val rec = v as? String ?: continue
            if (matches(rec, pin)) {
                found += pk
                if (!rec.startsWith(V2)) {
                    upgrade.putString(pk, newRecord(pin))
                    upgraded = true
                }
            }
        }
        if (upgraded) upgrade.apply()
        lastPin = pin
        lastResult = found
        session.addAll(found)
        return found
    }

    private fun lockedFor(ctx: Context): Long {
        val until = meta(ctx).getLong("lock_until", 0L)
        val left = until - SystemClock.elapsedRealtime()
        return if (left in 1..60_000) left else 0L
    }

    private fun registerFail(ctx: Context) {
        val m = meta(ctx)
        val n = m.getInt("fails", 0) + 1
        if (n >= 5) {
            m.edit().putInt("fails", 0).putLong("lock_until", SystemClock.elapsedRealtime() + 30_000).apply()
        } else {
            m.edit().putInt("fails", n).apply()
        }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    /**
     * Поле PIN. Подсказка - мелким шрифтом и без разрядки (раньше крупная подсказка
     * «PIN из 4-8 цифр» не влезала в поле на узких экранах и при крупном шрифте); точки
     * введённых цифр - крупно и с разрядкой, как в замке телефона.
     */
    private fun pinField(ctx: Context, hint: CharSequence = t("PIN: 4-8 цифр", "PIN: 4-8 digits")) = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(8))
        gravity = Gravity.CENTER
        setSingleLine()
        this.hint = hint
        fun style(empty: Boolean) {
            textSize = if (empty) 15f else 22f
            letterSpacing = if (empty) 0f else 0.3f
        }
        style(true)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = style(e.isNullOrEmpty())
        })
        minHeight = dp(ctx, 48)
    }

    /**
     * Вход в скрытый чат: открыт в этом сеансе - [ok] сразу; иначе окно PIN (проверяется на лету,
     * без кнопки). Неверный PIN или «Отмена» - [denied].
     */
    fun gate(ctx: Context, pk: String, ok: () -> Unit, denied: () -> Unit) {
        if (canOpen(ctx, pk)) {
            ok()
            return
        }
        val field = pinField(ctx)
        val note = TextView(ctx).apply {
            textSize = 13f
            alpha = 0.75f
            gravity = Gravity.CENTER
            text = t("Этот чат скрыт. Введите его PIN.", "This chat is hidden. Enter its PIN.")
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 24), dp(ctx, 8), dp(ctx, 24), 0)
            addView(note)
            addView(field)
        }
        var passed = false
        val dlg = AlertDialog.Builder(ctx)
            .setTitle("🔒 " + ctx.getString(R.string.hidden_chat_open))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        fun check(force: Boolean) {
            val pin = field.text.toString()
            val wait = lockedFor(ctx)
            if (wait > 0) {
                note.text = t("Много неверных попыток. Подождите ${wait / 1000 + 1} с", "Too many attempts. Wait ${wait / 1000 + 1} s")
                return
            }
            if (!isValidPin(pin)) {
                if (force) note.text = ctx.getString(R.string.hide_chat_bad_pin)
                return
            }
            if (pk in unlock(ctx, pin)) {
                passed = true
                meta(ctx).edit().putInt("fails", 0).apply()
                dlg.dismiss()
                ok()
            } else if (force || pin.length == 8) {
                registerFail(ctx)
                field.setText("")
                note.text = t("Неверный PIN", "Wrong PIN")
                field.animate().translationX(dp(ctx, 10).toFloat()).setDuration(50).withEndAction {
                    field.animate().translationX(0f).setDuration(120).start()
                }.start()
            }
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = check(false)
        })
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener { check(true) }
            field.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.setOnDismissListener { if (!passed) denied() }
        dlg.show()
    }

    /**
     * Скрыть чат: PIN вводится дважды. [done] - после скрытия.
     * Тот же PIN, что у других скрытых чатов, откроет их все разом.
     */
    fun askHide(ctx: Context, pk: String, done: () -> Unit) {
        val first = pinField(ctx)
        val second = pinField(ctx, t("Повторите PIN", "Repeat PIN"))
        // Пояснение коротко, предупреждение - мелким шрифтом: всё помещается вместе с полями
        // даже на 720 px и при крупном системном шрифте (раньше текст выталкивал поля за край).
        val info = TextView(ctx).apply {
            textSize = 13f
            alpha = 0.85f
            text = ctx.getString(R.string.hide_chat_explain)
        }
        val warn = TextView(ctx).apply {
            textSize = 11.5f
            alpha = 0.65f
            text = ctx.getString(R.string.no_recovery_warning)
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 4))
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 22), dp(ctx, 6), dp(ctx, 22), 0)
            addView(info)
            addView(first)
            addView(second)
            addView(warn)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(box) }
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.hide_chat)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val pin = first.text.toString()
                when {
                    !isValidPin(pin) -> Toast.makeText(ctx, R.string.hide_chat_bad_pin, Toast.LENGTH_LONG).show()
                    second.text.toString() != pin -> {
                        second.setText("")
                        Toast.makeText(ctx, t("PIN не совпадает - введите ещё раз", "PINs don't match - try again"), Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        hide(ctx, pk, pin)
                        dlg.dismiss()
                        Toast.makeText(ctx, R.string.hide_chat_done, Toast.LENGTH_LONG).show()
                        done()
                    }
                }
            }
            first.requestFocus()
            dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dlg.show()
    }
}
