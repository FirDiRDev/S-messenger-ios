// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import com.stoyko.smessenger.R

/**
 * Обход блокировок для встроенного VPN (ядро Xray):
 *  - DNS через HTTPS (DoH): Cloudflare, Google, Quad9 или свой адрес - провайдер не видит и
 *    не подменяет запросы;
 *  - фрагментация TLS-приветствия: ClientHello режется на куски, и DPI не может прочитать
 *    SNI (имя сайта) - помогает, когда сервер блокируют по SNI;
 *  - для VLESS Reality «подделка SNI» уже встроена: в ссылке свой реальный домен (sni),
 *    IP, порт и публичный ключ (pbk) - трафик выглядит как обычный HTTPS к этому домену.
 */
object VpnTweaks {
    private const val PREFS = "vpn_tweaks"

    @Volatile var dns: String = "auto"
        private set

    @Volatile var fragment: Boolean = false
        private set

    val DNS_PRESETS = linkedMapOf(
        "auto" to listOf("1.1.1.1", "8.8.8.8"),
        "cloudflare" to listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"),
        "google" to listOf("https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query"),
        "quad9" to listOf("https://9.9.9.9/dns-query"),
        // AdGuard: тот же DoH, но реклама и трекеры отсекаются ещё на этапе DNS.
        "adguard" to listOf("https://94.140.14.14/dns-query", "https://94.140.15.15/dns-query"),
    )

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        dns = p.getString("dns", "auto") ?: "auto"
        fragment = p.getBoolean("fragment", false)
    }

    private fun save(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString("dns", dns).putBoolean("fragment", fragment).apply()

    /** Сменить DNS (например, «adguard» после импорта YAML с блокировкой рекламы). */
    fun setDns(ctx: Context, key: String) {
        load(ctx)
        dns = key
        save(ctx)
    }

    fun dnsServers(): JSONArray = JSONArray(
        DNS_PRESETS[dns] ?: if (dns.startsWith("https://") || dns.matches(Regex("[0-9a-fA-F.:]+"))) {
            listOf(dns, "1.1.1.1")
        } else {
            DNS_PRESETS.getValue("auto")
        },
    )

    /** Дополнительный исходящий для фрагментации TLS (через него идёт основной прокси). */
    fun fragmentOutbound(): JSONObject = JSONObject()
        .put("tag", "fragment")
        .put("protocol", "freedom")
        .put(
            "settings",
            JSONObject().put(
                "fragment",
                JSONObject().put("packets", "tlshello").put("length", "100-200").put("interval", "10-20"),
            ),
        )
        .put("streamSettings", JSONObject().put("sockopt", JSONObject().put("tcpNoDelay", true)))

    /** Окно «Обход блокировок»: DNS и фрагментация. */
    fun showDialog(ctx: Context) {
        load(ctx)
        val dnsNames = arrayOf<CharSequence>(
            ctx.getString(R.string.dns_auto),
            "Cloudflare (DoH)",
            "Google (DoH)",
            "Quad9 (DoH)",
            com.stoyko.smessenger.ai.t("AdGuard (DoH, без рекламы)", "AdGuard (DoH, ad-free)"),
            ctx.getString(R.string.dns_custom),
        )
        val keys = listOf("auto", "cloudflare", "google", "quad9", "adguard")
        val items = arrayOf<CharSequence>(
            ctx.getString(R.string.dns_title) + ": " + (keys.indexOf(dns).takeIf { it >= 0 }?.let { dnsNames[it] } ?: dns),
            (if (fragment) "✅ " else "⬜ ") + ctx.getString(R.string.tls_fragment),
            ctx.getString(R.string.reality_help_title),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.dpi_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(ctx)
                        .setTitle(R.string.dns_title)
                        .setItems(dnsNames) { _, i ->
                            if (i < keys.size) {
                                dns = keys[i]
                                save(ctx)
                                applied(ctx)
                            } else {
                                val input = android.widget.EditText(ctx).apply {
                                    hint = "https://dns.example/dns-query"
                                    setText(dns.takeIf { it !in keys }.orEmpty())
                                }
                                AlertDialog.Builder(ctx)
                                    .setTitle(R.string.dns_custom)
                                    .setView(input)
                                    .setPositiveButton(android.R.string.ok) { _, _ ->
                                        val v = input.text.toString().trim()
                                        if (v.isNotEmpty()) {
                                            dns = v
                                            save(ctx)
                                            applied(ctx)
                                        }
                                    }
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show()
                            }
                        }
                        .show()
                    1 -> {
                        fragment = !fragment
                        save(ctx)
                        applied(ctx)
                    }
                    2 -> AlertDialog.Builder(ctx)
                        .setTitle(R.string.reality_help_title)
                        .setMessage(R.string.reality_help_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applied(ctx: Context) =
        android.widget.Toast.makeText(ctx, R.string.vpn_tweaks_applied, android.widget.Toast.LENGTH_LONG).show()
}
