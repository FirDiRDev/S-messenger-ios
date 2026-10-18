// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Экспорт данных «для чтения» - ZIP, который открывается на любом компьютере:
 *
 *  - `index.html` - оглавление; у каждого чата и группы своя страница `chat.html` с пузырями
 *    и фото прямо в переписке, а также `messages.txt` и `messages.json`;
 *  - `media/` - файлы из чатов (фото, видео, голосовые, документы) рядом с перепиской;
 *  - `contacts.json`, `gifts.json`, `skins.json`, `money.json`, `stats.json`,
 *    `connections.json` - контакты, подарки, скины, монеты, статистика, узлы/прокси/VPN;
 *  - `manifest.json` (последним) - опись с SHA-256 каждого файла.
 *
 * Без повреждений: база читается отдельным соединением только для чтения (Room в режиме WAL
 * это допускает), каждый файл считается потоком, а после записи архив перечитывается целиком
 * и каждый файл сверяется с описью. Ключи профиля и пароли сюда не попадают
 * (для переноса на другой телефон - полный профиль `.smsave`).
 */
object DataExport {
    enum class Cat(val key: String) {
        MESSAGES("messages"), MEDIA("media"), CONTACTS("contacts"), GROUPS("groups"),
        GIFTS("gifts"), SKINS("skins"), MONEY("money"), STATS("stats"), CONNECTIONS("connections"),
    }

    class Result(val files: Int, val bytes: Long, val skipped: List<String>, val chats: Int, val messages: Int)

    private val PREFS = mapOf(
        Cat.MESSAGES to listOf("chat_extras", "saved_messages", "scheduled", "polls", "captions", "drafts", "chat_folders", "pinned_chats", "chat_archive", "voice_text", "auto_delete"),
        Cat.CONTACTS to listOf("contact_alias", "contact_full_ids", "birthday", "chat_mute"),
        Cat.GROUPS to listOf("group_settings", "group_profiles", "group_welcome", "channel_views", "group_reactions", "group_invites"),
        Cat.GIFTS to listOf("gifts", "coin_gifts"),
        Cat.SKINS to listOf("runner", "shop_limited", "games_ui", "chat_look", "ui", "profile_card"),
        Cat.MONEY to listOf("coins", "coin_tx", "coin_limits", "coin_offers", "bj_ledger", "coin_shop_cfg"),
        Cat.STATS to listOf("game_stats", "match3", "blackjack", "durak_local", "chess_local", "metro"),
        Cat.CONNECTIONS to listOf("proxies", "vpn", "vpn_split", "vpn_tweaks", "warp", "transport", "dpi_sources"),
    )

