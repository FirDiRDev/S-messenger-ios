// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context

/** Своё имя для контакта: собеседник его не видит и не может перезаписать. */
object ContactAlias {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("contact_alias", Context.MODE_PRIVATE)

    fun get(ctx: Context, pk: String): String? = prefs(ctx).getString(pk, null)?.takeIf { it.isNotBlank() }
    fun has(ctx: Context, pk: String) = get(ctx, pk) != null
    fun set(ctx: Context, pk: String, name: String?) {
        if (name.isNullOrBlank()) prefs(ctx).edit().remove(pk).apply() else prefs(ctx).edit().putString(pk, name.trim()).apply()
    }
}
