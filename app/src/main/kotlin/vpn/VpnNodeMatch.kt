// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R

/**
 * «Подобрать VPN для узлов»: проверяет ВСЕ серверы (раньше - только 5 самых быстрых):
 *  1) реальный пинг каждого сервера (параллельно) - мёртвые сразу отсеиваются;
 *  2) через каждый живой сервер отдельным пробным подключением проверяет доступ к каталогу
 *     узлов сети - НЕ включая сервер: работающий VPN и сеть мессенджера не прерываются.
 * Лучший сервер становится выбранным по умолчанию, но не подключается - приходит уведомление,
 * включить VPN человек решает сам. Подбор идёт в фоне с полосой прогресса в шторке.
 */
object VpnNodeMatch {
    private const val VPN_WAIT_MS = 12_000L
    private const val NET_WAIT_MS = 20_000L

    /** Подключён ли сам мессенджер к сети (обновляется событием ядра). */
    @Volatile
    var toxOnline = false

    @Volatile
    var running = false
        private set

    private class Result(val profile: VpnProfile, val ms: Long)

    private val main = Handler(Looper.getMainLooper())

    /** Проверки перед запуском; null - можно начинать. */
    private fun precheck(context: Context): Int? {
        if (running) return R.string.vpn_match_running
        if (VpnStore(context).profiles().isEmpty()) return R.string.vpn_match_need_ping
        if (SmVpnService.prepareIntent(context) != null) return R.string.vpn_match_need_permission
        return null
    }