    /** Ключи с секретами - не выгружаются никогда. */
    private fun secret(key: String) = key.contains("password", true) || key.startsWith("key_") || key.contains("secret", true) || key.contains("token", true)

    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun export(ctx: Context, out: OutputStream, cats: Set<Cat>, progress: FullBackup.Progress? = null): Result {
        progress?.update(FullBackup.Stage.PREPARE, 0, 0)
        val w = Writer(out)
        val skipped = ArrayList<String>()
        var chats = 0
        var msgs = 0
        val db = openDb(ctx)
        try {
            val names = HashMap<String, String>()
            val aliases = runCatching { ctx.getSharedPreferences("contact_alias", Context.MODE_PRIVATE).all }.getOrDefault(emptyMap())
            // Контакты.
            val contacts = JSONArray()
            db?.query("contacts", null, null, null, null, null, "name")?.use { c ->
                while (c.moveToNext()) {
                    val pk = c.str("public_key")
                    val name = (aliases[pk] as? String)?.takeIf { it.isNotBlank() } ?: c.str("name").ifBlank { pk.take(8) }
                    names[pk] = name
                    contacts.put(JSONObject().put("public_key", pk).put("name", name).put("status_message", c.str("status_message")).put("last_message", c.long("last_message")))
                }
            }
            if (Cat.CONTACTS in cats) {
                w.text("contacts.json", contacts.toString(2))
                val reqs = JSONArray()
                db?.let { d ->
                    runCatching {
                        d.query("friend_requests", null, null, null, null, null, null).use { c ->
                            while (c.moveToNext()) reqs.put(JSONObject().put("public_key", c.str("public_key")).put("message", c.str("message")))
                        }
                    }
                }
                if (reqs.length() > 0) w.text("friend_requests.json", reqs.toString(2))
            }
            // Общий объём медиа - для полосы прогресса.
            val total = if (Cat.MEDIA in cats) FullBackup.mediaSize(ctx).coerceAtLeast(1) else 0L
            var done = 0L
            val index = StringBuilder()
            // Личные чаты.
            if ((Cat.MESSAGES in cats || Cat.MEDIA in cats) && db != null) {
                val fts = HashMap<Int, Pair<String, String>>()
                runCatching {
                    db.query("file_transfers", arrayOf("id", "destination", "file_name"), null, null, null, null, null).use { c ->
                        while (c.moveToNext()) fts[c.int("id")] = c.str("destination") to c.str("file_name")
                    }
                }
                val convs = ArrayList<String>()
                db.rawQuery("SELECT DISTINCT conversation FROM messages", null).use { c -> while (c.moveToNext()) convs += c.getString(0) }
                index.append("<h2>").append(t(ctx, "Чаты", "Chats")).append("</h2><ul>")
                for (pk in convs) {
                    val title = names[pk] ?: pk.take(8)
                    val dir = "chats/" + safe(title) + "_" + pk.take(8)
                    val txt = StringBuilder()
                    val json = JSONArray()
                    val html = StringBuilder()
                    var n = 0
                    db.query("messages", null, "conversation = ?", arrayOf(pk), null, null, "timestamp, id").use { c ->
                        while (c.moveToNext()) {
                            val raw = c.str("message")
                            val type = c.int("type")
                            if (type != 2 && com.stoyko.smessenger.chat.Markup.isHidden(raw)) continue
                            val out = c.int("sender") == 0
                            val ts = c.long("timestamp")
                            val who = if (out) t(ctx, "Я", "Me") else title
                            if (type == 2) {
                                val ft = fts[c.int("correlation_id")]
                                val fname = com.stoyko.smessenger.chat.MediaSpoiler.clean(ft?.second ?: raw)
                                var rel: String? = null
                                if (Cat.MEDIA in cats && ft != null && ft.first.isNotBlank()) {
                                    val name = "media/" + c.long("id") + "_" + safe(fname)
                                    val copied = w.stream("$dir/$name") { openUri(ctx, ft.first) }
                                    if (copied >= 0) {
                                        rel = name
                                        done += copied
                                        progress?.update(FullBackup.Stage.WRITE, done, total)
                                    } else {
                                        skipped += fname
                                    }
                                }
                                txt.append("[").append(time.format(Date(ts))).append("] ").append(who).append(": 📎 ").append(fname).append('\n')
                                json.put(JSONObject().put("time", ts).put("out", out).put("file", fname).put("path", rel ?: JSONObject.NULL))
                                html.append(bubble(out, who, ts, fileHtml(fname, rel)))
                            } else {
                                val (quote, body) = runCatching { com.stoyko.smessenger.chat.Markup.parseReply(raw) }.getOrDefault(null to raw)
                                val plain = runCatching { com.stoyko.smessenger.chat.RichText.plain(body) }.getOrDefault(body).replace(INV, "")
                                txt.append("[").append(time.format(Date(ts))).append("] ").append(who).append(": ")
                                if (quote != null) txt.append("↩ «").append(quote).append("» ")
                                txt.append(plain).append('\n')
                                json.put(JSONObject().put("time", ts).put("out", out).put("text", plain).put("reply", quote ?: JSONObject.NULL))
                                html.append(bubble(out, who, ts, (if (quote != null) "<div class=q>" + esc(quote) + "</div>" else "") + esc(plain).replace("\n", "<br>")))
                            }
                            n++
                        }
                    }
                    if (n == 0) continue
                    chats++
                    msgs += n
                    if (Cat.MESSAGES in cats) {
                        w.text("$dir/messages.txt", txt.toString())
                        w.text("$dir/messages.json", json.toString(1))
                    }
                    w.text("$dir/chat.html", page(title, html.toString()))
                    index.append("<li><a href=\"").append(esc(dir)).append("/chat.html\">").append(esc(title)).append("</a> <small>").append(n).append("</small></li>")
                }
                index.append("</ul>")
            }
            // Группы и каналы.
            if (Cat.GROUPS in cats && db != null) {
                index.append("<h2>").append(t(ctx, "Группы и каналы", "Groups and channels")).append("</h2><ul>")
                val groups = JSONArray()
                val list = ArrayList<Triple<String, String, Boolean>>()
                runCatching {
                    db.query("group_chats", null, null, null, null, null, "name").use { c ->
                        while (c.moveToNext()) {
                            list += Triple(c.str("chat_id"), c.str("name"), c.int("is_channel") != 0)
                            groups.put(JSONObject().put("chat_id", c.str("chat_id")).put("name", c.str("name")).put("channel", c.int("is_channel") != 0).put("role", c.int("role")).put("members", c.int("peer_count")))
                        }
                    }
                }
                w.text("groups.json", groups.toString(2))
                for ((id, name0, channel) in list) {
                    val name = name0.ifBlank { id.take(8) }
                    val dir = "groups/" + safe(name) + "_" + id.take(8)
                    val txt = StringBuilder()
                    val json = JSONArray()
                    val html = StringBuilder()
                    var n = 0
                    runCatching {
                        db.query("group_messages", null, "chat_id = ?", arrayOf(id), null, null, "timestamp").use { c ->
                            while (c.moveToNext()) {
                                val raw = c.str("message")
                                val fileName = c.str("file_name")
                                val ts = c.long("timestamp")
                                val out = c.int("outgoing") != 0
                                val who = if (out) t(ctx, "Я", "Me") else c.str("sender_name").ifBlank { c.str("sender_key").take(8) }
                                if (fileName.isNotBlank()) {
                                    val clean = com.stoyko.smessenger.chat.MediaSpoiler.clean(fileName)
                                    var rel: String? = null
                                    val path = c.str("file_path")
                                    if (Cat.MEDIA in cats && path.isNotBlank() && File(path).isFile) {
                                        val r = "media/" + c.long("id") + "_" + safe(clean)
                                        val copied = w.stream("$dir/$r") { File(path).inputStream() }
                                        if (copied >= 0) {
                                            rel = r
                                            done += copied
                                        } else {
                                            skipped += clean
                                        }
                                    }
                                    txt.append("[").append(time.format(Date(ts))).append("] ").append(who).append(": 📎 ").append(clean).append('\n')
                                    json.put(JSONObject().put("time", ts).put("from", who).put("file", clean).put("path", rel ?: JSONObject.NULL))
                                    html.append(bubble(out, who, ts, fileHtml(clean, rel)))
                                } else {
                                    if (com.stoyko.smessenger.chat.Markup.isHidden(raw)) continue
                                    val body = runCatching { com.stoyko.smessenger.chat.Markup.body(raw) }.getOrDefault(raw)
                                    val plain = runCatching { com.stoyko.smessenger.chat.RichText.plain(body) }.getOrDefault(body).replace(INV, "")
                                    txt.append("[").append(time.format(Date(ts))).append("] ").append(who).append(": ").append(plain).append('\n')
                                    json.put(JSONObject().put("time", ts).put("from", who).put("text", plain))
                                    html.append(bubble(out, who, ts, esc(plain).replace("\n", "<br>")))
                                }
                                n++
                            }
                        }
                    }
                    w.text("$dir/messages.txt", txt.toString())
                    w.text("$dir/messages.json", json.toString(1))
                    w.text("$dir/chat.html", page((if (channel) "📢 " else "👥 ") + name, html.toString()))
                    index.append("<li><a href=\"").append(esc(dir)).append("/chat.html\">").append(if (channel) "📢 " else "👥 ").append(esc(name)).append("</a> <small>").append(n).append("</small></li>")
                    msgs += n
                }
                index.append("</ul>")
            }
            // Настройки по разделам: подарки, скины, монеты, статистика, подключения…
            for ((cat, prefs) in PREFS) {
                if (cat !in cats) continue
                val o = JSONObject()
                for (name in prefs) {
                    val all = runCatching { ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrNull() ?: continue
                    if (all.isEmpty()) continue
                    val p = JSONObject()
                    for ((k, v) in all.toSortedMap()) {
                        if (secret(k)) continue
                        p.put(k, prettyValue(v))
                    }
                    o.put(name, p)
                }
                if (cat == Cat.CONNECTIONS) {
                    // Узлы сети (свои и проверенные онлайн).
                    for (f in listOf("online_nodes.json", "nodes.json", "custom_nodes.json")) {
                        val file = File(ctx.filesDir, f)
                        if (file.isFile && file.length() < 8L * 1024 * 1024) {
                            w.stream("connections/$f") { file.inputStream() }
                        }
                    }
                }
                if (o.length() > 0) w.text(cat.key + ".json", o.toString(2))
            }
            w.text("index.html", page("S-messenger · " + t(ctx, "экспорт данных", "data export"), index.toString() + "<p><small>" + esc(time.format(Date())) + "</small></p>"))
            w.text(
                "README.txt",
                t(
                    ctx,
                    "Экспорт данных S-messenger.\nОткройте index.html в браузере. Переписка - в папках chats/ и groups/ (chat.html, messages.txt, messages.json), файлы - в media/.\nКлючи профиля и пароли сюда не входят. Для переноса на другой телефон используйте полный профиль .smsave.\n",
                    "S-messenger data export.\nOpen index.html in a browser. Chats are in chats/ and groups/ (chat.html, messages.txt, messages.json), files in media/.\nProfile keys and passwords are not included. To move to another phone, use the full .smsave profile.\n",
                ),
            )
            w.finish(JSONObject().put("app", com.stoyko.smessenger.BuildConfig.VERSION_NAME).put("created", System.currentTimeMillis()).put("categories", JSONArray(cats.map { it.key })))
            return Result(w.count, w.bytes, skipped, chats, msgs)
        } finally {
            runCatching { db?.close() }
        }
    }

    /** Перечитать архив и сверить каждый файл с описью. null - всё совпало. */
    fun verify(input: InputStream, total: Long, progress: FullBackup.Progress? = null): String? = runCatching {
        val got = HashMap<String, String>()
        var manifest: JSONObject? = null
        var done = 0L
        ZipInputStream(input.buffered(128 * 1024)).use { z ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = z.nextEntry ?: break
                val md = MessageDigest.getInstance("SHA-256")
                val text = if (e.name == MANIFEST) java.io.ByteArrayOutputStream() else null
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    text?.write(buf, 0, n)
                    done += n
                }
                if (text != null) manifest = JSONObject(text.toString("UTF-8")) else got[e.name] = hex(md.digest())
                progress?.update(FullBackup.Stage.VERIFY, done, total)
            }
        }
        val m = manifest ?: return@runCatching "no manifest"
        val files = m.getJSONArray("files")
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.getString("name")
            val sha = got[name] ?: return@runCatching "missing: $name"
            if (sha != f.getString("sha256")) return@runCatching "damaged: $name"
        }
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    // ---------- запись ----------

    private const val MANIFEST = "manifest.json"
    private const val INV = "⁣"

    private class Writer(out: OutputStream) {
        private val zip = ZipOutputStream(out.buffered(128 * 1024))
        private val files = JSONArray()
        private val used = HashSet<String>()
        var count = 0
        var bytes = 0L

        private fun unique(name: String): String {
            var n = name
            var k = 2
            while (!used.add(n)) {
                val dot = name.lastIndexOf('.')
                n = if (dot > name.lastIndexOf('/')) name.substring(0, dot) + "_" + k + name.substring(dot) else name + "_" + k
                k++
            }
            return n
        }

        fun text(name: String, s: String) {
            val data = s.toByteArray(Charsets.UTF_8)
            val n = unique(name)
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(n))
            zip.write(data)
            zip.closeEntry()
            files.put(JSONObject().put("name", n).put("size", data.size).put("sha256", hex(MessageDigest.getInstance("SHA-256").digest(data))))
            count++
            bytes += data.size
        }

        /** Файл потоком; -1 - не удалось прочитать (запись в архиве не появится). */
        fun stream(name: String, open: () -> InputStream?): Long {
            val input = runCatching { open() }.getOrNull() ?: return -1
            val n = unique(name)
            val md = MessageDigest.getInstance("SHA-256")
            var size = 0L
            input.use { i ->
                val packed = n.substringAfterLast('.', "").lowercase() in PACKED
                zip.setLevel(if (packed) Deflater.NO_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
                zip.putNextEntry(ZipEntry(n))
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = runCatching { i.read(buf) }.getOrDefault(-1)
                    if (r < 0) break
                    zip.write(buf, 0, r)
                    md.update(buf, 0, r)
                    size += r
                }
                zip.closeEntry()
            }
            files.put(JSONObject().put("name", n).put("size", size).put("sha256", hex(md.digest())))
            count++
            bytes += size
            return size
        }

        fun finish(head: JSONObject) {
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(head.put("files", files).toString(1).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.finish()
            zip.flush()
        }
    }

    private val PACKED = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "mp4", "mkv", "webm", "3gp", "mov", "m4a", "mp3", "ogg", "opus", "aac", "zip", "apk", "gz", "7z", "rar")

    // ---------- вспомогательное ----------

    private fun openDb(ctx: Context): SQLiteDatabase? = runCatching {
        val f = ctx.getDatabasePath("core_db")
        if (!f.isFile) null else SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()

    private fun openUri(ctx: Context, s: String): InputStream? {
        val u = Uri.parse(s)
        return if (u.scheme == "file" || u.scheme == null) u.path?.let { File(it) }?.takeIf { it.isFile }?.inputStream() else ctx.contentResolver.openInputStream(u)
    }

    private fun Cursor.idx(n: String) = getColumnIndex(n)
    private fun Cursor.str(n: String): String = idx(n).let { if (it < 0 || isNull(it)) "" else getString(it) }
    private fun Cursor.long(n: String): Long = idx(n).let { if (it < 0 || isNull(it)) 0L else getLong(it) }
    private fun Cursor.int(n: String): Int = idx(n).let { if (it < 0 || isNull(it)) 0 else getInt(it) }

    private fun prettyValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(v.map { it.toString() })
        is String -> {
            val s = v.trim()
            when {
                s.startsWith("{") -> runCatching { JSONObject(s) }.getOrDefault(v)
                s.startsWith("[") -> runCatching { JSONArray(s) }.getOrDefault(v)
                else -> v
            }
        }
        else -> v
    }

    private fun safe(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(60).ifBlank { "_" }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun t(ctx: Context, ru: String, en: String) = if (Locale.getDefault().language == "ru") ru else en

    private val IMG = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "bmp")
    private val VID = setOf("mp4", "webm", "mkv", "3gp", "mov")
    private val AUD = setOf("m4a", "mp3", "ogg", "opus", "aac", "wav")

    private fun fileHtml(name: String, rel: String?): String {
        if (rel == null) return "📎 " + esc(name)
        val ext = name.substringAfterLast('.', "").lowercase()
        val src = esc(rel)
        return when (ext) {
            in IMG -> "<a href=\"$src\"><img src=\"$src\" loading=lazy></a>"
            in VID -> "<video src=\"$src\" controls preload=metadata></video>"
            in AUD -> "<audio src=\"$src\" controls preload=none></audio>"
            else -> "📎 <a href=\"$src\">" + esc(name) + "</a>"
        }
    }

    private fun bubble(out: Boolean, who: String, ts: Long, inner: String) =
        "<div class=\"m ${if (out) "o" else "i"}\"><b>" + esc(who) + "</b>" + inner + "<i>" + time.format(Date(ts)) + "</i></div>\n"

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>
body{margin:0;font:15px/1.45 system-ui,sans-serif;background:#0f0d1a;color:#ece9ff;padding:16px;max-width:760px;margin:auto}
h1{font-size:22px;background:linear-gradient(90deg,#a78bfa,#38bdf8);-webkit-background-clip:text;color:transparent}
a{color:#93c5fd}ul{padding-left:18px}li{margin:6px 0}small{color:#8b85a8}
.m{max-width:80%;margin:6px 0;padding:8px 12px;border-radius:16px;background:#221d3a;word-wrap:break-word;clear:both}
.m.o{margin-left:auto;background:linear-gradient(135deg,#6d5df5,#4f46e5)}.m b{display:block;font-size:12px;opacity:.75}
.m i{display:block;font-size:11px;opacity:.55;text-align:right;font-style:normal}.q{border-left:3px solid #a78bfa;padding-left:8px;opacity:.8;font-size:13px;margin:2px 0 4px}
img,video{max-width:100%;border-radius:12px;display:block;margin:4px 0}audio{width:100%}
</style></head><body><h1>${esc(title)}</h1>$body</body></html>"""
}
