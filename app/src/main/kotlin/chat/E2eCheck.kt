// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.CustomPacketError
import com.stoyko.smessenger.domain.tox.Tox

/**
 * Проверка сквозного шифрования в реальном времени.
 *
 * Каждое сообщение, звонок и файл шифруются на телефоне отправителя ключом получателя
 * (Curve25519 + XSalsa20-Poly1305) и расшифровываются только на телефоне получателя.
 * Проверка показывает это наглядно:
 *  1. ключи - ваш и собеседника (отпечатки);
 *  2. канал - напрямую (UDP) или через узел-ретранслятор (TCP; узел видит только шифр);
 *  3. живой тест - отправляем собеседнику зашифрованный пакет со случайным «вызовом»; ответить
 *     правильно может только тот, у кого закрытый ключ собеседника. Замеряем время туда-обратно;
 *  4. код безопасности - сверьте его с собеседником при встрече или по звонку.
 */
object E2eCheck {
    // Свои пакеты без потерь: ядро требует первый байт в диапазоне 160-191.
    private const val PING: Byte = 0xA7.toByte()
    private const val PONG: Byte = 0xA8.toByte()
    private const val CHALLENGE = 24

    private val random = SecureRandom()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

    /** Входящий пакет без потерь. true - это пакет проверки (обработан). */
    fun handle(tox: Tox, pk: String, data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        when (data[0]) {
            PING -> {
                // Отвечаем тем же вызовом: собеседник убедится, что пакет расшифровали именно мы.
                val reply = data.copyOf()
                reply[0] = PONG
                runCatching { tox.sendLosslessPacket(PublicKey(pk), reply) }
                return true
            }
            PONG -> {
                val key = pk.uppercase() + ":" + data.drop(1).joinToString("") { "%02x".format(it) }
                pending.remove(key)?.complete(data)
                return true
            }
        }
        return false
    }

    // Запасной путь - скрытое служебное сообщение (как карточка профиля): доходит всегда, когда
    // доходят обычные сообщения. Нужен, если пакеты без потерь где-то по пути не проходят.
    private const val MSG_PING = "\u2063🔒?:"
    private const val MSG_PONG = "\u2063🔒!:"

    /** Входящее служебное сообщение проверки. true - обработано (в чат не писать). */
    fun handleMessage(tox: Tox, pk: String, text: String): Boolean {
        when {
            text.startsWith(MSG_PING) -> {
                runCatching {
                    tox.sendMessage(PublicKey(pk), MSG_PONG + text.removePrefix(MSG_PING), com.stoyko.smessenger.core.vo.MessageType.Normal)
                }
                return true
            }
            text.startsWith(MSG_PONG) -> {
                val key = pk.uppercase() + ":" + text.removePrefix(MSG_PONG).lowercase()
                pending.remove(key)?.complete(ByteArray(0))
                return true
            }
        }
        return false
    }

    /** Время туда-обратно в мс; null - нет ответа (не в сети или старая версия). */
    suspend fun ping(tox: Tox, pk: String, timeoutMs: Long = 8000): Long? {
        // 1) быстрый путь - пакет без потерь; 2) если ответа нет за 3 с - скрытое сообщение.
        pingLossless(tox, pk, 3000)?.let { return it }
        return pingMessage(tox, pk, timeoutMs)
    }

    private suspend fun pingMessage(tox: Tox, pk: String, timeoutMs: Long): Long? {
        val challenge = ByteArray(CHALLENGE).also { random.nextBytes(it) }
        val hex = challenge.joinToString("") { "%02x".format(it) }
        val key = pk.uppercase() + ":" + hex
        val wait = CompletableDeferred<ByteArray>()
        pending[key] = wait
        val start = System.nanoTime()
        val ok = runCatching {
            tox.sendMessage(PublicKey(pk), MSG_PING + hex, com.stoyko.smessenger.core.vo.MessageType.Normal)
        }.isSuccess
        if (!ok) {
            pending.remove(key)
            return null
        }
        val reply = withTimeoutOrNull(timeoutMs) { wait.await() }
        pending.remove(key)
        return if (reply != null) (System.nanoTime() - start) / 1_000_000 else null
    }

    private suspend fun pingLossless(tox: Tox, pk: String, timeoutMs: Long): Long? {
        val challenge = ByteArray(CHALLENGE).also { random.nextBytes(it) }
        val packet = byteArrayOf(PING) + challenge
        val key = pk.uppercase() + ":" + challenge.joinToString("") { "%02x".format(it) }
        val wait = CompletableDeferred<ByteArray>()
        pending[key] = wait
        val start = System.nanoTime()
        val sent = runCatching { tox.sendLosslessPacket(PublicKey(pk), packet) }.getOrNull()
        if (sent != CustomPacketError.Success) {
            pending.remove(key)
            return null
        }
        val reply = withTimeoutOrNull(timeoutMs) { wait.await() }
        pending.remove(key)
        return if (reply != null) (System.nanoTime() - start) / 1_000_000 else null
    }

    /** Код безопасности (тот же, что в профиле контакта): 12 групп по 5 цифр. */
    fun securityCode(selfPk: String, peerPk: String): String {
        val keys = listOf(selfPk.uppercase(), peerPk.uppercase()).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest((keys[0] + keys[1]).toByteArray())
        val groups = (0 until 12).map { i ->
            val v = ((digest[i * 2].toInt() and 0xFF) shl 8) or (digest[i * 2 + 1].toInt() and 0xFF)
            "%05d".format(v % 100000)
        }
        return groups.chunked(4).joinToString("\n") { it.joinToString(" ") }
    }

