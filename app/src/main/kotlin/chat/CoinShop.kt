// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.games.CoinSound
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.DialogTracker
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Покупка монет за рубли через ЮMoney (кошелёк физлица, форма Quickpay).
 *
 * Как устроено (сервер - папка `server/coins/` в исходниках):
 *  1. [paymentUrl] строит ссылку оплаты под выбранный пак; в `label` уходит СЕКРЕТНЫЙ ID
 *     покупок игрока (64 hex-символа, создаётся один раз и хранится в базе [PurchaseDb]).
 *  2. После оплаты ЮMoney присылает уведомление на сервер, сервер проверяет подпись sha1,
 *     сумму и повторы и выпускает чек, подписанный ECDSA P-256.
 *  3. Приложение забирает чеки ([sync]), проверяет подпись открытым ключом [serverKey],
 *     ID, соответствие суммы паку и уникальность - и только тогда зачисляет монеты.
 *
 * База `coin_purchases.db` лежит в закрытой папке приложения (databases/), каждый чек в ней
 * хранится вместе с подписью сервера и проверяется заново при чтении - вписать «свой» чек
 * в базу нельзя. Файл базы входит в экспорт профиля ([com.stoyko.smessenger.profile.FullBackup]
 * переносит всю папку databases/), поэтому покупки и ID переезжают вместе с профилем.
 */
object CoinShop {
    private const val TAG = "CoinShop"

    // ---------------- настройки (заполнить после запуска сервера, см. server/coins/README.md) ----

    // Можно вписать при сборке - или (удобнее) задать в update.json, блок "coinShop":
    //   "coinShop": { "wallet": "41001…", "server": "https://…", "publicKey": "MFkw…" }
    // Тогда магазин включается у всех без новой сборки приложения (см. server/README.md).
    // Рабочие настройки по умолчанию: магазин работает сразу, даже без сети к GitHub.
    private const val BUILD_WALLET = "4100119100813867"
    private const val BUILD_SERVER = "https://coins-server.andrejstojko66.workers.dev"
    private const val BUILD_KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEvsKrTQJI9WuKFroOlRuR+hyGvfDxKr+vVXEOiO2DflkRXeVctXW1ChMyxLv9Gl0XMSbVcV81mMboEkI9rLyNUA=="

    /** Номер кошелька ЮMoney (41001…). */
    @Volatile
    var wallet = BUILD_WALLET
        private set

    /** Адрес сервера монет, без «/» на конце, например https://coins.example.workers.dev */
    @Volatile
    var serverUrl = BUILD_SERVER
        private set

    /** Открытый ключ подписи чеков (base64 DER). */
    @Volatile
    var serverKey = BUILD_KEY
        private set

    private fun cfgPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("coin_shop_cfg", Context.MODE_PRIVATE)

    /** Подхватить настройки, полученные из update.json. */
    fun load(ctx: Context) {
        val p = cfgPrefs(ctx)
        wallet = p.getString("wallet", null)?.takeIf { it.isNotBlank() } ?: BUILD_WALLET
        serverUrl = (p.getString("server", null)?.takeIf { it.startsWith("https://") } ?: BUILD_SERVER).trimEnd('/')
        // Ключ из update.json берём, только если это настоящий ключ P-256 (опечатка в одной букве
        // ломает проверку всех чеков - тогда остаётся встроенный рабочий ключ).
        serverKey = p.getString("key", null)?.takeIf { it.length > 60 && validKey(it) } ?: BUILD_KEY
    }

