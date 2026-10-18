// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.settings

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.stoyko.smessenger.domain.tox.BootstrapNode
import com.stoyko.smessenger.domain.tox.BootstrapNodeRegistry

/**
 * Сторож узлов: следит за узлами, через которые сейчас идёт связь, и держит наготове запас свежих.
 *
 * Раз в 1,5 минуты (при выключенном экране - реже) проверяет 10 «рабочих» узлов из начала списка
 * и 14 следующих - кандидатов в запас. Если рабочие начали тормозить (задержка выросла вдвое) или
 * несколько перестали отвечать, или связи нет - сразу подключает заранее проверенные свежие узлы
 * (они уже подняты в начало списка), не дожидаясь обрыва. Параллельно список узлов обновляется
 * из источников (раз в 6 часов).
 */
object NodeWatchdog {
    private const val TAG = "NodeWatchdog"
    private const val HEAD = 10
    private const val CANDIDATES = 14
    private const val SWAP_MIN_INTERVAL_MS = 120_000L

    @Volatile
    private var lastSwap = 0L

    @Volatile
    private var headMedian = 0L

    /** Короткий отчёт о последней проверке (для журнала и экрана узлов). */
    @Volatile
    var lastReport: String = ""
        private set

    /** Узлы, готовые к подмене (живые и быстрые), по последней проверке. */
    @Volatile
    var reserve: List<BootstrapNode> = emptyList()
        private set

    /**
     * Одна проверка. [online] - есть ли сейчас подключение к сети Tox.
     * [swap] вызывается, когда нужно подставить свежие узлы (переподключение к лучшим).
     */
    fun tick(ctx: Context, registry: BootstrapNodeRegistry, online: Boolean, swap: () -> Unit) {
        val list = registry.get(HEAD + CANDIDATES)
        if (list.isEmpty()) return
        val pool = Executors.newFixedThreadPool(12) { r -> Thread(r, "node-watch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        val results = try {
            val futures = list.map { n -> pool.submit<Long> { NodeHealth.probeNode(n.address, n.port, n.tcpPorts) } }
            list.mapIndexed { i, n -> n to runCatching { futures[i].get(4, TimeUnit.SECONDS) }.getOrDefault(-1L) }
        } finally {
            pool.shutdownNow()
        }
        results.forEach { (n, ms) -> NodeHealth.recordPing(ctx, n.address, n.port, ms) }

        val head = results.take(HEAD)
        val headAlive = head.filter { it.second >= 0 }.map { it.second }.sorted()
        val dead = head.size - headAlive.size
        val median = headAlive.getOrNull(headAlive.size / 2) ?: -1L
        reserve = results.drop(HEAD).filter { it.second >= 0 }.sortedBy { it.second }.take(6).map { it.first }

        val prev = headMedian
        val slower = prev > 0 && median > 0 && median > prev * 2 && median > 500
        val degraded = !online || dead >= 3 || slower
        headMedian = if (median > 0) (if (prev > 0) (prev * 2 + median) / 3 else median) else prev

        lastReport = "head ${headAlive.size}/${head.size} alive, median ${median}ms, reserve ${reserve.size}" +
            (if (degraded) " - swap" else "")
        Log.i(TAG, lastReport)

        val now = System.currentTimeMillis()
        if (degraded && reserve.isNotEmpty() && now - lastSwap > SWAP_MIN_INTERVAL_MS) {
            lastSwap = now
            // Порядок подключения уже обновлён (пинги записаны): упавшие ушли вниз, свежие - наверх.
            swap()
        }
        // Раз в 5 часов - свежие узлы из источников, проверка скорости и переподключение к быстрым.
        NodeHealth.autoUpdate(ctx) {
            registry.reset()
            swap()
        }
    }
}
