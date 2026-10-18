// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Память: что сколько занимает, очистка кэша и автоудаление старых файлов из чатов.
 * Сама переписка (текст) не удаляется никогда.
 */
object StorageCleaner {
    private const val TAG = "StorageCleaner"
    private const val PREFS = "storage"

    /** Через сколько дней удалять файлы из чатов: 0 - никогда. */
    fun keepDays(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("keep_days", 0)
    fun setKeepDays(ctx: Context, days: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("keep_days", days).apply()

    /** Предел места для файлов и кэша в МБ: 0 - без ограничения. При превышении удаляются самые старые. */
    fun limitMb(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("limit_mb", 0)
    fun setLimitMb(ctx: Context, mb: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("limit_mb", mb).apply()

    /** Последняя автоочистка (чтобы не сканировать диск чаще раза в несколько часов - экономия батареи). */
    private fun lastAuto(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_auto", 0L)
    private fun setLastAuto(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_auto", System.currentTimeMillis()).apply()

    fun received(ctx: Context) = File(ctx.filesDir, "ft")
    fun sentCopies(ctx: Context) = File(ctx.filesDir, "outgoing")
    fun stories(ctx: Context) = File(ctx.filesDir, "stories")
    fun web(ctx: Context) = File(ctx.dataDir(), "app_webview")

    private fun Context.dataDir(): File = filesDir.parentFile ?: filesDir

    fun size(f: File): Long = runCatching {
        if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    fun cacheSize(ctx: Context) = size(ctx.cacheDir) + (ctx.externalCacheDir?.let { size(it) } ?: 0L)

    /** Кэш: превью, временные файлы, кэш встроенного браузера (YouTube). */
    fun clearCache(ctx: Context) {
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        // Кэш страниц браузера; вход в аккаунты (куки) сохраняется.
        File(web(ctx), "Default/HTTP Cache").deleteRecursively()
        File(web(ctx), "Default/Code Cache").deleteRecursively()
        File(web(ctx), "Default/GPUCache").deleteRecursively()
    }

    /**
     * Копии отправленных фото и видео (оригиналы остаются в галерее телефона). Свежие (меньше суток)
     * не трогаем - они могут ещё ждать отправки собеседнику, который не в сети.
     */
    fun clearSentCopies(ctx: Context, olderThanMs: Long = 24 * 3600_000L): Long =
        deleteOlder(sentCopies(ctx), System.currentTimeMillis() - olderThanMs, pendingPaths(ctx))

    /**
     * Файлы, которые ещё отправляются (собеседник не в сети, передача на паузе). Адреса бывают
     * и file://, и content:// приложения - раньше вторые не узнавались, и очистка удаляла копию,
     * а собеседник получал пустой файл.
     */
    private fun pendingPaths(ctx: Context): Set<String> = runCatching {
        (ctx.applicationContext as com.stoyko.smessenger.App).component.fileTransferRepository().pendingOutgoing()
            .mapNotNull { d ->
                runCatching {
                    val u = android.net.Uri.parse(d)
                    com.stoyko.smessenger.ui.chat.ForwardFiles.ownFile(ctx, u)?.absolutePath ?: File(u.path ?: d).absolutePath
                }.getOrNull()
            }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Ручная очистка копий отправленного (галочка в файловом менеджере и кнопка в настройках):
     * удаляются ВСЕ копии - фото, видео, файлы, голосовые, кружки, истории, - кроме тех, что
     * прямо сейчас ещё отправляются (раньше удалялись только копии старше суток, и очистка
     * выглядела так, будто ничего не делает).
     */
    fun clearSentCopiesNow(ctx: Context): Long {
        val dir = sentCopies(ctx)
        if (!dir.exists()) return 0L
        val keep = pendingPaths(ctx)
        val fresh = System.currentTimeMillis() - 2 * 60_000L
        var freed = 0L
        dir.walkBottomUp().forEach { f ->
            if (f.isFile && f.absolutePath !in keep && f.lastModified() < fresh && !f.name.endsWith(".wip")) {
                val len = f.length()
                if (f.delete()) freed += len
            } else if (f.isDirectory && f != dir && f.listFiles().isNullOrEmpty()) {
                f.delete()
            }
        }
        return freed
    }

    /** Только кэш встроенного браузера и YouTube (вход в аккаунты сохраняется). */
    fun clearBrowserCache(ctx: Context) {
        File(web(ctx), "Default/HTTP Cache").deleteRecursively()
        File(web(ctx), "Default/Code Cache").deleteRecursively()
        File(web(ctx), "Default/GPUCache").deleteRecursively()
        File(web(ctx), "Default/Service Worker/CacheStorage").deleteRecursively()
        File(ctx.cacheDir, "WebView").deleteRecursively()
        File(ctx.cacheDir, "org.chromium.android_webview").deleteRecursively()
    }

    /** Полная очистка браузера: кэш, куки, история, данные сайтов (нужно будет войти заново). */
    fun clearBrowserAll(ctx: Context) {
        clearBrowserCache(ctx)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                android.webkit.CookieManager.getInstance().flush()
                android.webkit.WebStorage.getInstance().deleteAllData()
                android.webkit.WebViewDatabase.getInstance(ctx).clearFormData()
                @Suppress("DEPRECATION")
                android.webkit.WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword()
            }
        }
    }

    /**
     * Автоочистка по настройке (при запуске фоновой службы и раз в несколько часов):
     * по возрасту файлов (1 день … месяц) и по пределу занятого места (100 МБ … 5 ГБ).
     */
    fun autoClean(ctx: Context, force: Boolean = false) {
        try {
            if (!force && System.currentTimeMillis() - lastAuto(ctx) < 3 * 3600_000L) return
            setLastAuto(ctx)
            val days = keepDays(ctx)
            // Копии отправленного старше недели не нужны в любом случае.
            clearSentCopies(ctx, 7 * 24 * 3600_000L)
            if (days > 0) {
                val before = System.currentTimeMillis() - days * 24 * 3600_000L
                val freed = deleteOlder(received(ctx), before) + deleteOlder(sentCopies(ctx), before, pendingPaths(ctx))
                if (freed > 0) Log.i(TAG, "auto clean: freed $freed bytes")
            }
            enforceLimit(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "auto clean: $e")
        }
    }

    /** Если файлы и кэш заняли больше предела - сначала кэш, потом самые старые файлы из чатов. */
    fun enforceLimit(ctx: Context) {
        val mb = limitMb(ctx)
        if (mb <= 0) return
        val limit = mb.toLong() * 1024 * 1024
        fun total() = size(received(ctx)) + size(sentCopies(ctx)) + size(stories(ctx)) + cacheSize(ctx) +
            size(File(web(ctx), "Default/HTTP Cache"))
        var used = total()
        if (used <= limit) return
        clearCache(ctx)
        used = total()
        if (used <= limit) return
        val files = (received(ctx).walkTopDown() + sentCopies(ctx).walkTopDown() + stories(ctx).walkTopDown())
            .filter { it.isFile && !it.name.endsWith(".wip") }
            .sortedBy { it.lastModified() }
            .toList()
        // Свежее (последний час) не трогаем: файл может ещё отправляться или только что пришёл.
        val fresh = System.currentTimeMillis() - 3600_000L
        for (f in files) {
            if (used <= limit * 9 / 10) break
            if (f.lastModified() > fresh) continue
            val len = f.length()
            if (f.delete()) used -= len
        }
        Log.i(TAG, "limit clean: now $used of $limit")
    }

    /** Удалить файлы старше [before]; возвращает освобождённые байты. Незаконченные (.wip) не трогаем. */
    private fun deleteOlder(dir: File, before: Long, keep: Set<String> = emptySet()): Long {
        if (!dir.exists()) return 0L
        var freed = 0L
        dir.walkBottomUp().forEach { f ->
            if (f.isFile && f.lastModified() < before && !f.name.endsWith(".wip") && f.absolutePath !in keep) {
                val len = f.length()
                if (f.delete()) freed += len
            } else if (f.isDirectory && f != dir && f.listFiles().isNullOrEmpty()) {
                f.delete()
            }
        }
        return freed
    }
}
