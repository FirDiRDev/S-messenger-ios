// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlinx.coroutines.CoroutineExceptionHandler

private const val TAG = "Diag"
private const val MAX_ERRORS_BYTES = 64 * 1024
private const val FATAL_MARK = "ВЫЛЕТ"

/**
 * Встроенная диагностика сбоев - чтобы причину вылета было видно без компьютера и adb.
 *
 * 1. Ошибки Java/Kotlin (в любом потоке) записываются в файл.
 * 2. Перед обращениями к нативной библиотеке записывается «этап». Если процесс упал внутри
 *    нативного кода (такие сбои Java не перехватить), этап остаётся в файле, и при следующем
 *    запуске видно, на чём именно всё оборвалось.
 * Отчёт показывается при следующем запуске приложения (MainActivity).
 */
object Diagnostics {
    @Volatile
    private var dir: File? = null
    private val active = ConcurrentLinkedDeque<String>()

    fun init(filesDir: File) {
        dir = File(filesDir, "diag").apply { mkdirs() }
        // Шаги прошлого запуска - чтобы после вылета было видно, что происходило перед ним.
        runCatching { file("crumbs.txt")?.takeIf { it.exists() }?.readLines()?.takeLast(CRUMBS)?.let { prevCrumbs = it } }
    }

    // ---------- «Хлебные крошки»: последние действия и события ----------

    private const val CRUMBS = 60
    private val crumbs = ConcurrentLinkedDeque<String>()

    @Volatile
    private var prevCrumbs: List<String> = emptyList()

    /**
     * Сведения о состоянии приложения в момент ошибки (версия, устройство, память, сеть, VPN,
     * подключение к сети Tox). Задаёт приложение.
     */
    @Volatile
    var context: (() -> String)? = null

