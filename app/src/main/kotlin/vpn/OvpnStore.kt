// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.stoyko.smessenger.ai.t
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Профили OpenVPN (.ovpn). Своего движка OpenVPN во встроенном VPN нет (нужна отдельная
 * нативная библиотека), поэтому профили хранятся здесь, проверяются (TCP-пинг до сервера) и
 * одним нажатием открываются в приложении OpenVPN (OpenVPN Connect или OpenVPN for Android).
 */
object OvpnStore {
    data class Profile(val id: String, val name: String, val text: String) {
        val remote: Triple<String, Int, String>? get() = parseRemote(text)
        /** Логин и пароль не вшиты - приложение OpenVPN спросит их при подключении. */
        val needsLogin: Boolean
            get() = Regex("(?m)^\\s*auth-user-pass\\s*$").containsMatchIn(text) && !text.contains("<auth-user-pass>")
    }

    private const val KEY = "ovpn_profiles"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { Thread(it, "ovpn-ping").apply { isDaemon = true } }
    private val pings = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun looksLike(text: String): Boolean {
        val t = text.lowercase()
        return Regex("(?m)^\\s*remote\\s+\\S+").containsMatchIn(t) &&
            (Regex("(?m)^\\s*client\\s*$").containsMatchIn(t) || "<ca>" in t || Regex("(?m)^\\s*dev\\s+tun").containsMatchIn(t))
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("vpn", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Profile> = runCatching {
        val a = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Profile(it.optString("id"), it.optString("name"), it.optString("text")) }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, l: List<Profile>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
        prefs(ctx).edit().putString(KEY, a.toString()).apply()
    }

    /** Добавить профиль; null - такой уже есть. */
    fun add(ctx: Context, text: String, fileName: String = ""): Profile? {
        val cur = list(ctx)
        val norm = text.trim()
        if (cur.any { it.text.trim() == norm }) return null
        val p = Profile(UUID.randomUUID().toString(), nameOf(norm, fileName), norm)
        save(ctx, cur + p)
        return p
    }

    fun remove(ctx: Context, id: String) = save(ctx, list(ctx).filter { it.id != id })

    private fun nameOf(text: String, fileName: String): String {
        Regex("(?m)^#\\s*OVPN_FRIENDLY_PROFILE_NAME\\s*=\\s*(.+)$").findAll(text)
            .map { it.groupValues[1].trim() }.firstOrNull { it.any { c -> c.isLetterOrDigit() } }
            ?.let { return it }
        val r = parseRemote(text)
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim().takeIf { it.isNotBlank() && it.any { c -> c.isLetterOrDigit() } }
        return listOfNotNull(base, r?.let { "${it.first}:${it.second}" }).joinToString(" · ").ifBlank { "OpenVPN" }
    }

    /** Первый remote: хост, порт, протокол. */
    fun parseRemote(text: String): Triple<String, Int, String>? {
        val proto = Regex("(?m)^\\s*proto\\s+(\\S+)").find(text)?.groupValues?.get(1)?.lowercase() ?: "udp"
        val m = Regex("(?m)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?(?:\\s+(\\S+))?").find(text) ?: return null
        val port = m.groupValues[2].toIntOrNull() ?: 1194
        val pr = m.groupValues[3].ifBlank { proto }.lowercase()
        return Triple(m.groupValues[1], port, if (pr.startsWith("tcp")) "tcp" else "udp")
    }

    fun ping(id: String): Long? = pings[id]

    /** TCP-пинг до сервера (для UDP-профилей проверяется только, что адрес находится). */
    fun pingAll(ctx: Context, done: () -> Unit) {
        val l = list(ctx)
        if (l.isEmpty()) return
        val left = java.util.concurrent.atomic.AtomicInteger(l.size)
        l.forEach { p ->
            pool.execute {
                val r = p.remote
                val ms = runCatching {
                    if (r == null) return@runCatching -1L
                    val addr = java.net.InetAddress.getByName(r.first)
                    if (r.third != "tcp") return@runCatching 0L
                    val t0 = System.nanoTime()
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(addr, r.second), 4_000) }
                    ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
                }.getOrDefault(-1L)
                pings[p.id] = ms
                left.decrementAndGet()
                main.post { done() }
            }
        }
    }

    /** Открыть в приложении OpenVPN; нет приложения - предложить поставить. */
    fun open(ctx: Context, p: Profile) {
        val dir = File(ctx.filesDir, "ovpn").apply { mkdirs() }
        val safe = p.name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "").trim().ifBlank { "profile" }.take(40)
        val f = File(dir, "$safe.ovpn")
        f.writeText(p.text)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-openvpn-profile")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (pkg in listOf("net.openvpn.openvpn", "de.blinkt.openvpn")) {
            val i = Intent(view).setPackage(pkg)
            if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return
        }
        if (view.resolveActivity(ctx.packageManager) != null) {
            runCatching { ctx.startActivity(Intent.createChooser(view, t("Открыть в OpenVPN", "Open in OpenVPN")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { askInstall(ctx) }
        } else {
            askInstall(ctx)
        }
    }

    fun askInstall(ctx: Context) {
        com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle("OpenVPN")
                .setMessage(
                    t(
                        "Для профилей .ovpn нужно приложение OpenVPN Connect или OpenVPN for Android. Установить?",
                        "OpenVPN profiles need the OpenVPN Connect or OpenVPN for Android app. Install it?",
                    ),
                )
                .setPositiveButton("OpenVPN Connect") { _, _ -> market(ctx, "net.openvpn.openvpn") }
                .setNeutralButton("OpenVPN for Android") { _, _ -> market(ctx, "de.blinkt.openvpn") }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun market(ctx: Context, pkg: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toast.makeText(ctx, com.stoyko.smessenger.R.string.no_app_to_open, Toast.LENGTH_LONG).show() }
            }
    }
}
