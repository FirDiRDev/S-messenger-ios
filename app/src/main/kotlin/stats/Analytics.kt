// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stats

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.stoyko.smessenger.App
import com.stoyko.smessenger.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Обезличенная статистика для разработчика: сколько устройств поставили приложение, сколько
 * создали профиль, сколько сейчас в сети и активны за день, какие версии и типы телефонов.
 *
 * Никаких ID, ключей, имён и адресов не отправляется - только «+1» к общим счётчикам
 * (как счётчик посещений сайта). Каждое устройство добавляет единицу:
 *  - «установка» и «профиль создан» - один раз;
 *  - «версия N» и «тип телефона» - один раз на версию;
 *  - «активен сегодня» - раз в сутки;
 *  - «в сети» - раз в 10 минут, пока приложение работает (счётчик своего 10-минутного окна).
 *
 * Счётчики - бесплатный сервис Abacus (запасной - CountAPI). Скачивания APK - из релизов GitHub.
 */
object Analytics {
    private const val TAG = "Analytics"
    private const val NS = "smessenger-firedie"
    private const val ABACUS = "https://abacus.jasoncameron.dev"
    private const val COUNTAPI = "https://countapi.mileshilliard.com/api/v1"
    const val WINDOW_MIN = 10L

    private val pool = Executors.newSingleThreadScheduledExecutor { Thread(it, "analytics").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var started = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("analytics", Context.MODE_PRIVATE)

    private fun day(offsetDays: Int = 0): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Moscow") }
        return f.format(Date(System.currentTimeMillis() - offsetDays * 86_400_000L))
    }

    fun windowNow(): Long = System.currentTimeMillis() / 60_000L / WINDOW_MIN

    fun start(app: App) {
        if (started) return
        started = true
        pool.scheduleWithFixedDelay({ runCatching { tick(app) }.onFailure { Log.w(TAG, "tick: $it") } }, 20, 60, TimeUnit.SECONDS)
    }

    private fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.isConnected == true
    }.getOrDefault(true)

    /** Раз в минуту: отправить те «+1», что ещё не ушли. Неудача - попробуем через минуту. */
    private fun tick(app: App) {
        if (!online(app)) return
        val p = prefs(app)
        fun once(flag: String, key: String) {
            if (p.getBoolean(flag, false)) return
            if (hit(key)) p.edit().putBoolean(flag, true).apply()
        }
        once("inst", "inst")
        val ver = BuildConfig.VERSION_CODE
        once("v_$ver", "v-$ver")
        once("k_$ver", "k-" + runCatching { com.stoyko.smessenger.ui.DeviceProfile.get(app).kind.name.lowercase() }.getOrDefault("phone"))
        val profile = runCatching { app.component.tox().started }.getOrDefault(false)
        if (profile) once("reg", "reg")
        val d = day()
        if (p.getString("day", "") != d && hit("d-$d")) p.edit().putString("day", d).apply()
        val w = windowNow()
        if (p.getLong("win", -1L) != w && hit("on-$w")) p.edit().putLong("win", w).apply()
    }

    // ---------------- сеть ----------------

    private fun http(url: String, timeout: Int = 8000): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "S-messenger/" + BuildConfig.VERSION_NAME)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
            code to body
        } finally {
            c.disconnect()
        }
    }

    private fun numberIn(body: String): Long? = runCatching {
        val o = JSONObject(body)
        when (val v = o.opt("value")) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }.getOrNull()

    /** +1 к счётчику (в оба сервиса; засчитано, если принял хотя бы основной или запасной). */
    private fun hit(key: String): Boolean {
        val a = runCatching { http("$ABACUS/hit/$NS/$key").first in 200..299 }.getOrDefault(false)
        val b = runCatching { http("$COUNTAPI/hit/$NS-$key").first in 200..299 }.getOrDefault(false)
        return a || b
    }

    /** Значение счётчика; нет такого - 0; сервисы недоступны - null. */
    fun get(key: String): Long? {
        runCatching {
            val (code, body) = http("$ABACUS/get/$NS/$key")
            if (code == 404) return 0L
            if (code in 200..299) numberIn(body)?.let { return it }
        }
        runCatching {
            val (code, body) = http("$COUNTAPI/get/$NS-$key")
            if (code == 404) return 0L
            if (code in 200..299) numberIn(body)?.let { return it }
        }
        return null
    }

    class Snapshot(
        val installs: Long?,
        val registered: Long?,
        val onlineNow: Long?,
        val todayActive: Long?,
        val week: List<Pair<String, Long?>>,
        val versions: List<Pair<Int, Long>>,
        val kinds: List<Pair<String, Long>>,
        val downloads: Long?,
        val latestDownloads: Long?,
        val latestTag: String?,
        val at: Long = System.currentTimeMillis(),
    ) {
        val offline: Long? get() = if (installs != null && onlineNow != null) (installs - onlineNow).coerceAtLeast(0) else null
    }

    /** В сети сейчас: устройства, отметившиеся в текущем или прошлом 10-минутном окне. */
    fun onlineNow(): Long? {
        val w = windowNow()
        val cur = get("on-$w")
        val prev = get("on-${w - 1}")
        if (cur == null && prev == null) return null
        return maxOf(cur ?: 0, prev ?: 0)
    }

    /** Всё сразу (долго - только не на главном потоке). */
    fun load(): Snapshot {
        val installs = get("inst")
        val reg = get("reg")
        val on = onlineNow()
        val week = (6 downTo 0).map { off ->
            val d = day(off)
            d to get("d-$d")
        }
        val cur = BuildConfig.VERSION_CODE
        val versions = (cur downTo (cur - 5).coerceAtLeast(1)).mapNotNull { v -> get("v-$v")?.takeIf { it > 0 }?.let { v to it } }
        val kinds = listOf("budget", "phone", "flagship", "flip", "fold", "tablet").mapNotNull { k -> get("k-$k")?.takeIf { it > 0 }?.let { k to it } }
        var total: Long? = null
        var latest: Long? = null
        var tag: String? = null
        runCatching {
            val (code, body) = http(com.stoyko.smessenger.Obf.x_ghApi() + com.stoyko.smessenger.Obf.x_repo() + "/releases?per_page=100", 12000)
            if (code in 200..299) {
                val a = JSONArray(body)
                var sum = 0L
                for (i in 0 until a.length()) {
                    val r = a.optJSONObject(i) ?: continue
                    val assets = r.optJSONArray("assets") ?: continue
                    var rs = 0L
                    for (j in 0 until assets.length()) {
                        val asset = assets.optJSONObject(j) ?: continue
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) rs += asset.optLong("download_count")
                    }
                    sum += rs
                    if (i == 0) {
                        latest = rs
                        tag = r.optString("tag_name").ifBlank { null }
                    }
                }
                total = sum
            }
        }.onFailure { Log.w(TAG, "github: $it") }
        return Snapshot(installs, reg, on, week.last().second, week, versions, kinds, total, latest, tag)
    }
}