    /** Событие (открыт экран, сменилась сеть, начался звонок…) - попадёт в отчёт об ошибке. */
    fun crumb(event: String) {
        crumbs.add("${time()} $event")
        while (crumbs.size > CRUMBS) crumbs.pollFirst()
        try {
            file("crumbs.txt")?.writeText(crumbs.joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    fun crumbsText(): String = crumbs.joinToString("\n")
    fun previousCrumbs(): String = prevCrumbs.joinToString("\n")

    /** Записать событие в журнал как предупреждение (без исключения): зависание, обрыв и т. п. */
    fun warn(where: String, details: String) {
        try {
            val f = file("errors.txt") ?: return
            if (f.length() > MAX_ERRORS_BYTES) f.delete()
            f.appendText("=== ${time()} предупреждение: $where\n$details\n${contextBlock()}\n")
        } catch (_: Throwable) {
        }
    }

    private fun contextBlock(): String = buildString {
        context?.let { c -> runCatching { append("Состояние: ").append(c()).append('\n') } }
        if (crumbs.isNotEmpty()) append("Последние события:\n").append(crumbs.toList().takeLast(15).joinToString("\n")).append('\n')
    }

    /** Записать в журнал как есть (отчёты системы о прошлых завершениях и т. п.). */
    fun appendRaw(text: String) = appendHistory(text)

    private fun file(name: String): File? = dir?.let { File(it, name) }

    private fun writeStages() {
        try {
            file("stage.txt")?.writeText(active.joinToString("\n"))
        } catch (_: Throwable) {
        }
    }

    /**
     * Выполняет block, отмечая этап. Если процесс умрёт внутри - этап останется в отчёте.
     * Долгие периодические этапы (сохранение профиля) не отмечаем: если Android просто
     * выгрузил приложение из памяти в этот момент, это не сбой, и отчёт показывать нельзя.
     */
    inline fun <T> track(stage: String, block: () -> T): T {
        begin(stage)
        try {
            return block()
        } finally {
            end(stage)
        }
    }

    /** Этап без записи на диск (только в журнал) - для частых/периодических действий. */
    inline fun <T> light(stage: String, block: () -> T): T {
        Log.v("Diag", "> $stage")
        return block()
    }

    fun begin(stage: String) {
        Log.i(TAG, "> $stage")
        active.add("${time()} $stage")
        writeStages()
    }

    fun end(stage: String) {
        active.removeLastOccurrence(active.lastOrNull { it.endsWith(" $stage") } ?: return)
        writeStages()
    }

    /** Записывает ошибку. fatal=true - приложение сейчас упадёт. */
    fun record(where: String, e: Throwable, fatal: Boolean) {
        Log.e(TAG, "$where (fatal=$fatal)", e)
        try {
            val f = file("errors.txt") ?: return
            if (f.length() > MAX_ERRORS_BYTES) f.delete()
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val extra = buildString {
                append(contextBlock())
                if (active.isNotEmpty()) append("Незавершённые этапы:\n").append(active.joinToString("\n")).append('\n')
                if (fatal) {
                    // Что делали остальные потоки в момент вылета (зависания и гонки видны сразу).
                    append("Потоки:\n")
                    runCatching {
                        Thread.getAllStackTraces().entries.sortedBy { it.key.name }.take(40).forEach { (t, st) ->
                            append("  ").append(t.name).append(" [").append(t.state).append("] ")
                            append(st.take(3).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
                            append('\n')
                        }
                    }
                }
            }
            f.appendText(
                "=== ${time()} ${if (fatal) "ВЫЛЕТ" else "ошибка в фоне"}: $where\n" +
                    "Поток: ${Thread.currentThread().name}\n$sw\n$extra\n",
            )
        } catch (_: Throwable) {
        }
    }

    /** Обработчик для корутин: ошибка в фоне записывается, а не роняет приложение. */
    fun coroutineHandler(where: String) = CoroutineExceptionHandler { _, e -> record(where, e, fatal = false) }

    /**
     * Текст отчёта о прошлом ВЫЛЕТЕ или null. Окно показывается только при настоящем сбое:
     * ошибки в фоне (не приводившие к закрытию) и выгрузка приложения системой отчёта не вызывают.
     * [lastExitWasCrash] - что сообщила система о прошлом завершении (null - неизвестно, Android < 11).
     */
    fun takePendingReport(lastExitWasCrash: Boolean? = null): String? {
        val stages = try {
            file("stage.txt")?.takeIf { it.exists() }?.readText().orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
        val errors = try {
            file("errors.txt")?.takeIf { it.exists() }?.readText().orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
        file("stage.txt")?.delete()
        // Все ошибки остаются в общем журнале (Настройки → «Журнал ошибок»).
        if (errors.isNotEmpty()) appendHistory(errors)
        file("errors.txt")?.delete()
        val fatal = errors.contains(FATAL_MARK)
        // Незавершённый этап - признак сбоя нативной библиотеки, но только если система
        // не сказала, что приложение закрылось штатно (выгружено из памяти, смахнуто и т. п.).
        val nativeCrash = stages.isNotEmpty() && lastExitWasCrash != false
        if (!fatal && !nativeCrash) return null
        return buildString {
            if (nativeCrash) {
                append("Незавершённые этапы (приложение закрылось внутри них - вероятно, сбой нативной библиотеки):\n")
                append(stages).append("\n\n")
                if (prevCrumbs.isNotEmpty()) append("Последние события перед закрытием:\n").append(prevCrumbs.takeLast(20).joinToString("\n")).append("\n\n")
            }
            if (fatal) append(errors).append('\n')
        }
    }

    private fun appendHistory(text: String) {
        try {
            val f = file("history.txt") ?: return
            if (f.length() > MAX_ERRORS_BYTES * 2) {
                val keep = f.readText().takeLast(MAX_ERRORS_BYTES)
                f.writeText(keep)
            }
            f.appendText(text + "\n")
        } catch (_: Throwable) {
        }
    }

    /** Журнал всех записанных ошибок (для раздела настроек). */
    fun history(): String = try {
        val hf = file("history.txt")
        val h = hf?.takeIf { it.exists() }?.readText().orEmpty()
        // 4.12.1: записи старше 7 дней убираем - в журнале не висят давно исправленные ошибки
        // старых версий (например, зависание 4.0.3 недельной давности в каждом отчёте).
        val fresh = dropOld(h)
        if (fresh.length != h.length) runCatching { hf?.writeText(fresh) }
        val cur = file("errors.txt")?.takeIf { it.exists() }?.readText().orEmpty()
        (fresh + cur).trim()
    } catch (_: Throwable) {
        ""
    }

    private const val KEEP_DAYS = 7

    /** Оставить записи «=== dd.MM HH:mm…» не старше [KEEP_DAYS] дней. */
    private fun dropOld(text: String): String {
        if (text.isBlank()) return text
        val now = java.util.Calendar.getInstance()
        val head = Regex("^=== (\\d{2})\\.(\\d{2}) ")
        val blocks = text.split(Regex("(?m)(?=^=== \\d{2}\\.\\d{2} )"))
        return blocks.filter { b ->
            val m = head.find(b) ?: return@filter true
            val c = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.DAY_OF_MONTH, m.groupValues[1].toInt())
                set(java.util.Calendar.MONTH, m.groupValues[2].toInt() - 1)
                // Дата «из будущего» - значит, прошлого года.
                if (after(now)) add(java.util.Calendar.YEAR, -1)
            }
            (now.timeInMillis - c.timeInMillis) / 86_400_000L <= KEEP_DAYS
        }.joinToString("")
    }

    fun clearHistory() {
        runCatching { file("history.txt")?.delete() }
        runCatching { file("errors.txt")?.delete() }
    }

    private fun time(): String = SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.US).format(Date())
}