    /** Подбор в фоне, без окна (только шторка). */
    fun startInBackground(context: Context): Boolean {
        precheck(context)?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return false
        }
        engine(context.applicationContext, AtomicBoolean(false), { _, _, _ -> }, { })
        Toast.makeText(context, R.string.vpn_match_bg_started, Toast.LENGTH_LONG).show()
        return true
    }

    /** Подбор с окном (живой журнал); кнопка «В фоне» оставляет только шторку. */
    fun start(context: Context, onFinished: () -> Unit) {
        precheck(context)?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            return
        }
        val d = context.resources.displayMetrics.density
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * d).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val progress = com.stoyko.smessenger.ui.ElasticProgressView(context).apply { this.progress = 0f }
        val status = TextView(context).apply { textSize = 14f }
        val log = TextView(context).apply {
            textSize = 13f
            setPadding(0, (8 * d).toInt(), 0, 0)
        }
        box.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (10 * d).toInt()).apply { bottomMargin = (10 * d).toInt() })
        box.addView(status)
        box.addView(log)
        val userCancel = AtomicBoolean(false)
        var attached = true
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.vpn_match_title)
            .setView(android.widget.ScrollView(context).apply { addView(box) })
            .setNegativeButton(R.string.bg_jobs_stop) { _, _ ->
                // Окно закрывается сразу, подбор останавливается в фоне (главный поток не ждёт).
                attached = false
                userCancel.set(true)
                BgJobs.cancel(context, BgJobs.NODES)
            }
            .setNeutralButton(R.string.dpi_bg_button) { _, _ -> attached = false }
            .setCancelable(false)
            .show()
        engine(context.applicationContext, userCancel, { fraction, text, line ->
            if (!attached) return@engine
            progress.progress = fraction
            status.text = text
            if (line != null) log.append(line + "\n")
        }) { summary ->
            if (attached) {
                progress.progress = 1f
                status.text = summary
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setText(R.string.close)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener { runCatching { dialog.dismiss() } }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility = android.view.View.GONE
            }
            onFinished()
        }
    }

    /**
     * Дождаться задач, но не дольше [deadlineMs] и с проверкой отмены каждые 150 мс (раньше
     * ожидание шло по очереди до 40-60 с на КАЖДЫЙ сервер, и после «Остановить» подбор
     * продолжал висеть). Возвращает результаты уже закончившихся задач.
     */
    private fun <T> awaitAll(futures: List<java.util.concurrent.Future<T>>, deadlineMs: Long, stopped: () -> Boolean): List<T> {
        val until = android.os.SystemClock.elapsedRealtime() + deadlineMs
        while (!stopped() && futures.any { !it.isDone } && android.os.SystemClock.elapsedRealtime() < until) {
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                break
            }
        }
        val out = ArrayList<T>()
        for (f in futures) {
            if (f.isDone && !f.isCancelled) runCatching { f.get() }.onSuccess { out.add(it) }
        }
        return out
    }

    private fun engine(
        app: Context,
        userCancel: AtomicBoolean,
        progress: (Float, String, String?) -> Unit,
        done: (String) -> Unit,
    ) {
        running = true
        val store = VpnStore(app)
        val title = app.getString(R.string.bg_nodes_title)
        val previous = store.selectedId
        fun ui(f: Float, text: String, line: String?) = main.post { progress(f, text, line) }
        // Пулы создаются заранее: «Остановить» снимает очередь проверок сразу (shutdownNow).
        val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "vpn-match-ping").apply { isDaemon = true } }
        val pool2 = Executors.newFixedThreadPool(4) { r -> Thread(r, "vpn-match-nodes").apply { isDaemon = true } }
        val stop = BgJobs.begin(BgJobs.NODES) {
            pool.shutdownNow()
            pool2.shutdownNow()
        }
        fun stopped() = stop.get() || userCancel.get()

        Thread({
            var summary = app.getString(R.string.vpn_match_cancelled)
            try {
                val all = store.profiles()
                val results = ArrayList<Result>()
                // 1) Реальный пинг всех серверов (по 6 одновременно).
                val pinged = java.util.concurrent.atomic.AtomicInteger(0)
                val futures = all.map { p ->
                    pool.submit<Pair<VpnProfile, Long>> {
                        if (stopped()) return@submit p to 0L
                        val ms = XrayCore.realPing(app, p.link)
                        if (stopped()) return@submit p to 0L
                        val n = pinged.incrementAndGet()
                        val text = app.getString(R.string.vpn_match_stage_ping, n, all.size)
                        BgJobs.progress(app, BgJobs.NODES, title, text, n, all.size * 2, stop)
                        ui(n * 0.3f / all.size.coerceAtLeast(1), text, null)
                        p to ms
                    }
                }
                val pings = awaitAll(futures, 40_000L + (all.size / 6 + 1) * 15_000L) { stopped() }
                pool.shutdownNow()
                // Сохраняем только реально проверенные (0 - «не проверяли», такие не трогаем).
                val checked = pings.filter { it.second != 0L }.associate { it.first.id to it.second }
                if (checked.isNotEmpty()) {
                    runCatching { store.saveProfiles(store.profiles().map { p -> checked[p.id]?.let { p.withPing(it) } ?: p }) }
                }
                val alive = pings.filter { it.second > 0 }.sortedBy { it.second }.map { it.first }
                if (!stopped()) {
                    ui(0.3f, app.getString(R.string.vpn_match_alive, alive.size, all.size), app.getString(R.string.vpn_match_alive, alive.size, all.size))

                    // 2) Проверка «для узлов» БЕЗ переключения: VPN и мессенджер не трогаем (раньше каждый
                    //    сервер включался по очереди и мессенджер на это время отключался от сети).
                    //    Через каждый живой сервер отдельным пробным подключением открываем каталог узлов
                    //    сети и проверочный адрес - сервер, который быстрее всех пропускает оба, лучший для узлов.
                    val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
                    val tests = alive.map { p ->
                        pool2.submit<Result?> {
                            if (stopped()) return@submit null
                            val ms = nodeDelay(app, p)
                            if (stopped()) return@submit null
                            val n = doneCount.incrementAndGet()
                            val text = app.getString(R.string.vpn_match_trying, n, alive.size, p.name)
                            BgJobs.progress(app, BgJobs.NODES, title, text, all.size + n * all.size / alive.size.coerceAtLeast(1), all.size * 2, stop)
                            val line = if (ms > 0) app.getString(R.string.vpn_match_ok, p.name, ms / 1000.0) else app.getString(R.string.vpn_match_fail, p.name)
                            ui(0.3f + 0.7f * n / alive.size.coerceAtLeast(1), text, line)
                            if (ms > 0) Result(p, ms) else null
                        }
                    }
                    awaitAll(tests, 60_000L + (alive.size / 4 + 1) * 30_000L) { stopped() }.forEach { r -> if (r != null) results += r }
                }
                pool2.shutdownNow()
                val best = results.minByOrNull { it.ms }
                if (best != null) {
                    // Лучший сервер - по умолчанию, но НЕ подключаем: человек сам решит, когда включить.
                    store.selectedId = best.profile.id
                    store.routeTox = true
                    store.mode = VpnStore.MODE_SERVER
                    notifyBest(app, best.profile.name, best.ms)
                } else if (previous.isNotEmpty()) {
                    store.selectedId = previous
                }
                summary = when {
                    best != null -> app.getString(R.string.vpn_match_best, best.profile.name, best.ms / 1000.0) +
                        "\n" + app.getString(R.string.vpn_match_checked, alive.size, all.size, results.size)
                    stopped() -> app.getString(R.string.vpn_match_cancelled)
                    else -> app.getString(R.string.vpn_match_none)
                }
            } catch (e: Throwable) {
                android.util.Log.w("VpnNodeMatch", "match: $e")
            } finally {
                pool.shutdownNow()
                pool2.shutdownNow()
                // Сначала - «не идёт», чтобы кнопка сразу могла запустить подбор заново.
                running = false
                BgJobs.finish(app, BgJobs.NODES, app.getString(R.string.vpn_match_title), summary, stop)
                main.post { done(summary) }
            }
        }, "vpn-node-match").apply { isDaemon = true }.start()
    }

    /** Каталог узлов сети мессенджера: если сервер его пропускает - узлы через него доступны. */
    private const val NODES_URL = "https://nodes.tox.chat/json"

    /**
     * Задержка «для узлов» через сервер [p], мс (каталог узлов + проверочный адрес, берётся
     * худшее из двух); -1 - не пропускает. Отдельное пробное подключение: работающий VPN и сеть
     * мессенджера не прерываются.
     */
    private fun nodeDelay(context: Context, p: VpnProfile): Long {
        val a = runCatching {
            XrayCore.init(context)
            libv2ray.Libv2ray.measureOutboundDelay(VpnLinks.pingConfig(p.link), NODES_URL)
        }.getOrDefault(-1L)
        if (a <= 0) return -1
        val b = XrayCore.realPing(context, p.link)
        if (b < 0) return -1
        return maxOf(a, b)
    }

    /** Уведомление: найден лучший сервер для узлов, он выбран, подключиться - по желанию. */
    private fun notifyBest(app: Context, name: String, ms: Long) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "vpn_match"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, app.getString(R.string.vpn_match_title), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                android.app.PendingIntent.getActivity(app, 7311, it, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val n = androidx.core.app.NotificationCompat.Builder(app, ch)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle(app.getString(R.string.vpn_match_found_title))
                .setContentText(app.getString(R.string.vpn_match_found_text, name, ms / 1000.0))
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(app.getString(R.string.vpn_match_found_text, name, ms / 1000.0)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(7311, n)
        }
    }
}
