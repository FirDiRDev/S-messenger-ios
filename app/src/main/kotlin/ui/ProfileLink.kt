// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.stoyko.smessenger.R

/**
 * Ссылка на профиль, которую можно отправить куда угодно (в любой мессенджер, соцсеть, почту):
 *
 *  - `https://<сервер>/u/<ID>` - обычная ссылка, кликается везде. Страница сразу открывает
 *    S-messenger (на Android - через intent://), а если приложения нет - предлагает скачать;
 *  - `smessenger:add/<ID>` - прямая ссылка в приложение.
 *
 * По ссылке S-messenger открывается и сам отправляет заявку в друзья (уже друзья - открывается чат).
 */
object ProfileLink {
    private val HEX_ID = Regex("[0-9A-Fa-f]{76}")

    private fun server(): String = com.stoyko.smessenger.chat.CoinShop.serverUrl.trimEnd('/')

    fun webLink(id: String, name: String = ""): String =
        server() + "/u/" + id.uppercase() + if (name.isBlank()) "" else "?n=" + java.net.URLEncoder.encode(name.take(40), "UTF-8")

    fun appLink(id: String): String = "smessenger:add/" + id.uppercase()

    /** ID из ссылки на профиль (или null - это не ссылка на профиль). */
    fun parse(data: String?): String? {
        val s = data?.trim() ?: return null
        val isProfile = s.startsWith("smessenger:add/", true) || s.startsWith("smessenger://add/", true) ||
            (s.startsWith("https://", true) && s.contains("/u/"))
        if (!isProfile) return null
        val tail = if (s.contains("/u/")) s.substringAfter("/u/") else s.substringAfter("add/")
        return HEX_ID.find(tail)?.value?.takeIf { tail.startsWith(it, true) }?.uppercase()
    }

    fun shareText(ctx: Context, id: String, name: String): String =
        ctx.getString(R.string.profile_link_share_text, webLink(id, name), appLink(id))

    fun share(ctx: Context, id: String, name: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText(ctx, id, name))
        }
        runCatching { ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.profile_link_share))) }
    }

    fun copy(ctx: Context, id: String, name: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("S-messenger", webLink(id, name)))
        Toast.makeText(ctx, R.string.profile_link_copied, Toast.LENGTH_SHORT).show()
    }
}
