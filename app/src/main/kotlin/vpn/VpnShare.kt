// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R

/**
 * Серверы VPN, присланные в чате: ссылки vless/vmess/trojan/ss/hysteria2/socks5 в тексте
 * сообщения или файл TXT/JSON (список ссылок, подписка base64, конфиг Xray/sing-box,
 * список SOCKS5 «хост:порт»). Предлагаем добавить их во встроенный VPN или открыть
 * другим приложением (v2rayNG, Hiddify, NekoBox…).
 */
object VpnShare {
    private val TEXT_EXT = setOf("txt", "json", "conf", "cfg", "yaml", "yml", "list", "sub", "csv", "text", "")
    private const val MAX_SIZE = 3L * 1024 * 1024
    private val main = Handler(Looper.getMainLooper())

    /** Явные ссылки на серверы в обычном сообщении. */
    fun linksIn(text: String): List<String> =
        if (!text.contains("://")) emptyList() else runCatching { VpnLinks.extractLinks(text, strict = true) }.getOrDefault(emptyList())

    fun isConfigName(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        val ext = if (dot < 0) "" else name.substring(dot + 1).lowercase()
        return ext in TEXT_EXT
    }

    /**
     * Файл из чата: если это TXT/JSON с серверами - предложить VPN, иначе [fallback]
     * (обычное «Открыть с помощью»). Файл читается в фоне.
     */
    fun checkFile(ctx: Context, uri: Uri, name: String, fallback: () -> Unit) {
        if (!isConfigName(name)) {
            fallback()
            return
        }
        val app = ctx.applicationContext
        Thread({
            val links = runCatching {
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    val bytes = input.readBytes(MAX_SIZE)
                    if (bytes == null) emptyList() else VpnLinks.extractLinks(String(bytes, Charsets.UTF_8), name)
                }
            }.getOrDefault(emptyList())
            main.post {
                if (links.isEmpty()) runCatching { fallback() } else offer(ctx, links, other = fallback)
            }
        }, "vpn-share").start()
    }

    /** Не больше [max] байт; null - файл слишком большой для списка серверов. */
    private fun java.io.InputStream.readBytes(max: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Окно «Найдены серверы VPN»: добавить во встроенный VPN / открыть другим приложением.
     * [other] - своё действие для «Другое приложение» (для файла - «Открыть с помощью»).
     */
    fun offer(ctx: Context, found: List<String>, other: (() -> Unit)? = null) {
        // Только то, что встроенный VPN действительно сможет разобрать.
        val links = found.filter { runCatching { VpnLinks.parse(it) }.getOrNull() != null }
        if (links.isEmpty()) {
            other?.invoke()
            return
        }
        val names = links.take(6).map { l ->
            runCatching { VpnLinks.parse(l) }.getOrNull()?.let { "• ${it.name} (${it.protocol})" }
                ?: ("• " + l.substringBefore("://") + "://…")
        }
        val more = if (links.size > 6) "\n" + ctx.getString(R.string.vpn_share_more, links.size - 6) else ""
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle(ctx.getString(R.string.vpn_share_title, links.size))
                .setMessage(ctx.getString(R.string.vpn_share_text) + "\n\n" + names.joinToString("\n") + more)
                .setPositiveButton(R.string.vpn_share_builtin) { _, _ ->
                    VpnScreen.openWithLink(ctx, links.joinToString("\n"))
                }
                .setNeutralButton(R.string.vpn_share_other) { _, _ -> other?.invoke() ?: openOther(ctx, links) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Другое приложение: одна ссылка - «открыть» (vless:// и т. п. понимают v2rayNG, Hiddify), иначе - «поделиться» текстом. */
    private fun openOther(ctx: Context, links: List<String>) {
        val title = ctx.getString(R.string.vpn_share_other)
        if (links.size == 1 && start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(links[0])), title)) return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, links.joinToString("\n"))
        if (!start(ctx, send, title)) Toast.makeText(ctx, R.string.vpn_share_no_app, Toast.LENGTH_LONG).show()
    }

    /** Выбор приложения без самого мессенджера (он тоже принимает такие ссылки). */
    private fun start(ctx: Context, intent: Intent, title: String): Boolean = runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val all = pm.queryIntentActivities(intent, 0)
        val own = all.filter { it.activityInfo.packageName == ctx.packageName }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        if (all.size <= own.size) return@runCatching false
        val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (own.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
        ctx.startActivity(chooser)
        true
    }.getOrDefault(false)
}
