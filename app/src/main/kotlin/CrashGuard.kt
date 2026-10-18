// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.stoyko.smessenger.domain.Diagnostics

/**
 * Защита от вылетов. Ошибка в обработчике нажатия (нет доступа к файлу, нет нужного приложения,
 * испорченные данные и т. п.) больше не закрывает приложение: она записывается в журнал,
 * пользователь видит короткое сообщение, а главный поток продолжает работать.
 *
 * Не перехватываем то, после чего продолжать опасно: нехватку памяти и ошибки при создании
 * экранов/приложения (экран остался бы недосозданным). Если ошибки идут потоком
 * (больше 4 за 10 секунд) - даём приложению упасть штатно, чтобы не зависнуть в цикле.
 */
object CrashGuard {
    private const val TAG = "CrashGuard"
    private val recent = ArrayDeque<Long>()
    @Volatile
    private var installed = false

    /** Экран, который сейчас на виду, - чтобы пересоздать его после ошибки в разметке. */
    @Volatile
    private var top: java.lang.ref.WeakReference<Activity>? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) {
                top = java.lang.ref.WeakReference(a)
            }

            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) {
                if (top?.get() === a) top = null
            }
        })
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    // Looper.loop() возвращается только при quit() - главный поток завершается.
                    return@post
                } catch (e: Throwable) {
                    if (!recoverable(e)) throw e
                    Diagnostics.record("главный поток (перехвачено, без вылета)", e, fatal = false)
                    Log.e(TAG, "recovered", e)
                    runCatching { Toast.makeText(app, R.string.crash_recovered, Toast.LENGTH_SHORT).show() }
                    // Ошибка посреди разметки/отрисовки оставляет окно «замороженным» (система считает,
                    // что разметка ещё идёт, и больше не обновляет экран) - приложение висит и не
                    // реагирует. Пересоздаём экран: интерфейс строится заново и снова отвечает.
                    if (inTraversal(e)) {
                        val a = top?.get()
                        if (a != null && !a.isFinishing && !a.isDestroyed) {
                            Handler(Looper.getMainLooper()).post { runCatching { a.recreate() } }
                        }
                    }
                }
            }
        }
    }

    private fun recoverable(e: Throwable): Boolean {
        if (e is OutOfMemoryError || e is StackOverflowError || e is LinkageError) return false
        val now = System.currentTimeMillis()
        synchronized(recent) {
            while (recent.isNotEmpty() && now - recent.first() > 10_000) recent.removeFirst()
            recent.addLast(now)
            if (recent.size > 4) return false
        }
        // Ошибка при создании экрана или приложения - продолжать нельзя.
        var t: Throwable? = e
        while (t != null) {
            for (el in t.stackTrace) {
                val m = el.methodName
                if (m == "handleBindApplication" || m == "performLaunchActivity" || m == "handleLaunchActivity" ||
                    m == "performCreate" || m == "handleCreateService" || m == "handleRelaunchActivity" ||
                    m == "onCreateView" && el.className.startsWith("androidx.fragment")
                ) {
                    return false
                }
            }
            t = t.cause
        }
        return true
    }

    /** Ошибка случилась во время разметки, измерения или отрисовки окна. */
    private fun inTraversal(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            for (el in t.stackTrace) {
                val m = el.methodName
                if (m == "performTraversals" || m == "layoutChildren") {
                    return true
                }
            }
            t = t.cause
        }
        return false
    }

    /**
     * «Перезапуск»-клиентах: сохраняет профиль, останавливает сеть, чистит кэш
     * и запускает процесс заново - приложение заново подключается к сети с чистого листа.
     */
    fun restartApp(activity: Activity, clearCache: Boolean = true) {
        val app = activity.applicationContext
        Toast.makeText(activity, R.string.restarting, Toast.LENGTH_SHORT).show()
        Thread {
            runCatching { (app as App).component.toxStarter().stopTox() }
            // Ждём, пока служба сохранит профиль и закроет сеть.
            var waited = 0
            while (ToxService.running && waited < 4000) {
                Thread.sleep(100)
                waited += 100
            }
            if (clearCache) runCatching { com.stoyko.smessenger.settings.StorageCleaner.clearCache(app) }
            Handler(Looper.getMainLooper()).postDelayed({
                // Перезапуск через отдельный процесс (RestartActivity): он завершает этот
                // процесс и сам открывает приложение заново - работает на всех телефонах.
                val phoenix = Intent(app, RestartActivity::class.java)
                    .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val started = runCatching { activity.startActivity(phoenix) }.isSuccess
                if (!started) {
                    val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
                    if (launch?.component != null) {
                        val restart = Intent.makeRestartActivityTask(launch.component)
                        runCatching { app.startActivity(restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    runCatching { activity.finishAffinity() }
                    Runtime.getRuntime().exit(0)
                } else {
                    runCatching { activity.finishAffinity() }
                    // Подстраховка: если окно перезапуска не завершило процесс за 3 с - сами.
                    Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 3000)
                }
            }, 600)
        }.start()
    }
}
