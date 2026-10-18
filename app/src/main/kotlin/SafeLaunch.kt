// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Исправление вылета «UID … does not have permission to content://…externalstorage…».
 *
 * Файл, выбранный когда-то через системный проводник, приложение может читать само, но не имеет
 * права «передать» этот адрес другому приложению (открыть, поделиться, установить APK). Android
 * в этом случае бросает SecurityException прямо из startActivity. Теперь такой файл копируется
 * во временную папку приложения и передаётся через свой FileProvider - доступ есть всегда.
 */
object SafeLaunch {
    private const val TAG = "SafeLaunch"

    /** Адрес чужого провайдера, который нельзя передать дальше. */
    private fun foreign(activity: Activity, uri: Uri?): Boolean =
        uri != null && uri.scheme == "content" && uri.authority != "${activity.packageName}.fileprovider"

    /** Есть ли в намерении (или во вложенном, у окна «Поделиться») чужие адреса. */
    fun hasForeignUris(activity: Activity, intent: Intent): Boolean {
        if (foreign(activity, intent.data)) return true
        streams(intent).any { foreign(activity, it) }.let { if (it) return true }
        val inner = innerIntent(intent) ?: return false
        return hasForeignUris(activity, inner)
    }

    @Suppress("DEPRECATION")
    private fun innerIntent(intent: Intent): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = runCatching {
        val one: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (one != null) return@runCatching listOf(one)
        val many: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
        many.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Повторить запуск с копиями файлов (в фоне: файл может быть большим).
     * [launch] вызывается в главном потоке с исправленным намерением.
     */
    fun retryWithCopies(activity: Activity, intent: Intent, launch: (Intent) -> Unit) {
        Toast.makeText(activity, R.string.preparing_file, Toast.LENGTH_SHORT).show()
        Thread({
            val fixed = try {
                rewrite(activity, intent)
            } catch (e: Exception) {
                Log.w(TAG, "rewrite: $e")
                null
            }
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (fixed == null) {
                    Toast.makeText(activity, R.string.file_no_access, Toast.LENGTH_LONG).show()
                } else {
                    try {
                        launch(fixed)
                    } catch (e: Exception) {
                        Log.w(TAG, "launch: $e")
                        Toast.makeText(activity, R.string.file_no_access, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, "safe-launch").start()
    }

    @Suppress("DEPRECATION")
    private fun rewrite(activity: Activity, src: Intent): Intent? {
        val intent = Intent(src)
        val map = HashMap<Uri, Uri>()
        fun fix(u: Uri): Uri? {
            if (!foreign(activity, u)) return u
            map[u]?.let { return it }
            val copy = copy(activity, u) ?: return null
            map[u] = copy
            return copy
        }
        intent.data?.let { d -> intent.setDataAndType(fix(d) ?: return null, intent.type) }
        val s = streams(intent)
        if (s.size == 1 && intent.action != Intent.ACTION_SEND_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_STREAM, fix(s[0]) ?: return null)
        } else if (s.isNotEmpty()) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(s.map { fix(it) ?: return null }))
        }
        innerIntent(intent)?.let { inner ->
            val fixedInner = rewrite(activity, inner) ?: return null
            intent.putExtra(Intent.EXTRA_INTENT, fixedInner)
        }
        // Старый ClipData держит исходные адреса - система проверяет и его.
        val uris = (listOfNotNull(intent.data) + streams(intent))
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
        } else {
            intent.clipData = null
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    private fun copy(activity: Activity, uri: Uri): Uri? {
        val cr = activity.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
        val dir = File(activity.cacheDir, "handoff").apply { mkdirs() }
        // Старые копии (больше суток) убираем, чтобы не копить мусор.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L) it.deleteRecursively() }
        val sub = File(dir, System.currentTimeMillis().toString(36)).apply { mkdirs() }
        val out = File(sub, safe)
        val input = runCatching { cr.openInputStream(uri) }.getOrNull() ?: return null
        input.use { i -> out.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        return FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", out)
    }
}