    private fun validKey(k: String): Boolean = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(k, Base64.DEFAULT)))
        true
    }.getOrDefault(false)

    /** Сохранить блок coinShop из текста update.json (вызывается и проверкой обновлений). */
    fun applyJson(ctx: Context, text: String) {
        val o = org.json.JSONObject(text).optJSONObject("coinShop") ?: return
        val wallet = o.optString("wallet").filter { it.isDigit() }
        val server = o.optString("server").trim()
        val key = o.optString("publicKey").trim()
        cfgPrefs(ctx).edit().putLong("at", System.currentTimeMillis())
            .putString("wallet", wallet.takeIf { it.length >= 11 })
            .putString("server", server.takeIf { it.startsWith("https://") })
            .putString("key", key.takeIf { validKey(it) })
            .apply()
        load(ctx)
    }

    /**
     * Свежие настройки магазина из update.json (тот же файл, что и обновления). Раз в 3 часа
     * или по [force]; [done] - в главном потоке.
     */
    fun refreshRemote(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        load(app)
        val p = cfgPrefs(app)
        if (!force && System.currentTimeMillis() - p.getLong("at", 0L) < 3 * 3600_000L) {
            done?.let { main.post(it) }
            return
        }
        Thread {
            // Свежий update.json - из последнего релиза на GitHub (запасные пути - в Updater).
            runCatching { applyJson(app, com.stoyko.smessenger.update.Updater.latestJsonText(app)) }
                .onFailure { Log.w(TAG, "config: $it") }
            load(app)
            done?.let { main.post(it) }
        }.start()
    }

    /** Сбор «на жизнь проекта». */
    const val DONATE_URL = "https://yoomoney.ru/fundraise/1KPEOB5LG3Q.261008"

    fun configured() = wallet.length >= 11 && serverUrl.startsWith("https://") && serverKey.length > 60

    /** Можно хотя бы принимать донаты (кошелёк известен). */
    fun canDonate() = wallet.length >= 11

    // ---------------- паки ----------------

    /** Пак монет: [rub] рублей → [coins] монет. Те же значения - в server/coins/config.py. */
    class Pack(val id: String, val emoji: String, val title: Int, val rub: Int, val coins: Int)

    val PACKS = listOf(
        Pack("starter", "🌱", R.string.shop_pack_starter, 99, 250),
        Pack("handful", "🤲", R.string.shop_pack_handful, 150, 300),
        Pack("bag", "💰", R.string.shop_pack_bag, 300, 600),
        Pack("chest", "🧰", R.string.shop_pack_chest, 500, 1500),
        Pack("treasure", "👑", R.string.shop_pack_treasure, 1000, 3500),
    )

    /**
     * «Фишки для Блэкджека»: те же монеты (фишки за столом = монеты), отдельные паки для тех,
     * кто держит стол дилера или играет по-крупному. Цены не совпадают с обычными паками -
     * сервер узнаёт пак по точной сумме (server/coins/config.py, coins-worker/worker.js).
     * Показываются, только когда сервер подтвердил, что знает эти паки ([chipPacksReady]).
     */
    val CHIP_PACKS = listOf(
        Pack("bj_stack", "🎰", R.string.bjack_pack_stack, 249, 700),
        Pack("bj_case", "💼", R.string.bjack_pack_case, 749, 2400),
        Pack("bj_highroller", "🎩", R.string.bjack_pack_high, 1490, 5500),
    )

    fun pack(id: String) = PACKS.firstOrNull { it.id == id } ?: CHIP_PACKS.firstOrNull { it.id == id }

    /** Пак по сумме в рублях (для проверки чека). */
    fun packForRub(rub: Int) = PACKS.firstOrNull { it.rub == rub } ?: CHIP_PACKS.firstOrNull { it.rub == rub }

    /** Сервер знает паки фишек (иначе оплата не нашла бы пак - их не показываем). */
    fun chipPacksReady(ctx: Context): Boolean = cfgPrefs(ctx).getBoolean("bj_packs", false)

    /**
     * Спросить сервер, какие паки он принимает (`GET /api/packs`), и запомнить, есть ли среди
     * них паки фишек с теми же ценами. [done] - в главном потоке: true - паки фишек доступны.
     */
    fun refreshPacks(ctx: Context, done: ((Boolean) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!configured()) load(app)
        Thread {
            var ok: Boolean? = null
            if (configured()) {
                val routes = if (com.stoyko.smessenger.vpn.VpnState.proxyReady) listOf(true, false) else listOf(false)
                for (viaProxy in routes) {
                    val r = runCatching { httpGet("$serverUrl/api/packs", viaProxy) }.getOrElse { Http(0, null) }
                    if (r.code == 0) continue
                    // 200 - список паков; 404 - старый сервер без паков фишек. Остальное (5xx, 429,
                    // страница портала Wi-Fi) - ответ не окончательный, оставляем прежний.
                    if (r.code == 200 && r.body != null) {
                        ok = runCatching {
                            val packs = org.json.JSONObject(r.body).getJSONObject("packs")
                            CHIP_PACKS.all { p ->
                                val a = packs.optJSONArray(p.id)
                                a != null && a.optInt(0) == p.rub && a.optInt(1) == p.coins
                            }
                        }.getOrNull()
                    } else if (r.code == 404) {
                        ok = false
                    }
                    break
                }
            }
            // Нет сети - оставляем прежний ответ сервера.
            ok?.let { cfgPrefs(app).edit().putBoolean("bj_packs", it).apply() }
            val res = chipPacksReady(app)
            done?.let { main.post { it(res) } }
        }.start()
    }

    /**
     * Ссылка на оплату пака [packName] для игрока [userId] (его секретный ID покупок).
     * [method]: "AC" - банковская карта, "PC" - кошелёк ЮMoney.
     */
    fun paymentUrl(userId: String, packName: String, method: String = "AC", title: String = "S-messenger"): String? {
        val p = pack(packName) ?: return null
        if (!ID_RE.matches(userId)) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = linkedMapOf(
            "receiver" to wallet,
            "quickpay-form" to "button",
            "paymentType" to if (method == "PC") "PC" else "AC",
            "sum" to p.rub.toString(),
            "label" to userId.uppercase(),
            "targets" to "$title: ${p.coins} монет",
            "successURL" to "$serverUrl/paid",
        )
        return "https://yoomoney.ru/quickpay/confirm?" + params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }

    private val ID_RE = Regex("^[0-9A-Fa-f]{64}$")

    // ---------------- защищённая база покупок ----------------

    /**
     * SQLite `coin_purchases.db`:
     *  - `users(user_id, owner_pk, coins_balance)` - секретный ID покупок аккаунта и сколько
     *    монет всего куплено (сумма проверенных чеков);
     *  - `receipts(...)` - каждый зачисленный чек с подписью сервера.
     */
    private class PurchaseDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "coin_purchases.db", null, 1) {
        init {
            // Без журнала WAL - файл базы всегда целый, экспорт профиля копирует его как есть.
            setWriteAheadLoggingEnabled(false)
        }

        override fun onCreate(db: SQLiteDatabase) {
            // Аккаунт → его секретный ID покупок и куплено монет.
            db.execSQL("CREATE TABLE users (user_id TEXT PRIMARY KEY, owner_pk TEXT NOT NULL UNIQUE, coins_balance INTEGER NOT NULL DEFAULT 0)")
            // Зачисленные чеки: номер чека и номер платежа уникальны - дважды не зачислить.
            db.execSQL(
                "CREATE TABLE receipts (receipt_id TEXT PRIMARY KEY, user_id TEXT NOT NULL, operation_id TEXT NOT NULL UNIQUE, " +
                    "coins INTEGER NOT NULL, rub INTEGER NOT NULL, created INTEGER NOT NULL, signature TEXT NOT NULL, applied INTEGER NOT NULL DEFAULT 0)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Volatile
    private var helper: PurchaseDb? = null

    private fun db(ctx: Context): SQLiteDatabase = (helper ?: synchronized(this) { helper ?: PurchaseDb(ctx).also { helper = it } }).writableDatabase

    /** Профиль заменили (импорт) - базу открыть заново. */
    fun reset() {
        synchronized(this) {
            runCatching { helper?.close() }
            helper = null
        }
    }

    private fun myPk(ctx: Context): String? {
        val tox = runCatching { (ctx.applicationContext as App).component.tox() }.getOrNull() ?: return null
        if (!runCatching { tox.started }.getOrDefault(false)) return null
        return runCatching { tox.publicKey.string().take(64).uppercase() }.getOrNull()?.takeIf { ID_RE.matches(it) }
    }

    /** Секретный ID покупок текущего аккаунта (создаётся при первом обращении). */
    @Synchronized
    fun userId(ctx: Context): String? {
        val pk = myPk(ctx) ?: return null
        val d = db(ctx)
        d.rawQuery("SELECT user_id FROM users WHERE owner_pk=?", arrayOf(pk)).use { c -> if (c.moveToFirst()) return c.getString(0) }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02X".format(it) }
        d.insert("users", null, ContentValues().apply {
            put("user_id", id)
            put("owner_pk", pk)
            put("coins_balance", 0)
        })
        return id
    }

    /** Сколько монет куплено всего (по проверенным чекам). */
    fun purchased(ctx: Context): Int {
        val id = runCatching { userId(ctx) }.getOrNull() ?: return 0
        var sum = 0L
        runCatching {
            db(ctx).rawQuery("SELECT receipt_id, user_id, coins, rub, operation_id, created, signature FROM receipts WHERE user_id=? AND applied=1", arrayOf(id)).use { c ->
                while (c.moveToNext()) {
                    val r = Receipt(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getLong(5), c.getString(6))
                    // Проверяем подпись заново: «вписанный руками» чек не засчитывается.
                    if (verify(r) == null) sum += r.coins
                }
            }
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ---------------- чеки ----------------

    class Receipt(val id: String, val userId: String, val coins: Int, val rub: Int, val operationId: String, val created: Long, val signature: String) {
        /** Ровно та строка, которую подписывает сервер (server.py, sign_receipt). */
        fun payload() = "smc1|$id|$userId|$coins|$rub|$operationId|$created"
    }

    @Volatile
    private var keyCache: Pair<String, java.security.PublicKey>? = null

    private val publicKey: java.security.PublicKey?
        get() {
            val k = serverKey
            keyCache?.let { if (it.first == k) return it.second }
            return runCatching {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(k, Base64.DEFAULT)))
            }.getOrNull()?.also { keyCache = k to it }
        }

    /** null - чек настоящий и правильный; иначе - причина отказа. */
    fun verify(r: Receipt, expectedUser: String? = null): String? {
        val key = publicKey ?: return "no key"
        if (!Regex("^[0-9a-f]{32}$").matches(r.id)) return "bad id"
        if (expectedUser != null && !r.userId.equals(expectedUser, ignoreCase = true)) return "not mine"
        // Математика: монеты должны точно соответствовать паку за эту сумму.
        val p = packForRub(r.rub) ?: return "unknown sum"
        if (p.coins != r.coins || r.coins <= 0) return "wrong coins"
        val ok = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(r.payload().toByteArray(Charsets.UTF_8))
                verify(Base64.decode(r.signature, Base64.DEFAULT))
            }
        }.getOrDefault(false)
        return if (ok) null else "bad signature"
    }

    private val main = Handler(Looper.getMainLooper())

    /** Результат [sync]: монет зачислено (≥ 0) или одна из ошибок. */
    const val SYNC_NET_ERROR = -1
    const val SYNC_SERVER_ERROR = -2
    const val SYNC_NOT_READY = -3

    private val waiters = ArrayList<(Int) -> Unit>()
    private var syncing = false

    /**
     * Забрать чеки с сервера и зачислить новые. [done] (в главном потоке) получает, сколько
     * монет зачислено сейчас (0 - ничего нового) или [SYNC_NET_ERROR] / [SYNC_SERVER_ERROR] /
     * [SYNC_NOT_READY]. Если проверка уже идёт - [done] получит её результат (а не «ничего»).
     */
    fun sync(ctx: Context, done: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        if (!configured()) load(app)
        if (!configured()) {
            done?.let { cb -> main.post { cb(SYNC_NET_ERROR) } }
            return
        }
        synchronized(waiters) {
            done?.let { waiters += it }
            if (syncing) return
            syncing = true
        }
        Thread {
            var result = SYNC_NET_ERROR
            try {
                // Секретный ID: из базы (нужна запущенная сеть Tox) или сохранённый при оплате -
                // тогда чек заберётся и в фоне, когда приложение только что поднято будильником.
                val id = runCatching { userId(app) }.getOrNull() ?: pending(app)?.userId
                result = if (id == null) SYNC_NOT_READY else fetchAndApply(app, id)
            } catch (e: Exception) {
                Log.w(TAG, "sync: $e")
            }
            // Ожидание закончено, когда пришёл чек, выписанный после перехода к оплате.
            if (result > 0) pending(app)?.let { p -> if (paidSince(app, p.userId, p.at / 1000 - 300, p.pack.rub)) clearPending(app) }
            val cbs = synchronized(waiters) {
                syncing = false
                waiters.toList().also { waiters.clear() }
            }
            main.post { cbs.forEach { runCatching { it(result) } } }
        }.start()
    }

    /** Ответ сервера: код и текст (код 0 - сеть недоступна). */
    private class Http(val code: Int, val body: String?)

    private fun httpGet(url: String, viaProxy: Boolean): Http {
        val proxy = if (viaProxy) com.stoyko.smessenger.vpn.VpnState.httpProxy() else java.net.Proxy.NO_PROXY
        val conn = URL(url).openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 12_000
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val code = conn.responseCode
            Http(code, if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "http (${if (viaProxy) "vpn" else "direct"}): $e")
            Http(0, null)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Чеки игрока. Пути по очереди: через прокси VPN приложения (если он включён) и напрямую;
     * сервер ответил 5xx/429 - ещё одна попытка через секунду. null + код - не получилось.
     */
    private fun fetchReceipts(id: String): Pair<String?, Int> {
        val url = "$serverUrl/api/receipts?user_id=$id"
        val routes = if (com.stoyko.smessenger.vpn.VpnState.proxyReady) listOf(true, false) else listOf(false)
        var last = 0
        for (viaProxy in routes) {
            for (attempt in 0..1) {
                val r = runCatching { httpGet(url, viaProxy) }.getOrElse { Http(0, null) }
                if (r.code == 200 && r.body != null) return r.body to 200
                // Сеть недоступна этим путём - сразу следующий путь (без долгих повторов).
                if (r.code == 0) break
                last = r.code
                // 4xx (кроме 429) - ответ окончательный, повторять бессмысленно.
                if (r.code in 400..499 && r.code != 429) return null to r.code
                if (attempt == 0) Thread.sleep(1_000)
            }
        }
        return null to last
    }

    private fun fetchAndApply(ctx: Context, id: String): Int {
        val (body, code) = fetchReceipts(id)
        if (body == null) return if (code == 0) SYNC_NET_ERROR else SYNC_SERVER_ERROR
        val arr = runCatching { org.json.JSONObject(body).optJSONArray("receipts") }.getOrElse {
            // Вместо JSON пришла страница (портал Wi-Fi, ошибка прокси) - это не «нет чеков».
            Log.w(TAG, "bad json: $it")
            return SYNC_SERVER_ERROR
        } ?: return 0
        val d = db(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = Receipt(
                o.optString("receipt_id"), o.optString("user_id"), o.optInt("coins"), o.optInt("rub"),
                o.optString("operation_id"), o.optLong("created"), o.optString("signature"),
            )
            val why = verify(r, id)
            if (why != null) {
                Log.w(TAG, "receipt ${r.id.take(8)} rejected: $why")
                continue
            }
            // Сначала чек сохраняется в базе (applied=0), потом - зачисление.
            d.insertWithOnConflict("receipts", null, ContentValues().apply {
                put("receipt_id", r.id)
                put("user_id", r.userId.uppercase())
                put("operation_id", r.operationId)
                put("coins", r.coins)
                put("rub", r.rub)
                put("created", r.created)
                put("signature", r.signature)
                put("applied", 0)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
        return applyPending(ctx, id)
    }

    /**
     * Зачислить все сохранённые, но ещё не зачисленные чеки (в т.ч. оставшиеся после сбоя).
     * Порядок защищает от двойного зачисления и от потери монет:
     *  1. [Coins.creditReceipt] - баланс и номер чека записываются одним commit();
     *     если чек уже зачислен (сбой случился после шага 1) - монеты не добавляются;
     *  2. в базе чек отмечается applied=1 вместе с суммой куплено - одной транзакцией.
     */
    @Synchronized
    private fun applyPending(ctx: Context, id: String): Int {
        val d = db(ctx)
        val list = ArrayList<Receipt>()
        d.rawQuery("SELECT receipt_id, user_id, coins, rub, operation_id, created, signature FROM receipts WHERE user_id=? AND applied=0", arrayOf(id)).use { c ->
            while (c.moveToNext()) list += Receipt(c.getString(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getLong(5), c.getString(6))
        }
        var total = 0
        for (r in list) {
            if (verify(r, id) != null) continue
            if (Coins.creditReceipt(ctx, r.id, r.coins)) total += r.coins
            d.beginTransaction()
            try {
                val n = d.update("receipts", ContentValues().apply { put("applied", 1) }, "receipt_id=? AND applied=0", arrayOf(r.id))
                if (n == 1) d.execSQL("UPDATE users SET coins_balance = coins_balance + ? WHERE user_id=?", arrayOf<Any>(r.coins, id))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        return total
    }

    /**
     * Есть ли зачисленный чек игрока [id], выписанный не раньше [sinceSec] (секунды) или
     * на сумму ожидаемого пака [rub] (если часы телефона сбиты).
     */
    private fun paidSince(ctx: Context, id: String, sinceSec: Long, rub: Int): Boolean = runCatching {
        db(ctx).rawQuery(
            "SELECT 1 FROM receipts WHERE user_id=? AND applied=1 AND (created>=? OR rub=?) LIMIT 1",
            arrayOf(id, sinceSec.toString(), rub.toString()),
        ).use { it.moveToFirst() }
    }.getOrDefault(true)

    // ---------------- ожидание оплаты (переживает закрытие и выгрузку приложения) ----------------

    /** Окно частых проверок после перехода к оплате. */
    private const val POLL_WINDOW = 30 * 60_000L

    /** Сколько помнить незавершённую покупку (кнопка «Не пришло? Проверить ещё раз»). */
    private const val PENDING_TTL = 24 * 3600_000L

    /** Первые проверки (секунды от начала ожидания), дальше - раз в минуту, после 10 минут - раз в 2. */
    private val FIRST_CHECKS = longArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 150, 180, 240, 300)

    /** Покупка, которую ждём: пак, когда открыли оплату, и секретный ID (для проверки в фоне). */
    class Pending(val pack: Pack, val at: Long, val userId: String)

    private fun pendPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("coin_shop_pending", Context.MODE_PRIVATE)

    fun pending(ctx: Context): Pending? {
        val p = pendPrefs(ctx)
        val pk = pack(p.getString("pack", null) ?: return null) ?: return null
        val uid = p.getString("uid", null)?.takeIf { ID_RE.matches(it) } ?: return null
        val at = p.getLong("at", 0L)
        if (System.currentTimeMillis() - at !in 0..PENDING_TTL) {
            clearPending(ctx)
            return null
        }
        return Pending(pk, at, uid)
    }

    private fun setPending(ctx: Context, p: Pack, uid: String) {
        // commit: состояние должно пережить выгрузку процесса, пока открыт браузер с оплатой.
        pendPrefs(ctx).edit().putString("pack", p.id).putString("uid", uid).putLong("at", System.currentTimeMillis()).commit()
    }

    private fun clearPending(ctx: Context) {
        pendPrefs(ctx).edit().clear().commit()
        cancelAlarm(ctx)
    }

    /** Когда (мс от начала ожидания) следующая проверка; null - окно частых проверок закончилось. */
    private fun nextCheckAt(elapsed: Long): Long? {
        for (s in FIRST_CHECKS) if (s * 1000 > elapsed) return s * 1000
        val step = if (elapsed < 10 * 60_000L) 60_000L else 120_000L
        val next = (elapsed / step + 1) * step
        return if (next <= POLL_WINDOW) next else null
    }

    @Volatile
    private var watchGen = 0

    /** Открытый экран магазина: получает итог каждой проверки (только в главном потоке). */
    private var uiListener: ((Int) -> Unit)? = null

    /** Итог проверки - экрану магазина, если он открыт; иначе - [otherwise]. */
    private fun deliver(n: Int, otherwise: (Int) -> Unit) {
        val l = uiListener
        runCatching { if (l != null) l(n) else otherwise(n) }
    }

    /**
     * Проверять чеки по нарастающему интервалу (0, 5, 10, 15… секунд, до 30 минут от оплаты),
     * пока ждём покупку. Итоги - открытому магазину, а без него зачисление показывается
     * уведомлением. Повторный вызов заменяет прежний цикл; отсчёт идёт от момента оплаты,
     * поэтому после перезапуска приложения не сбрасывается.
     */
    fun watchPending(ctx: Context, immediate: Boolean = true) {
        val app = ctx.applicationContext
        val gen = ++watchGen
        lateinit var tick: () -> Unit
        fun later() {
            val p = pending(app) ?: return
            val el = System.currentTimeMillis() - p.at
            val next = nextCheckAt(el) ?: return
            main.postDelayed({ tick() }, (next - el).coerceAtLeast(3_000L))
        }
        tick = {
            if (gen == watchGen && pending(app) != null) {
                sync(app) { n ->
                    deliver(n) { if (it > 0) notifyCredited(app, it) }
                    if (gen == watchGen) later()
                }
            }
        }
        if (immediate) tick() else later()
    }

    /** Совместимость: после покупки - ждать чек по нарастающему интервалу. */
    @Suppress("UNUSED_PARAMETER")
    fun pollAfterPurchase(ctx: Context, onCredited: (Int) -> Unit) = watchPending(ctx)

    // ---- проверка в фоне по будильнику (приложение закрыто/выгружено) ----

    const val ACTION_POLL = "com.stoyko.smessenger.COINS_POLL"

    private fun alarmIntent(ctx: Context): android.app.PendingIntent = android.app.PendingIntent.getBroadcast(
        ctx,
        0x5E23,
        Intent(ctx, com.stoyko.smessenger.update.UpdateReceiver::class.java).setAction(ACTION_POLL),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    /** Будильник на следующую проверку (не чаще раза в минуту - система всё равно сдвигает). */
    fun scheduleAlarm(ctx: Context) {
        val app = ctx.applicationContext
        val p = pending(app) ?: return cancelAlarm(app)
        val el = System.currentTimeMillis() - p.at
        val next = nextCheckAt(el.coerceAtLeast(0)) ?: return cancelAlarm(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        val at = android.os.SystemClock.elapsedRealtime() + (next - el).coerceAtLeast(60_000L)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app))
            } else {
                am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(app))
            }
        }.onFailure { Log.w(TAG, "alarm: $it") }
    }

    private fun cancelAlarm(ctx: Context) {
        val am = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
        runCatching { am.cancel(alarmIntent(ctx.applicationContext)) }
    }

    /** Сработал будильник (или перезагрузка): проверить чеки, зачислилось - уведомление. */
    fun backgroundCheck(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        load(app)
        if (pending(app) == null) {
            cancelAlarm(app)
            done()
            return
        }
        sync(app) { n ->
            if (n > 0) notifyCredited(app, n) else scheduleAlarm(app)
            done()
        }
    }

    /** Уведомление «Зачислено +N 🪙» (нажатие - открыть приложение, там - праздничный «+N»). */
    private fun notifyCredited(ctx: Context, n: Int) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "coins_shop"
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(android.app.NotificationChannel(ch, ctx.getString(R.string.shop_title), android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val b = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(ctx.getString(R.string.shop3_notify_title, n))
                .setContentText(ctx.getString(R.string.shop3_notify_text, Coins.balance(ctx)))
                .setAutoCancel(true)
            if (launch != null) {
                b.setContentIntent(
                    android.app.PendingIntent.getActivity(
                        ctx, 0x5E24, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            nm.notify(0x5E25, b.build())
        }.onFailure { Log.w(TAG, "notify: $it") }
    }

    /**
     * Проверка при каждом возврате в приложение: забрать чеки; всё, что зачислено (сейчас или
     * раньше в фоне, но ещё не показано), - вернуть в [done] одной суммой для «+N».
     */
    fun onResume(ctx: Context, done: (Int) -> Unit) {
        val app = ctx.applicationContext
        sync(app) { n ->
            deliver(n) {
                // Зачисленное в фоне (по будильнику) тоже показываем - один раз.
                val u = Coins.takeUnseenPurchase(app)
                if (u > 0) done(u)
            }
            if (pending(app) != null) {
                scheduleAlarm(app)
                watchPending(app, immediate = false)
            }
        }
    }

    // ---------------- экран магазина ----------------

    fun show(ctx: Context, focus: String? = null) {
        load(ctx)
        // Магазин работает сразу со встроенными/сохранёнными настройками, а свежий update.json
        // подгружается в фоне.
        refreshRemote(ctx, force = true)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val frame = FrameLayout(ctx).apply { setBackgroundColor(0xFF120E2E.toInt()) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(android.widget.ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            setText(R.string.shop_title)
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(34), dp(34)))
        val wallet = TextView(ctx).apply {
            textSize = 19f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        col.addView(bar)

        col.addView(TextView(ctx).apply {
            setText(R.string.shop_sub)
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(6))
        })
        val status = TextView(ctx).apply {
            textSize = 13.5f
            setTextColor(0xFFFDE68A.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        var method = "AC"
        val methodRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        fun select(sel: TextView) {
            for (b in chips) {
                val on = b === sel
                b.background = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(if (on) 0x55FDE047 else 0x22FFFFFF)
                    setStroke(dp(1), if (on) 0xFFFDE047.toInt() else 0x33FFFFFF)
                }
            }
        }
        fun chip(textRes: Int, m: String): TextView = TextView(ctx).apply {
            setText(textRes)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { v ->
                method = m
                select(v as TextView)
            }
        }.also { chips += it }
        val bCard = chip(R.string.shop_method_card, "AC")
        val bWallet = chip(R.string.shop_method_wallet, "PC")
        methodRow.addView(bCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        methodRow.addView(bWallet, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        col.addView(methodRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        select(bCard)

        val palettes = listOf(
            intArrayOf(0xFFA3E635.toInt(), 0xFF15803D.toInt()),
            intArrayOf(0xFF34D399.toInt(), 0xFF047857.toInt()),
            intArrayOf(0xFF60A5FA.toInt(), 0xFF4338CA.toInt()),
            intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt(), 0xFFB45309.toInt()),
            intArrayOf(0xFFF0ABFC.toInt(), 0xFF9333EA.toInt(), 0xFF4C1D95.toInt()),
        )
        // Выгода пака относительно «Горсти» (2 монеты за рубль).
        fun bonus(p: Pack): Int = ((p.coins.toDouble() / p.rub / 2.0 - 1) * 100).toInt()
        fun refresh() {
            wallet.text = Coins.balance(ctx).toString()
        }

        // Нажали пак и открыли оплату - экран переходит в «ожидаем оплату» (задаётся ниже).
        var afterBuy: () -> Unit = {}

        // ⚡ Эксклюзив на время - сверху, над паками.
        val limBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(limBox)
        fun fillLimited() {
            limBox.removeAllViews()
            ShopExtras.buildLimited(ctx, limBox) {
                refresh()
                fillLimited()
            }
        }
        fillLimited()
        PACKS.forEachIndexed { i, p ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, palettes[i % palettes.size]).apply { cornerRadius = 20 * d }
                elevation = 4 * d
            }
            // Живой значок: руки, мешок, сундук, корона - каждый двигается по-своему.
            card.addView(ShopExtras.PackIcon(ctx, p.emoji, i), LinearLayout.LayoutParams(dp(62), dp(62)))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
            }
            texts.addView(TextView(ctx).apply {
                setText(p.title)
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            texts.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.shop_pack_coins, p.coins) +
                    (if (bonus(p) > 0) "  ·  " + ctx.getString(R.string.shop_bonus, bonus(p)) else "") +
                    (if (p.id == "chest") "  ·  " + ctx.getString(R.string.shop_hit) else "")
                textSize = 14f
                setTextColor(0xF0FFFFFF.toInt())
            })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(Btn.nice(ctx, ctx.getString(R.string.shop_price, p.rub), intArrayOf(0xFFFFFFFF.toInt(), 0xFFE5E7EB.toInt())) {
                if (buy(ctx, p, method, status)) afterBuy()
            }.apply { setTextColor(0xFF1F1147.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
            col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            if (com.stoyko.smessenger.ui.Kinetics.enabled) {
                card.alpha = 0f
                card.translationY = 40 * d
                card.animate().alpha(1f).translationY(0f).setStartDelay(80L + i * 90L).setDuration(380).setInterpolator(DecelerateInterpolator()).start()
            }
        }

        // ♠ Фишки для Блэкджека - отдельные паки (появляются, когда сервер их подтвердил).
        val chipBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(chipBox)
        fun fillChips(ready: Boolean) {
            chipBox.removeAllViews()
            if (!ready) return
            chipBox.addView(TextView(ctx).apply {
                setText(R.string.bjack_shop_title)
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(4), dp(18), dp(4), dp(2))
            })
            chipBox.addView(TextView(ctx).apply {
                setText(R.string.bjack_shop_sub)
                textSize = 13f
                setTextColor(0xCCFFFFFF.toInt())
                setPadding(dp(4), 0, dp(4), dp(2))
            })
            val felt = listOf(
                intArrayOf(0xFF15803D.toInt(), 0xFF064E3B.toInt()),
                intArrayOf(0xFF166534.toInt(), 0xFF052E16.toInt(), 0xFF422006.toInt()),
                intArrayOf(0xFF7C2D12.toInt(), 0xFF1C1917.toInt(), 0xFF000000.toInt()),
            )
            CHIP_PACKS.forEachIndexed { i, p ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(14), dp(12))
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, felt[i % felt.size]).apply {
                        cornerRadius = 20 * d
                        setStroke(dp(2), 0xAAFDE68A.toInt())
                    }
                    elevation = 4 * d
                }
                card.addView(com.stoyko.smessenger.games.blackjack.BjChips.StackIcon(ctx, i), LinearLayout.LayoutParams(dp(66), dp(62)))
                val texts = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), 0, dp(6), 0)
                }
                texts.addView(TextView(ctx).apply {
                    setText(p.title)
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT_BOLD)
                })
                texts.addView(TextView(ctx).apply {
                    text = ctx.getString(R.string.bjack_pack_chips, p.coins) +
                        (if (bonus(p) > 0) "  ·  " + ctx.getString(R.string.shop_bonus, bonus(p)) else "")
                    textSize = 13.5f
                    setTextColor(0xF0FDE68A.toInt())
                })
                card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(Btn.nice(ctx, ctx.getString(R.string.shop_price, p.rub), intArrayOf(0xFFFFF1A8.toInt(), 0xFFF5B82E.toInt())) {
                    if (buy(ctx, p, method, status)) afterBuy()
                }.apply { setTextColor(0xFF3B2600.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
                chipBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            }
        }
        fillChips(chipPacksReady(ctx))

        // Статус покупки: «ожидаем оплату…» с крутилкой, «зачислено +N», ошибки сети/сервера;
        // ниже - «Не пришло? Проверить ещё раз» и «Восстановить покупки».
        val statusCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(0x1AFFFFFF)
                setStroke(dp(1), 0x33FDE68A)
            }
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val spinner = android.widget.ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFDE047.toInt())
            visibility = android.view.View.GONE
        }
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        statusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statusCard.addView(statusRow)
        val checkBtn = Btn.tonal(ctx, ctx.getString(R.string.shop_check)) {}
        val restoreBtn = Btn.tonal(ctx, ctx.getString(R.string.shop3_restore)) {}
        for (b in listOf(checkBtn, restoreBtn)) {
            b.maxLines = 2
            b.ellipsize = android.text.TextUtils.TruncateAt.END
            b.textSize = 13f
        }
        statusCard.addView(
            Btn.row(ctx, listOf(checkBtn, restoreBtn), height = 50, gap = 8),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        col.addView(statusCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        /** Текст статуса по ожидаемой покупке (или пусто, если ничего не ждём). */
        fun showPending() {
            val p = pending(ctx)
            checkBtn.text = ctx.getString(if (p != null) R.string.shop3_check_again else R.string.shop_check)
            if (p == null) {
                spinner.visibility = android.view.View.GONE
                return
            }
            val inWindow = System.currentTimeMillis() - p.at < POLL_WINDOW
            spinner.visibility = if (inWindow) android.view.View.VISIBLE else android.view.View.GONE
            status.text = if (inWindow) {
                ctx.getString(R.string.shop3_waiting, ctx.getString(p.pack.title), p.pack.coins)
            } else {
                ctx.getString(R.string.shop3_waiting_long, ctx.getString(p.pack.title))
            }
        }

        /** Итог проверки чеков на экране. [manual] - нажали кнопку (тогда и «ничего нового»). */
        fun showResult(n: Int, manual: Boolean) {
            // Всё зачисленное (сейчас или раньше в фоне) - одним «+N».
            val u = Coins.takeUnseenPurchase(ctx)
            refresh()
            if (u > 0) {
                spinner.visibility = android.view.View.GONE
                checkBtn.text = ctx.getString(R.string.shop_check)
                status.text = ctx.getString(R.string.shop3_credited, u)
                celebrate(ctx, u)
                return
            }
            val waiting = pending(ctx) != null
            when {
                n == SYNC_NET_ERROR && !configured() -> status.setText(R.string.shop_not_configured)
                n == SYNC_NET_ERROR -> status.setText(if (waiting) R.string.shop3_net_retry else R.string.shop_net_error)
                n == SYNC_SERVER_ERROR -> status.setText(R.string.shop3_server_error)
                n == SYNC_NOT_READY -> status.setText(R.string.shop_no_profile)
                waiting -> showPending()
                manual -> status.setText(R.string.shop_nothing)
            }
            if (n < 0 && waiting) spinner.visibility = android.view.View.VISIBLE
        }
        afterBuy = {
            showPending()
            watchPending(ctx)
            scheduleAlarm(ctx)
        }

        checkBtn.setOnClickListener {
            spinner.visibility = android.view.View.VISIBLE
            status.setText(R.string.shop_checking)
            sync(ctx) { n ->
                if (!dialog.isShowing) return@sync
                spinner.visibility = android.view.View.GONE
                showResult(n, manual = true)
                // Ждём покупку - снова частые проверки (на случай, если окно в 30 минут вышло).
                if (pending(ctx) != null) watchPending(ctx, immediate = false)
            }
        }
        restoreBtn.setOnClickListener {
            spinner.visibility = android.view.View.VISIBLE
            status.setText(R.string.shop3_restoring)
            sync(ctx) { n ->
                if (!dialog.isShowing) return@sync
                spinner.visibility = android.view.View.GONE
                if (n != 0) {
                    showResult(n, manual = true)
                    return@sync
                }
                val u = Coins.takeUnseenPurchase(ctx)
                if (u > 0) {
                    refresh()
                    status.text = ctx.getString(R.string.shop3_credited, u)
                    celebrate(ctx, u)
                    return@sync
                }
                // Ничего нового: все чеки уже зачислены - показать, сколько куплено всего.
                Thread {
                    val total = runCatching { purchased(ctx) }.getOrDefault(0)
                    main.post { if (dialog.isShowing) status.text = ctx.getString(R.string.shop3_restored_none, total) }
                }.start()
            }
        }
        showPending()

        // Выгодные предложения за монеты: скин дня со скидкой, набор скинов, бустеры.
        col.addView(TextView(ctx).apply {
            setText(R.string.offers_title)
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(4), dp(18), dp(4), dp(2))
        })
        val offersBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(offersBox)
        fun fillOffers() {
            offersBox.removeAllViews()
            CoinOffers.build(ctx, offersBox) {
                refresh()
                fillOffers()
            }
        }
        fillOffers()
        // Каталог: скины Змейки с предпросмотром, подарки себе.
        val catBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(catBox)
        fun fillCatalog() {
            catBox.removeAllViews()
            ShopExtras.buildCatalog(ctx, catBox) {
                refresh()
                fillCatalog()
            }
        }
        fillCatalog()

        col.addView(TextView(ctx).apply {
            setText(R.string.shop_note)
            textSize = 12.5f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(4), dp(14), dp(4), dp(4))
        })
        col.addView(donateButton(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) })

        val coinsL: () -> Unit = { refresh() }
        Coins.listen(coinsL)
        val ui: (Int) -> Unit = { n -> if (dialog.isShowing) showResult(n, manual = false) }
        uiListener = ui
        dialog.setOnDismissListener {
            Coins.unlisten(coinsL)
            if (uiListener === ui) uiListener = null
        }
        refresh()
        val scroll = ScrollView(ctx).apply { addView(col) }
        frame.addView(scroll)
        frame.fitSystemBars()
        dialog.setContentView(frame)
        dialog.show()
        // Открыли из блэкджека («Докупить фишки») - сразу к фишкам.
        fun focusChips() {
            if (focus == com.stoyko.smessenger.games.blackjack.BjOnline.CHIPS_FOCUS && chipBox.childCount > 0) {
                scroll.post { scroll.smoothScrollTo(0, (chipBox.top - dp(8)).coerceAtLeast(0)) }
            }
        }
        focusChips()
        // Открыли из «Бега по рельсам» (эксклюзивный облик) - сразу к обликам бегуна.
        if (focus == ShopExtras.RUNNER_FOCUS) scroll.post {
            val h = catBox.findViewWithTag<android.view.View>(ShopExtras.RUNNER_FOCUS)
            if (h != null) scroll.smoothScrollTo(0, (catBox.top + h.top - dp(8)).coerceAtLeast(0))
        }
        val chipsWere = chipPacksReady(ctx)
        refreshPacks(ctx) { ready ->
            if (!dialog.isShowing) return@refreshPacks
            if (ready != chipsWere || ready != (chipBox.childCount > 0)) {
                fillChips(ready)
                focusChips()
            }
        }
        // Вдруг оплата уже прошла, пока магазин был закрыт; ждём покупку - частые проверки.
        if (pending(ctx) != null) {
            watchPending(ctx)
        } else {
            sync(ctx) { n -> if (dialog.isShowing) showResult(n, manual = false) }
        }
    }

    /** Открыть оплату пака [p]; true - ссылка открыта и покупка запомнена как ожидаемая. */
    private fun buy(ctx: Context, p: Pack, method: String, status: TextView): Boolean {
        if (!configured()) {
            status.setText(R.string.shop_not_configured)
            // Разработчику - что именно настроить; остальным - короткое сообщение.
            if (DevAccount.isMe(ctx)) {
                DialogTracker.add(
                    androidx.appcompat.app.AlertDialog.Builder(ctx)
                        .setTitle(R.string.shop_setup_title)
                        .setMessage(R.string.shop_setup_text)
                        .setPositiveButton(android.R.string.ok, null)
                        .create(),
                ).show()
            } else {
                Toast.makeText(ctx, R.string.shop_not_configured, Toast.LENGTH_LONG).show()
            }
            refreshRemote(ctx, force = true)
            return false
        }
        val id = runCatching { userId(ctx) }.getOrNull()
        if (id == null) {
            Toast.makeText(ctx, R.string.shop_no_profile, Toast.LENGTH_LONG).show()
            return false
        }
        val url = paymentUrl(id, p.id, method) ?: return false
        // Запоминаем покупку ДО перехода к оплате: процесс могут выгрузить, пока открыт браузер.
        setPending(ctx, p, id)
        val opened = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!opened) {
            clearPending(ctx)
            Toast.makeText(ctx, R.string.shop_no_browser, Toast.LENGTH_LONG).show()
            return false
        }
        status.text = ctx.getString(R.string.shop_waiting, p.coins)
        return true
    }

    /** Красивая кнопка «💛 На жизнь проекта» - открывает сбор ЮMoney. */
    fun donateButton(ctx: Context): TextView =
        Btn.nice(ctx, ctx.getString(R.string.donate_button), intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt(), 0xFF9D174D.toInt())) {
            donate(ctx)
        }

    /** «На жизнь проекта»: окно с суммами (перевод на кошелёк ЮMoney) и ссылкой на сбор. */
    fun donate(ctx: Context) {
        load(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(8))
        }
        box.addView(TextView(ctx).apply {
            setText(R.string.donate_text)
            textSize = 14f
            setPadding(0, 0, 0, dp(10))
        })
        lateinit var dlg: android.app.Dialog
        fun open(url: String) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(ctx, R.string.shop_no_browser, Toast.LENGTH_LONG).show() }
            runCatching { dlg.dismiss() }
        }
        if (canDonate()) {
            val sums = listOf(100, 300, 500, 1000)
            box.addView(Btn.row(ctx, sums.take(2).map { r -> Btn.nice(ctx, "💛 $r ₽", intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt())) { open(donateUrl(r)) } }, height = 48, gap = 10))
            box.addView(Btn.row(ctx, sums.drop(2).map { r -> Btn.nice(ctx, "💛 $r ₽", intArrayOf(0xFFF472B6.toInt(), 0xFFBE185D.toInt())) { open(donateUrl(r)) } }, height = 48, gap = 10),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        box.addView(Btn.nice(ctx, ctx.getString(R.string.donate_fundraise), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) { open(DONATE_URL) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(12) })
        dlg = DialogTracker.add(
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle(R.string.donate_button)
                .setView(android.widget.ScrollView(ctx).apply { addView(box) })
                .setNegativeButton(R.string.close, null)
                .create(),
        )
        dlg.show()
    }

    /** Перевод «на жизнь проекта» нужной суммы - форма ЮMoney (карта или кошелёк). */
    private fun donateUrl(rub: Int): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val params = linkedMapOf(
            "receiver" to wallet,
            "quickpay-form" to "button",
            "paymentType" to "AC",
            "sum" to rub.toString(),
            "label" to "donate",
            "targets" to "На жизнь проекта S-messenger",
        )
        return "https://yoomoney.ru/quickpay/confirm?" + params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }

    // ---------------- анимация зачисления ----------------

    /**
     * Дождь из монет и счётчик баланса «было → стало». Баланс берётся настоящий -
     * из [Coins.balance] после зачисления.
     */
    fun celebrate(ctx: Context, n: Int) {
        if (n <= 0) return
        val d = ctx.resources.displayMetrics.density
        val dialog = DialogTracker.add(Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen))
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xB0000000.toInt()))
        val frame = FrameLayout(ctx)
        val now = Coins.balance(ctx)
        val before = (now - n).coerceAtLeast(0)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val big = CoinView(ctx).apply { period = 1.1f }
        box.addView(big, LinearLayout.LayoutParams((120 * d).toInt(), (120 * d).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(TextView(ctx).apply {
            text = "+$n"
            textSize = 46f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setShadowLayer(18f, 0f, 0f, 0xFFF59E0B.toInt())
        })
        val counter = TextView(ctx).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            text = ctx.getString(R.string.shop_balance, before)
        }
        box.addView(counter)
        box.addView(TextView(ctx).apply {
            setText(R.string.shop_thanks)
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)
        })
        frame.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnClickListener { dialog.safeDismiss() }
        dialog.setContentView(frame)
        dialog.show()

        CoinSound.jackpot(9)
        big.scaleX = 0.2f
        big.scaleY = 0.2f
        big.animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(android.view.animation.OvershootInterpolator(2.4f)).start()
        ValueAnimator.ofInt(before, now).apply {
            duration = 1800
            startDelay = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { counter.text = ctx.getString(R.string.shop_balance, it.animatedValue as Int) }
        }.start()
        // Дождь монет.
        frame.post {
            val w = frame.width.coerceAtLeast(1)
            val h = frame.height.coerceAtLeast(1)
            val count = if (com.stoyko.smessenger.ui.EnergySaver.on) 12 else if (com.stoyko.smessenger.ui.LiteMode.on) 18 else 28
            repeat(count) { i ->
                val size = ((22 + Math.random() * 22) * d).toInt()
                val c = CoinView(ctx).apply { period = (0.5 + Math.random()).toFloat() }
                frame.addView(c, 0, FrameLayout.LayoutParams(size, size))
                c.x = (Math.random() * (w - size)).toFloat()
                c.y = -size.toFloat()
                c.animate().y(h.toFloat() + size).rotation(((Math.random() - 0.5) * 360).toFloat())
                    .setStartDelay(i * 70L).setDuration((1300 + Math.random() * 900).toLong())
                    .setInterpolator(AccelerateInterpolator(1.2f))
                    .withEndAction { frame.removeView(c) }.start()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({ dialog.safeDismiss() }, 4200)
    }
}