    private fun fingerprint(pk: String) = pk.uppercase().take(16).chunked(4).joinToString(" ")

    /**
     * Окно проверки с анимацией: щит «сканирует», шаги по очереди загораются галочками.
     */
    fun show(context: Context, scope: LifecycleCoroutineScope, tox: Tox, peerPk: String, peerName: String, connection: ConnectionStatus) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = ContextCompat.getColor(context, R.color.textPrimary)
        val secondary = ContextCompat.getColor(context, R.color.textSecondary)
        val ok = 0xFF22C55E.toInt()
        val warn = 0xFFF5A524.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(6))
        }
        // Щит с вращающимся кольцом.
        val shieldBox = FrameLayout(context)
        val ring = ProgressBar(context).apply { isIndeterminate = true }
        val shield = TextView(context).apply {
            text = "🛡"
            textSize = 40f
            gravity = Gravity.CENTER
        }
        shieldBox.addView(ring, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        shieldBox.addView(shield, FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER))
        root.addView(shieldBox, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val pulse = ObjectAnimator.ofFloat(shield, View.SCALE_X, 1f, 1.12f).apply {
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { shield.scaleY = shield.scaleX }
            start()
        }
        val status = TextView(context).apply {
            setText(R.string.e2e_checking)
            textSize = 16f
            setTextColor(primary)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(status)

        fun step(title: String): Pair<TextView, TextView> {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                alpha = 0f
                translationX = dp(24).toFloat()
            }
            val mark = TextView(context).apply {
                text = "⏳"
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(primary) })
            val detail = TextView(context).apply { textSize = 13f; setTextColor(secondary) }
            box.addView(detail)
            row.addView(mark, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            root.addView(row)
            row.animate().alpha(1f).translationX(0f).setDuration(260).start()
            return mark to detail
        }

        fun done(mark: TextView, good: Boolean, symbol: String = if (good) "✅" else "⚠️") {
            mark.text = symbol
            mark.setTextColor(if (good) ok else warn)
            mark.scaleX = 0.3f
            mark.scaleY = 0.3f
            mark.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.e2e_title, peerName))
            .setView(android.widget.ScrollView(context).apply { addView(root) })
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.e2e_again, null)
            .create()

        fun run() {
            // Сбрасываем предыдущие шаги (повторная проверка).
            while (root.childCount > 2) root.removeViewAt(2)
            status.setText(R.string.e2e_checking)
            status.setTextColor(primary)
            shieldBox.findViewWithTag<View>("check")?.let { shieldBox.removeView(it) }
            shield.text = "🛡"
            ring.visibility = View.VISIBLE
            if (!pulse.isRunning) pulse.start()
            scope.launch {
                val self = runCatching { tox.publicKey.string() }.getOrDefault("")
                delay(350)
                val (m1, d1) = step(context.getString(R.string.e2e_step_keys))
                d1.text = context.getString(R.string.e2e_keys_detail, fingerprint(self), fingerprint(peerPk))
                delay(450)
                done(m1, self.length == 64 && peerPk.length == 64)

                val (m2, d2) = step(context.getString(R.string.e2e_step_channel))
                delay(400)
                d2.text = context.getString(
                    when (connection) {
                        ConnectionStatus.UDP -> R.string.e2e_channel_udp
                        ConnectionStatus.TCP -> R.string.e2e_channel_tcp
                        ConnectionStatus.None -> R.string.e2e_channel_offline
                    },
                )
                done(m2, connection != ConnectionStatus.None)

                val (m3, d3) = step(context.getString(R.string.e2e_step_live))
                d3.setText(R.string.e2e_live_sending)
                val rtt = if (connection != ConnectionStatus.None) ping(tox, peerPk) else null
                if (rtt != null) {
                    d3.text = context.getString(R.string.e2e_live_ok, rtt)
                    done(m3, true)
                } else {
                    d3.setText(if (connection == ConnectionStatus.None) R.string.e2e_live_offline else R.string.e2e_live_noreply)
                    done(m3, false)
                }

                val (m4, d4) = step(context.getString(R.string.e2e_step_code))
                d4.text = securityCode(self, peerPk)
                d4.typeface = Typeface.MONOSPACE
                d4.setTextIsSelectable(true)
                done(m4, true, "🔑")

                pulse.cancel()
                shield.scaleX = 1f
                shield.scaleY = 1f
                ring.visibility = View.INVISIBLE
                val verified = rtt != null
                shield.text = if (verified) "" else "🛡"
                if (verified) {
                    // Успех: рисующийся круг с галочкой и разлёт искр (как Success Check / Like Burst).
                    shieldBox.findViewWithTag<View>("check")?.let { shieldBox.removeView(it) }
                    val check = com.stoyko.smessenger.ui.SuccessCheckView(context).apply { tag = "check" }
                    shieldBox.addView(check, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
                    check.play()
                    shieldBox.postDelayed({ com.stoyko.smessenger.ui.Kinetics.burst(shieldBox) }, 450)
                } else {
                    shield.animate().rotationY(360f).setDuration(600).withEndAction { shield.rotationY = 0f }.start()
                }
                status.setText(if (verified) R.string.e2e_result_ok else R.string.e2e_result_partial)
                status.setTextColor(if (verified) ok else warn)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { run() }
            run()
        }
        dialog.setOnDismissListener { pulse.cancel() }
        dialog.show()
    }
}
