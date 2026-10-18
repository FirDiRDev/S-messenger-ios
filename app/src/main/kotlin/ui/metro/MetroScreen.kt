// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.metro

import com.stoyko.smessenger.ui.safeDismiss
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.fitSystemBars
import kotlin.math.cos
import kotlin.math.hypot
import org.json.JSONObject

/**
 * Схема метро Москвы без интернета: все линии, МЦК, БКЛ, Некрасовская и Троицкая.
 * Выберите «Откуда» и «Куда» (поиском или нажатием на станцию) - покажем маршрут, как в
 * картах: сколько ехать, где и куда пересаживаться, сколько станций на каждом участке.
 * Время ориентировочное (по расстоянию и средним перегонам), МЦД не показаны.
 */
object MetroScreen {
    // ---------- Данные ----------

    class Station(val id: Int, val name: String, val line: String, val lat: Double, val lon: Double)
    class Line(
        val id: String,
        val name: String,
        val color: Int,
        val ring: Boolean,
        val stations: IntArray,
        val times: IntArray,
        /** Строится: год открытия (по ней пока не ездят и маршруты не строятся). */
        val planned: String? = null,
    )

    class Data(val version: String, val lines: List<Line>, val stations: List<Station>, val adj: Array<MutableList<IntArray>>) {
        val lineById = lines.associateBy { it.id }

        /** Линия каждой станции - массивом, без поиска в словаре при каждом обращении. */
        val lineOf: Array<Line?> = Array(stations.size) { lineById[stations[it].line] }
        fun color(s: Station) = lineOf[s.id]?.color ?: Color.GRAY
        fun lineName(s: Station) = lineOf[s.id]?.name.orEmpty()
        fun planned(s: Station): String? = lineOf[s.id]?.planned
        fun planned(id: Int): String? = lineOf[id]?.planned

        /** Номер названия станции (у пересадочных узлов с одним именем - общий). */
        val nameId = IntArray(stations.size)

        /** Станции по названию: id всех станций узла. */
        val byName: Map<String, IntArray>

        /** Названия для поиска - без повторов, по алфавиту. */
        val names: List<String>

        /** Цвета линий узла (для точек в подсказках поиска). */
        val colorsByName: Map<String, IntArray>

        /** Название в нижнем регистре → первая станция (быстрый поиск по введённому тексту). */
        private val byLower: Map<String, Int>

        init {
            val groups = LinkedHashMap<String, ArrayList<Int>>()
            for (s in stations) groups.getOrPut(s.name) { ArrayList(2) }.add(s.id)
            val ids = HashMap<String, Int>(groups.size * 2)
            val bn = HashMap<String, IntArray>(groups.size * 2)
            val cn = HashMap<String, IntArray>(groups.size * 2)
            val low = HashMap<String, Int>(groups.size * 2)
            for ((name, list) in groups) {
                ids[name] = ids.size
                bn[name] = list.toIntArray()
                cn[name] = list.map { color(stations[it]) }.distinct().toIntArray()
                low.getOrPut(name.lowercase()) { list[0] }
            }
            for (s in stations) nameId[s.id] = ids[s.name] ?: 0
            byName = bn
            colorsByName = cn
            byLower = low
            names = groups.keys.sorted()
        }

        /** Рёбра графа в плоских массивах (CSR): у станции u - рёбра с eStart[u] до eStart[u + 1]. */
        val eStart = IntArray(stations.size + 1)
        val eTo: IntArray
        val eSec: IntArray
        val eKind: IntArray

        /** Есть пересадка на другую станцию. */
        val transfer = BooleanArray(stations.size)

        init {
            for (u in stations.indices) eStart[u + 1] = eStart[u] + adj[u].size
            val m = eStart[stations.size]
            eTo = IntArray(m)
            eSec = IntArray(m)
            eKind = IntArray(m)
            for (u in stations.indices) {
                var k = eStart[u]
                for (e in adj[u]) {
                    eTo[k] = e[0]
                    eSec[k] = e[1]
                    eKind[k] = e[2]
                    if (e[2] == 1) transfer[u] = true
                    k++
                }
            }
        }

        /** Станция по введённому тексту: точное название, иначе первое, начинающееся с текста. */
        fun find(text: String): Int {
            val t = text.trim()
            if (t.isEmpty()) return -1
            byLower[t.lowercase()]?.let { return it }
            return stations.firstOrNull { it.name.startsWith(t, ignoreCase = true) }?.id ?: -1
        }
    }

    /** Пересадка: дорога пешком + в среднем половина интервала ожидания поезда. */
    private const val WAIT = 120

    @Volatile
    private var cache: Data? = null

    fun load(ctx: Context): Data {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val o = JSONObject(ctx.assets.open("metro_msk.json").bufferedReader().use { it.readText() })
            val st = o.getJSONArray("st")
            val stations = (0 until st.length()).map {
                val s = st.getJSONObject(it)
                Station(it, s.getString("n"), s.getString("l"), s.getDouble("a"), s.getDouble("o"))
            }
            val adj = Array(stations.size) { ArrayList<IntArray>(4) as MutableList<IntArray> }
            val ls = o.getJSONArray("lines")
            val lines = (0 until ls.length()).map { i ->
                val l = ls.getJSONObject(i)
                val ids = l.getJSONArray("s").let { a -> IntArray(a.length()) { a.getInt(it) } }
                val times = l.getJSONArray("t").let { a -> IntArray(a.length()) { a.getInt(it) } }
                val ring = l.getBoolean("ring")
                val planned = l.optString("p").ifBlank { null }
                if (planned == null && ids.size > 1) for (k in times.indices) {
                    val a = ids[k]
                    val b = ids[(k + 1) % ids.size]
                    // [куда, секунд, 0 - едем по линии / 1 - пересадка]
                    adj[a].add(intArrayOf(b, times[k], 0))
                    adj[b].add(intArrayOf(a, times[k], 0))
                }
                Line(l.getString("id"), l.getString("name"), Color.parseColor(l.getString("c")), ring, ids, times, planned)
            }
            val tr = o.getJSONArray("tr")
            for (i in 0 until tr.length()) {
                val t = tr.getJSONArray(i)
                val a = t.getInt(0)
                val b = t.getInt(1)
                val sec = t.getInt(2)
                adj[a].add(intArrayOf(b, sec + WAIT, 1))
                adj[b].add(intArrayOf(a, sec + WAIT, 1))
            }
            return Data(o.optString("v"), lines, stations, adj).also { cache = it }
        }
    }

    // ---------- Маршрут ----------

    class Leg(val line: Line?, val from: Station, val to: Station, val stops: Int, val seconds: Int)
    class Route(val path: List<Int>, val legs: List<Leg>, val seconds: Int, val transfers: Int)

    /** Двоичная куча на LongArray: (время shl 32) or станция - без объектов на каждое ребро. */
    private class Heap(cap: Int) {
        private var a = LongArray(cap)
        var size = 0
            private set

        fun push(v: Long) {
            if (size == a.size) a = a.copyOf(size * 2)
            var i = size++
            while (i > 0) {
                val p = (i - 1) / 2
                if (a[p] <= v) break
                a[i] = a[p]
                i = p
            }
            a[i] = v
        }

        fun pop(): Long {
            val top = a[0]
            val last = a[--size]
            if (size > 0) {
                var i = 0
                while (true) {
                    var c = 2 * i + 1
                    if (c >= size) break
                    if (c + 1 < size && a[c + 1] < a[c]) c++
                    if (a[c] >= last) break
                    a[i] = a[c]
                    i = c
                }
                a[i] = last
            }
            return top
        }
    }

    /**
     * Самый быстрый маршрут (Дейкстра на куче, рёбра - плоскими массивами). Стартовое ожидание
     * поезда тоже учитывается. Поиск останавливается, как только дошли до нужного узла.
     */
    fun route(d: Data, from: Int, to: Int): Route? {
        val n = d.stations.size
        if (from !in 0 until n || to !in 0 until n || from == to) return null
        // Та же станция (другая линия пересадочного узла) - ехать некуда.
        if (d.nameId[from] == d.nameId[to]) return null
        val dist = IntArray(n) { Int.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val prevSec = IntArray(n)
        val heap = Heap(64)
        // Пересадки с той же станции на другую линию в начале пути - «бесплатно» (просто идёте на нужную платформу).
        for (s in d.byName[d.stations[from].name] ?: intArrayOf(from)) {
            dist[s] = WAIT
            heap.push((WAIT.toLong() shl 32) or s.toLong())
        }
        // Конечная - любая станция с тем же названием (переход внутри пересадочного узла не нужен).
        val goal = d.nameId[to]
        var target = -1
        while (heap.size > 0) {
            val top = heap.pop()
            val u = (top and 0xFFFFFFFFL).toInt()
            val du = (top ushr 32).toInt()
            if (du > dist[u]) continue
            if (d.nameId[u] == goal) {
                target = u
                break
            }
            for (k in d.eStart[u] until d.eStart[u + 1]) {
                val v = d.eTo[k]
                val nd = du + d.eSec[k]
                if (nd < dist[v]) {
                    dist[v] = nd
                    prev[v] = u
                    prevSec[v] = d.eSec[k]
                    heap.push((nd.toLong() shl 32) or v.toLong())
                }
            }
        }
        if (target < 0) return null
        val path = ArrayList<Int>()
        var c = target
        while (c != -1) {
            path.add(c)
            c = prev[c]
        }
        path.reverse()
        // Участки по линиям (время перегона - то ребро, по которому реально проехали).
        val legs = ArrayList<Leg>()
        var i = 0
        while (i < path.size - 1) {
            val a = d.stations[path[i]]
            val b = d.stations[path[i + 1]]
            if (a.line != b.line) {
                legs.add(Leg(null, a, b, 0, prevSec[b.id]))
                i++
                continue
            }
            var j = i
            var sec = 0
            while (j < path.size - 1 && d.stations[path[j + 1]].line == a.line) {
                sec += prevSec[path[j + 1]]
                j++
            }
            legs.add(Leg(d.lineById[a.line], a, d.stations[path[j]], j - i, sec))
            i = j
        }
        // Переход внутри одного узла в начале/конце - не пересадка. Но переход на станцию
        // с другим названием (например, с «Библиотеки им. Ленина» на «Арбатскую») - настоящая
        // дорога пешком: раньше она молча выпадала из списка шагов, хотя время учитывалось.
        val clean = legs.dropWhile { it.line == null && it.from.name == it.to.name }.dropLastWhile { it.line == null && it.from.name == it.to.name }
        return Route(path, clean, dist[target], clean.count { it.line == null })
    }

    // ---------- Экран ----------

    /** Номер линии для значка: «4A» → «4А», МЦК - «МЦК». */
    fun badge(l: Line): String = when {
        l.id == "14" -> "МЦК"
        else -> l.id.removeSuffix("P").replace("A", "А")
    }

    private const val PREFS = "metro"

    private fun recent(ctx: Context): List<Pair<String, String>> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "").orEmpty()
            .split('\n').mapNotNull { r -> r.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }

    private fun saveRecent(ctx: Context, from: String, to: String) {
        val list = (listOf(from to to) + recent(ctx).filterNot { it.first == from && it.second == to }).take(5)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("recent", list.joinToString("\n") { it.first + "\t" + it.second }).apply()
    }

    @Volatile
    private var loading = false

    /**
     * Открыть схему. Данные (JSON, граф, индексы для поиска) разбираются один раз и в фоне -
     * главный поток не ждёт чтения файла; повторное открытие - мгновенно из кэша.
     */
    fun show(ctx: Context) {
        cache?.let {
            showWith(ctx, it)
            return
        }
        if (loading) return
        loading = true
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val data = runCatching { load(app) }.getOrNull()
            main.post {
                loading = false
                if (data != null && alive(ctx)) showWith(ctx, data)
            }
        }, "metro-load").start()
    }

    /** Окно, из которого открывали схему, ещё живо (пока грузились данные, его могли закрыть). */
    private fun alive(ctx: Context): Boolean {
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return !c.isFinishing && !c.isDestroyed
            c = c.baseContext
        }
        return true
    }

    private fun showWith(ctx: Context, data: Data) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cBg = ContextCompat.getColor(ctx, R.color.colorBackground)
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val cCard = ContextCompat.getColor(ctx, R.color.colorSurface)
        val accent = ContextCompat.getColor(ctx, R.color.settingsAccent)

        val dialog = Dialog(ctx, R.style.AppTheme_FullDialog)
        val root = FrameLayout(ctx).apply { setBackgroundColor(cBg) }

        // Схема - на весь экран, панели плавают поверх неё.
        val map = MetroMapView(ctx, data)
        root.addView(map, FrameLayout.LayoutParams(-1, -1))

        val topCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFD7263D.toInt(), 0xFF9B1B30.toInt()),
            ).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 26 * d, 26 * d, 26 * d, 26 * d) }
            elevation = 8 * d
            setPadding(dp(8), dp(6), dp(12), dp(14))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        // Логотип «М» в круге.
        bar.addView(
            TextView(ctx).apply {
                text = "М"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFFD7263D.toInt())
                setTypeface(Typeface.DEFAULT_BOLD)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
            },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) },
        )
        bar.addView(
            TextView(ctx).apply {
                setText(R.string.metro_title)
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        bar.addView(
            TextView(ctx).apply {
                text = "ℹ"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33FFFFFF)
                }
                setOnClickListener { metroInfo(ctx, data) }
                Kinetics.squish(this, 0.85f)
            },
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        header.addView(bar)

        // Поля «Откуда» / «Куда»: поиск с цветными точками линий.
        // Названия и цвета узлов посчитаны заранее (в фоне, вместе с данными). Фильтр
        // ArrayAdapter работает в своём фоновом потоке, а строки подсказок переиспользуются:
        // точки линий не создаются заново на каждую прокрутку и каждую букву.
        val names = data.names
        val colorsByName = data.colorsByName
        class StationAdapter : ArrayAdapter<String>(ctx, 0, ArrayList(names)) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            repeat(3) {
                                addView(
                                    View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } },
                                    LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(3) },
                                )
                            }
                        },
                        LinearLayout.LayoutParams(dp(40), -2),
                    )
                    addView(TextView(ctx).apply { textSize = 15f; setTextColor(cText) })
                }
                val name = getItem(position).orEmpty()
                val dots = row.getChildAt(0) as LinearLayout
                val colors = colorsByName[name]
                for (k in 0 until dots.childCount) {
                    val v = dots.getChildAt(k)
                    if (colors != null && k < colors.size) {
                        (v.background as? GradientDrawable)?.setColor(colors[k])
                        v.visibility = View.VISIBLE
                    } else {
                        v.visibility = View.GONE
                    }
                }
                (row.getChildAt(1) as TextView).text = name
                return row
            }
        }
        fun field(hint: Int, dotColor: Int) = AutoCompleteTextView(ctx).apply {
            setHint(hint)
            setSingleLine()
            textSize = 15f
            threshold = 1
            setTextColor(cText)
            setHintTextColor(cSub)
            setAdapter(StationAdapter())
            setDropDownBackgroundDrawable(GradientDrawable().apply { cornerRadius = 14 * d; setColor(cCard) })
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(cCard)
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setSize(dp(10), dp(10))
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null)
            compoundDrawablePadding = dp(10)
            setPadding(dp(14), dp(11), dp(12), dp(11))
        }
        val fromField = field(R.string.metro_from, 0xFF22C55E.toInt())
        val toField = field(R.string.metro_to, 0xFFE5484D.toInt())
        val fields = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), 0, 0)
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(fromField, LinearLayout.LayoutParams(-1, -2))
        col.addView(toField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fields.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val swap = TextView(ctx).apply {
            text = "⇅"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }
        Kinetics.squish(swap, 0.85f)
        fields.addView(swap, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        header.addView(fields)
        topCol.addView(header)

        // Линии: значки-фильтры (нажатие - подсветить линию на схеме).
        val legendRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        val legendChips = ArrayList<TextView>()
        val shownLines = data.lines.filterNot { it.id.endsWith("A") || it.planned != null }
        for (l in shownLines) {
            val chip = TextView(ctx).apply {
                text = badge(l)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(8), 0, dp(8), 0)
                minWidth = dp(30)
                background = GradientDrawable().apply {
                    cornerRadius = 15 * d
                    setColor(l.color)
                }
                elevation = 2 * d
                tag = l.id
            }
            Kinetics.squish(chip, 0.85f)
            chip.setOnClickListener {
                map.highlightLine = if (map.highlightLine == l.id) null else l.id
                if (map.highlightLine != null) {
                    android.widget.Toast.makeText(ctx, l.name, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            chip.setOnLongClickListener {
                lineInfo(ctx, data, l)
                true
            }
            legendChips += chip
            legendRow.addView(chip, LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) })
        }
        // Значки линий следят за подсветкой: построили маршрут - подсветка снимается, и значки
        // снова яркие (раньше оставались бледными до следующего нажатия).
        map.onHighlight = { hl ->
            legendChips.forEach { c -> c.animate().alpha(if (hl == null || c.tag == hl) 1f else 0.35f).setDuration(180).start() }
        }
        topCol.addView(
            android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(legendRow)
            },
        )
        // Недавние маршруты.
        val recentRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(4))
        }
        val recentScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(recentRow)
        }
        topCol.addView(recentScroll)
        root.addView(topCol, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Кнопки масштаба справа.
        val zoomCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun roundBtn(label: String, click: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(cText)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(cCard)
            }
            elevation = 6 * d
            setOnClickListener { click() }
            Kinetics.squish(this, 0.85f)
        }
        zoomCol.addView(roundBtn("+") { map.zoomAnimated(1.8f) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        zoomCol.addView(roundBtn("−") { map.zoomAnimated(1 / 1.8f) }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10) })
        zoomCol.addView(roundBtn("◎") { map.resetView() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10) })
        // Поезда на схеме: показать / скрыть.
        zoomCol.addView(roundBtn("🚇") {
            // Цикл: живая схема (ускорено) → по реальному времени → скрыть.
            val msg = when {
                map.showTrains && map.trainSpeed > 1 -> { map.setTrainSpeed(1); R.string.metro_trains_realtime }
                map.showTrains -> { map.showTrains = false; R.string.metro_trains_off }
                else -> { map.showTrains = true; map.setTrainSpeed(MetroMapView.LIVE_SPEED); R.string.metro_trains_live }
            }
            map.invalidate()
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(10) })
        root.addView(zoomCol, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })

        // Карточка маршрута снизу.
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(26 * d, 26 * d, 26 * d, 26 * d, 0f, 0f, 0f, 0f)
                setColor(cCard)
            }
            elevation = 16 * d
            setPadding(dp(16), dp(8), dp(16), dp(14))
        }
        sheet.addView(
            View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3 * d
                    setColor((cSub and 0x00FFFFFF) or 0x66000000)
                }
            },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) },
        )
        val hintText = TextView(ctx).apply {
            text = ctx.getString(R.string.metro_hint, data.version)
            textSize = 13f
            setTextColor(cSub)
            gravity = Gravity.CENTER
        }
        sheet.addView(hintText)
        val summary = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val bigTime = TextView(ctx).apply {
            textSize = 30f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(cText)
        }
        val subInfo = TextView(ctx).apply {
            textSize = 13f
            setTextColor(cSub)
            setPadding(dp(12), 0, 0, 0)
        }
        summary.addView(bigTime)
        summary.addView(subInfo, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = TextView(ctx).apply {
            textSize = 13f
            setTextColor(accent)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(10), dp(6), dp(4), dp(6))
        }
        summary.addView(toggle)
        sheet.addView(summary)
        val steps = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val stepsScroll = ScrollView(ctx).apply {
            addView(steps)
            isVerticalScrollBarEnabled = false
        }
        sheet.addView(stepsScroll, LinearLayout.LayoutParams(-1, -2))
        val go = com.stoyko.smessenger.ui.Btn.primary(ctx, ctx.getString(R.string.metro_go), intArrayOf(0xFFE0394F.toInt(), 0xFFB31B33.toInt())) {}
        sheet.addView(go, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
        root.addView(sheet, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        var fromId = -1
        var toId = -1
        var expanded = true
        fun idByName(text: String): Int = data.find(text)
        fun mins(sec: Int) = ((sec + 30) / 60).coerceAtLeast(1)
        fun setExpanded(on: Boolean) {
            expanded = on
            toggle.text = ctx.getString(if (on) R.string.metro_less else R.string.metro_more)
            stepsScroll.layoutParams = (stepsScroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = if (on) minOf(steps.height.takeIf { it > 0 } ?: -2, (ctx.resources.displayMetrics.heightPixels * 0.36f).toInt()).let { h -> if (h <= 0) -2 else h } else 0
            }
            stepsScroll.requestLayout()
        }
        toggle.setOnClickListener { setExpanded(!expanded) }

        val walkDash = android.graphics.DashPathEffect(floatArrayOf(4 * d, 5 * d), 0f)
        fun step(color: Int, top: Boolean, bottom: Boolean, iconText: String?, title: String, sub: String, dashed: Boolean = false): View {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dp(54)
            }
            // Цветная «нитка» маршрута слева: кружок станции и полоса линии.
            val rail = object : View(ctx) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(c: Canvas) {
                    val cx = width / 2f
                    p.color = color
                    p.strokeWidth = 5 * d
                    p.strokeCap = Paint.Cap.ROUND
                    p.pathEffect = if (dashed) walkDash else null
                    val cy = 18 * d
                    if (top) c.drawLine(cx, 0f, cx, cy, p)
                    if (bottom) c.drawLine(cx, cy, cx, height.toFloat(), p)
                    p.pathEffect = null
                    p.style = Paint.Style.FILL
                    p.color = Color.WHITE
                    c.drawCircle(cx, cy, 7 * d, p)
                    p.style = Paint.Style.STROKE
                    p.color = color
                    p.strokeWidth = 3.5f * d
                    c.drawCircle(cx, cy, 7 * d, p)
                    p.style = Paint.Style.FILL
                }
            }
            row.addView(rail, LinearLayout.LayoutParams(dp(28), -1))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), 0, dp(8))
            }
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (iconText != null) {
                head.addView(
                    TextView(ctx).apply {
                        text = iconText
                        textSize = 11f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setPadding(dp(6), 0, dp(6), 0)
                        minWidth = dp(24)
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * d
                            setColor(color)
                        }
                    },
                    LinearLayout.LayoutParams(-2, dp(20)).apply { marginEnd = dp(8) },
                )
            }
            head.addView(TextView(ctx).apply {
                text = title
                textSize = 15f
                setTextColor(cText)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            texts.addView(head)
            if (sub.isNotEmpty()) {
                texts.addView(TextView(ctx).apply {
                    text = sub
                    textSize = 13f
                    setTextColor(cSub)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }

        fun render(r: Route?) {
            steps.removeAllViews()
            if (r == null) {
                summary.visibility = View.GONE
                hintText.visibility = View.VISIBLE
                go.visibility = View.VISIBLE
                map.route = null
                stepsScroll.layoutParams = (stepsScroll.layoutParams as LinearLayout.LayoutParams).apply { height = -2 }
                return
            }
            map.route = r
            hintText.visibility = View.GONE
            go.visibility = View.GONE
            summary.visibility = View.VISIBLE
            val m = mins(r.seconds)
            bigTime.text = ctx.getString(R.string.metro_minutes, m)
            val arrive = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(System.currentTimeMillis() + r.seconds * 1000L))
            val stations = r.legs.filter { it.line != null }.sumOf { it.stops }
            subInfo.text = ctx.getString(R.string.metro_summary, r.transfers, stations, arrive)
            val rides = r.legs
            rides.forEachIndexed { i, leg ->
                val l = leg.line
                if (l == null) {
                    steps.addView(
                        step(
                            0xFF9CA3AF.toInt(), top = true, bottom = true, iconText = null,
                            title = "🚶 " + ctx.getString(R.string.metro_walk, mins(leg.seconds)),
                            sub = ctx.getString(R.string.metro_walk_to, leg.to.name, data.lineName(leg.to)),
                            dashed = true,
                        ),
                    )
                } else {
                    steps.addView(
                        step(
                            l.color, top = i > 0, bottom = true, iconText = badge(l),
                            title = leg.from.name,
                            sub = ctx.getString(R.string.metro_leg, l.name, leg.stops, mins(leg.seconds)),
                        ),
                    )
                    val next = rides.getOrNull(i + 1)
                    if (next == null) {
                        steps.addView(step(l.color, top = true, bottom = false, iconText = null, title = leg.to.name, sub = ctx.getString(R.string.metro_arrive_at, arrive)))
                    } else if (next.line != null) {
                        // Пересадка без перехода (кросс-платформенная) - отдельной строкой не нужна.
                    } else {
                        steps.addView(step(l.color, top = true, bottom = false, iconText = null, title = leg.to.name, sub = ctx.getString(R.string.metro_get_off)))
                    }
                }
            }
            steps.addView(TextView(ctx).apply {
                setText(R.string.metro_estimate)
                textSize = 11f
                setTextColor(cSub)
                setPadding(0, dp(8), 0, 0)
            })
            Kinetics.stagger((0 until steps.childCount).map { steps.getChildAt(it) }, step = 40, max = 20)
            setExpanded(true)
            stepsScroll.post { setExpanded(expanded) }
            // Время «набегает» числом (в лёгком режиме - сразу итог, без лишней анимации).
            if (Kinetics.enabled && !com.stoyko.smessenger.ui.LiteMode.on) {
                android.animation.ValueAnimator.ofInt(0, m).apply {
                    duration = 600
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener { bigTime.text = ctx.getString(R.string.metro_minutes, it.animatedValue as Int) }
                    start()
                }
            }
            Kinetics.springIn(sheet, dy = 30f)
        }

        fun fillRecent() {
            recentRow.removeAllViews()
            val list = recent(ctx)
            recentScroll.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
            for ((a, b) in list) {
                recentRow.addView(
                    TextView(ctx).apply {
                        text = "🕘 $a → $b"
                        textSize = 12f
                        setTextColor(cText)
                        gravity = Gravity.CENTER
                        setPadding(dp(12), 0, dp(12), 0)
                        background = GradientDrawable().apply {
                            cornerRadius = 15 * d
                            setColor(cCard)
                        }
                        elevation = 2 * d
                        setOnClickListener {
                            fromField.setText(a, false)
                            toField.setText(b, false)
                            fromId = idByName(a)
                            toId = idByName(b)
                            map.from = fromId
                            map.to = toId
                            if (fromId >= 0 && toId >= 0) render(route(data, fromId, toId))
                        }
                        Kinetics.squish(this, 0.92f)
                    },
                    LinearLayout.LayoutParams(-2, dp(30)).apply { marginEnd = dp(6) },
                )
            }
        }

        fun search() {
            if (fromId < 0) fromId = idByName(fromField.text.toString())
            if (toId < 0) toId = idByName(toField.text.toString())
            map.from = fromId
            map.to = toId
            if (fromId < 0 || toId < 0) {
                android.widget.Toast.makeText(ctx, R.string.metro_pick_both, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(go.windowToken, 0)
            val r = route(data, fromId, toId)
            if (r == null) {
                val p = data.planned(fromId) ?: data.planned(toId)
                if (p != null) {
                    val n = if (data.planned(fromId) != null) data.stations[fromId].name else data.stations[toId].name
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.metro_planned, n, p), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            render(r)
            if (r != null) {
                saveRecent(ctx, data.stations[fromId].name, data.stations[toId].name)
                fillRecent()
            }
        }
        fromField.setOnItemClickListener { _, _, _, _ ->
            fromId = idByName(fromField.text.toString())
            map.from = fromId
            map.focusAnimated(fromId)
            if (toId >= 0) search() else toField.requestFocus()
        }
        toField.setOnItemClickListener { _, _, _, _ ->
            toId = idByName(toField.text.toString())
            map.to = toId
            search()
        }
        fun watch(f: AutoCompleteTextView, reset: () -> Unit) = f.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: Editable?) {
                if (f.isPerformingCompletion) return
                reset()
            }
        })
        watch(fromField) { fromId = -1 }
        watch(toField) { toId = -1 }
        go.setOnClickListener { search() }
        swap.setOnClickListener {
            val a = fromField.text.toString()
            val b = toField.text.toString()
            val ia = fromId
            val ib = toId
            fromField.setText(b, false)
            toField.setText(a, false)
            fromId = ib
            toId = ia
            map.from = fromId
            map.to = toId
            swap.animate().rotationBy(180f).setDuration(300).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            if (fromId >= 0 && toId >= 0) search()
        }
        // Нажатие на станцию на схеме: сначала «Откуда», затем «Куда».
        fun pickFrom(id: Int) {
            val s = data.stations[id]
            fromId = id
            toId = -1
            fromField.setText(s.name, false)
            toField.setText("", false)
            map.from = id
            map.to = -1
            render(null)
        }
        fun pickTo(id: Int) {
            toId = id
            toField.setText(data.stations[id].name, false)
            if (fromId >= 0) search() else map.to = id
        }
        map.onStationInfo = { id -> stationInfo(ctx, data, id, onFrom = { pickFrom(it) }, onTo = { pickTo(it) }) }
        map.onStation = onStation@{ id ->
            val s = data.stations[id]
            val planned = data.planned(s)
            if (planned != null) {
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.metro_planned, s.name, planned), android.widget.Toast.LENGTH_LONG).show()
                map.onStationInfo?.invoke(id)
                return@onStation
            }
            if (fromId < 0 || toId >= 0) {
                fromId = id
                toId = -1
                fromField.setText(s.name, false)
                toField.setText("", false)
                map.from = id
                map.to = -1
                render(null)
            } else {
                toId = id
                toField.setText(s.name, false)
                search()
            }
        }
        fillRecent()

        root.fitSystemBars()
        // Схема вписывается целиком в свободную часть экрана - между шапкой и карточкой.
        val insetsListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            map.setInsets((topCol.bottom - map.top).coerceAtLeast(0).toFloat(), (map.bottom - sheet.top).coerceAtLeast(0).toFloat())
        }
        topCol.addOnLayoutChangeListener(insetsListener)
        sheet.addOnLayoutChangeListener(insetsListener)
        dialog.setContentView(root)
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.setOnDismissListener {
            map.stop()
            map.onHighlight = null
        }
        dialog.show()
        Kinetics.springIn(header, dy = -20f)
        Kinetics.stagger(legendChips.map { it as View }, step = 25, max = 18)
    }

    // ---------- Информация ----------

    /** Карточка станции: линии, статус, пересадки, «Отсюда» / «Сюда». */
    private fun stationInfo(ctx: Context, data: Data, id: Int, onFrom: (Int) -> Unit, onTo: (Int) -> Unit) {
        val s = data.stations[id]
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val same = (data.byName[s.name] ?: intArrayOf(id)).map { data.stations[it] }
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(18), dp(16), dp(18), dp(18))
        }
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        root.addView(TextView(ctx).apply {
            text = "Ⓜ  " + s.name
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(cText)
        })
        // Линии станции.
        for (st in same) {
            val l = data.lineById[st.line] ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
            }
            row.addView(TextView(ctx).apply {
                text = badge(l)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(8), 0, dp(8), 0)
                minWidth = dp(30)
                background = GradientDrawable().apply { cornerRadius = 13 * d; setColor(l.color) }
            }, LinearLayout.LayoutParams(-2, dp(26)))
            row.addView(TextView(ctx).apply {
                text = l.name + (l.planned?.let { "  ·  " + ctx.getString(R.string.metro_building, it) } ?: "")
                textSize = 15f
                setTextColor(cText)
                setPadding(dp(10), 0, 0, 0)
            })
            root.addView(row)
            // Ближайшие поезда в обе стороны (по расписанию).
            if (l.planned == null) {
                val arr = TextView(ctx).apply {
                    textSize = 13.5f
                    setTextColor(cText)
                    setPadding(dp(4), dp(6), 0, 0)
                }
                fun fill() {
                    val hh = MetroTrains.headway()
                    arr.text = if (hh == null) {
                        ctx.getString(R.string.metro_closed)
                    } else {
                        MetroTrains.arrivals(l, st.id, MetroTrains.nowSec(), hh).joinToString("\n") { (dir, sec) ->
                            val to = if (l.ring) {
                                ctx.getString(if (dir > 0) R.string.metro_ring_cw else R.string.metro_ring_ccw)
                            } else {
                                data.stations[if (dir > 0) l.stations.last() else l.stations.first()].name
                            }
                            val min = sec / 60
                            "🚇 → $to: " + if (min < 1) ctx.getString(R.string.metro_arriving) else ctx.getString(R.string.metro_in_min, min)
                        } + "\n" + ctx.getString(R.string.metro_headway, hh / 60)
                    }
                }
                fill()
                // Обновляем, пока карточка открыта.
                val tick = object : Runnable {
                    override fun run() {
                        if (!arr.isAttachedToWindow) return
                        fill()
                        arr.postDelayed(this, 10_000)
                    }
                }
                arr.postDelayed(tick, 10_000)
                root.addView(arr)
            }
        }
        // Пересадки.
        val transfers = same.flatMap { st -> data.adj[st.id].filter { it[2] == 1 }.map { e -> data.stations[e[0]] to (e[1] - WAIT) } }
            .filter { it.first.name != s.name || it.first.line != s.line }
            .distinctBy { it.first.id }
        if (transfers.isNotEmpty()) {
            root.addView(TextView(ctx).apply {
                setText(R.string.metro_transfers_title)
                textSize = 13f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(cSub)
                setPadding(0, dp(14), 0, dp(2))
            })
            for ((t, secs) in transfers) {
                root.addView(TextView(ctx).apply {
                    text = "🚶 " + ctx.getString(R.string.metro_transfer_item, t.name, data.lineName(t), ((secs + 30) / 60).coerceAtLeast(1))
                    textSize = 14f
                    setTextColor(cText)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }
        val planned = data.planned(s)
        root.addView(TextView(ctx).apply {
            text = if (planned != null) ctx.getString(R.string.metro_status_building, planned) else ctx.getString(R.string.metro_status_open)
            textSize = 13f
            setTextColor(if (planned != null) 0xFFF59E0B.toInt() else 0xFF22C55E.toInt())
            setPadding(0, dp(12), 0, 0)
        })
        if (planned == null) {
            root.addView(
                com.stoyko.smessenger.ui.Btn.row(
                    ctx,
                    listOf(
                        com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(R.string.metro_from_here), 0xFF16A34A.toInt()) { sheet.dismiss(); onFrom(id) },
                        com.stoyko.smessenger.ui.Btn.tonal(ctx, ctx.getString(R.string.metro_to_here), 0xFFDC2626.toInt()) { sheet.dismiss(); onTo(id) },
                    ),
                    height = 46,
                ),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) },
            )
        }
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.show()
    }

    /** Сведения о линии (долгое нажатие на значок линии). */
    private fun lineInfo(ctx: Context, data: Data, l: Line) {
        val parts = data.lines.filter { it.id.removeSuffix("A").removeSuffix("P") == l.id }
        val open = parts.filter { it.planned == null }
        val stations = open.flatMap { p -> p.stations.map { data.stations[it].name } }.distinct()
        val secs = open.sumOf { it.times.sum() }
        val msg = buildString {
            append(ctx.getString(R.string.metro_line_stats, stations.size, (secs + 30) / 60))
            if (l.ring) append("\n").append(ctx.getString(R.string.metro_line_ring))
            parts.filter { it.planned != null }.forEach { p ->
                append("\n\n").append(ctx.getString(R.string.metro_line_building, p.planned!!, p.stations.drop(1).joinToString(", ") { data.stations[it].name }))
            }
            append("\n\n").append(stations.joinToString(" → "))
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(badge(l) + "  " + l.name)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Справка о схеме. */
    private fun metroInfo(ctx: Context, data: Data) {
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.metro_title)
            .setMessage(ctx.getString(R.string.metro_info_text, data.version, data.stations.count { data.planned(it) == null }, data.lines.count { it.planned == null && !it.id.endsWith("A") }))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- Схема ----------

    /**
     * Готовая (записанная) часть схемы: на Android 10+ - RenderNode (кадр рисуется GPU без
     * повторной работы в Kotlin), на 6-9 - Picture. Записывается для текущего масштаба во всю
     * схему сразу, поэтому при перемещении пальцем просто сдвигается.
     */
    private class Layer {
        private var pic: android.graphics.Picture? = null
        private var node: Any? = null

        val ready: Boolean get() = pic != null || node != null

        fun record(hw: Boolean, w: Int, h: Int, body: (Canvas) -> Unit) {
            release()
            if (hw && android.os.Build.VERSION.SDK_INT >= 29) {
                node = Api29.record(w, h, body)
            } else {
                val p = android.graphics.Picture()
                val c = p.beginRecording(w, h)
                try {
                    body(c)
                } finally {
                    p.endRecording()
                }
                pic = p
            }
        }

        /** false - на этом холсте записанное не нарисовать (тогда рисуем как обычно). */
        fun draw(c: Canvas, dx: Float, dy: Float): Boolean {
            val n = node
            val p = pic
            if (n != null) {
                if (!c.isHardwareAccelerated || android.os.Build.VERSION.SDK_INT < 29) return false
                c.save()
                c.translate(dx, dy)
                Api29.draw(c, n)
                c.restore()
                return true
            }
            if (p != null) {
                if (c.isHardwareAccelerated && android.os.Build.VERSION.SDK_INT < 23) return false
                c.save()
                c.translate(dx, dy)
                c.drawPicture(p)
                c.restore()
                return true
            }
            return false
        }

        fun release() {
            val n = node
            if (n != null && android.os.Build.VERSION.SDK_INT >= 29) Api29.discard(n)
            node = null
            pic = null
        }
    }

    @androidx.annotation.RequiresApi(29)
    private object Api29 {
        fun record(w: Int, h: Int, body: (Canvas) -> Unit): Any {
            val n = android.graphics.RenderNode("metro")
            n.setPosition(0, 0, w, h)
            val c = n.beginRecording(w, h)
            try {
                body(c)
            } finally {
                n.endRecording()
            }
            return n
        }

        fun draw(c: Canvas, n: Any) = c.drawRenderNode(n as android.graphics.RenderNode)

        fun discard(n: Any) = (n as android.graphics.RenderNode).discardDisplayList()
    }

    @SuppressLint("ViewConstructor")
    private class MetroMapView(ctx: Context, private val data: Data) : View(ctx) {
        companion object {
            /** Во сколько раз быстрее идут «часы» живой схемы. */
            const val LIVE_SPEED = 12

            /** Средняя скорость поезда, м/с - чтобы понять, как часто нужен новый кадр. */
            private const val TRAIN_MPS = 12f
        }

        private val d = resources.displayMetrics.density
        private val n = data.stations.size
        private val lines = data.lines
        private val lat0 = if (n == 0) 55.75 else data.stations.sumOf { it.lat } / n
        private val lon0 = if (n == 0) 37.62 else data.stations.sumOf { it.lon } / n
        private val k = cos(Math.toRadians(lat0))
        // Координаты в «метрах» схемы.
        private val px = FloatArray(n) { ((data.stations[it].lon - lon0) * k * 111_000).toFloat() }
        private val py = FloatArray(n) { (-(data.stations[it].lat - lat0) * 111_000).toFloat() }
        // Границы схемы - один раз (раньше min/max по всем станциям считались несколько раз за кадр).
        private val minX = if (n == 0) 0f else px.min()
        private val maxX = if (n == 0) 0f else px.max()
        private val minY = if (n == 0) 0f else py.min()
        private val maxY = if (n == 0) 0f else py.max()

        /** «Семья» линии: 8 и 8А, 17 и строящаяся 17P - одна подсветка. */
        private val groupIds = HashMap<String, Int>()
        private fun groupKey(id: String) = id.removeSuffix("A").removeSuffix("P")
        private fun groupOf(id: String): Int = groupIds.getOrPut(groupKey(id)) { groupIds.size }
        private val lineGroup = IntArray(lines.size) { groupOf(lines[it].id) }
        private val lineIndex = HashMap<Line, Int>().also { m -> lines.forEachIndexed { i, l -> m[l] = i } }
        private val stLine = IntArray(n) { data.lineOf[it]?.let { l -> lineIndex[l] } ?: -1 }
        private val stGroup = IntArray(n) { if (stLine[it] >= 0) lineGroup[stLine[it]] else -1 }
        private val stColor = IntArray(n) { data.color(data.stations[it]) }
        private val stBuilding = BooleanArray(n) { data.planned(it) != null }
        private val badgeText = Array(lines.size) { badge(lines[it]) }
        private val isMcd = BooleanArray(lines.size) { lines[it].id.startsWith("D") }

        /** Подписи: строящиеся станции - с годом открытия. Строки готовы заранее, а не в каждом кадре. */
        private val labelText = Array(n) { i -> data.planned(i)?.let { y -> "${data.stations[i].name} · $y" } ?: data.stations[i].name }

        /** Ширина подписей: [мелкий/крупный шрифт × обычный/жирный] - меряется один раз. */
        private val labelW = Array(4) { FloatArray(n) { -1f } }

        private val transfer = data.transfer

        /** Пары станций-пересадок (рисуются «перемычками», как на настоящей схеме). */
        private val links: IntArray = run {
            val out = ArrayList<Int>()
            for (u in 0 until n) for (e in data.eStart[u] until data.eStart[u + 1]) {
                if (data.eKind[e] == 1 && u < data.eTo[e]) {
                    out += u
                    out += data.eTo[e]
                }
            }
            out.toIntArray()
        }

        /** Порядок подписей: сначала пересадочные (важнее), потом остальные. */
        private val labelOrderBase = (0 until n).sortedByDescending { if (transfer[it]) 1 else 0 }.toIntArray()
        private var labelOrder = labelOrderBase

        private val dark = (ContextCompat.getColor(ctx, R.color.colorBackground) and 0xFFFFFF).let { c ->
            ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) < 3 * 110
        }

        // Камера: экран = схема * scale + (tx, ty).
        private var scale = 0f
        private var tx = 0f
        private var ty = 0f

        /** Номер состояния схемы (выбор, подсветка, маршрут): изменился - записанные слои устарели. */
        private var stateVer = 0
        private val onRoute = BooleanArray(n)
        /** На маршруте, «откуда» или «куда». */
        private val picked = BooleanArray(n)

        var from = -1
            set(v) {
                if (field == v) return
                field = v
                stateChanged()
            }
        var to = -1
            set(v) {
                if (field == v) return
                field = v
                stateChanged()
            }
        var highlightLine: String? = null
            set(v) {
                val changed = field != v
                field = v
                hlGroup = v?.let { groupIds[groupKey(it)] } ?: -1
                v?.let { id -> data.lineById[id]?.let { l -> fitTo(l.stations.toList(), animate = true) } }
                if (changed) onHighlight?.invoke(v)
                stateChanged()
            }
        private var hlGroup = -1

        /** Подсветка линии изменилась (в том числе сама - при построении маршрута). */
        var onHighlight: ((String?) -> Unit)? = null

        /** Показывать поезда (модель по расписанию - см. [MetroTrains]). */
        var showTrains = true
            set(v) {
                field = v
                invalidate()
            }

        /**
         * Скорость «часов» схемы. По реальному времени поезд за секунду проезжает на общей
         * схеме меньше пикселя - кажется, что поезда стоят. Поэтому по умолчанию схема живая:
         * время идёт в [LIVE_SPEED] раз быстрее, и составы заметно едут по линиям.
         */
        var trainSpeed = LIVE_SPEED
            private set
        private var clockBaseSec = 0.0
        private var clockBaseUp = 0L

        private fun realNowSec(): Double {
            val c = java.util.Calendar.getInstance()
            return MetroTrains.nowSec(c) + c.get(java.util.Calendar.MILLISECOND) / 1000.0
        }

        fun setTrainSpeed(k: Int) {
            // По реальному времени - от настоящих часов; ускоренно - продолжаем с текущего момента схемы.
            clockBaseSec = if (k <= 1) realNowSec() else clockSec()
            clockBaseUp = android.os.SystemClock.uptimeMillis()
            trainSpeed = k
            invalidate()
        }

        /** Время схемы в секундах от начала суток (с долями) - без Calendar в каждом кадре. */
        private fun clockSec(): Double {
            if (clockBaseUp == 0L) {
                clockBaseSec = realNowSec()
                clockBaseUp = android.os.SystemClock.uptimeMillis()
            }
            val t = clockBaseSec + (android.os.SystemClock.uptimeMillis() - clockBaseUp) / 1000.0 * trainSpeed.coerceAtLeast(1)
            return ((t % 86_400.0) + 86_400.0) % 86_400.0
        }

        /** Интервал движения - раз в 15 с, а не в каждом кадре. */
        private var headwayAt = 0L
        private var headwayVal: Int? = null
        private fun headwayNow(): Int? {
            val now = android.os.SystemClock.uptimeMillis()
            if (headwayAt == 0L || now - headwayAt > 15_000) {
                headwayVal = MetroTrains.headway()
                headwayAt = now
            }
            return headwayVal
        }

        private val trainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val trainBuf = MetroTrains.Buf()

        var route: Route? = null
            set(v) {
                field = v
                java.util.Arrays.fill(onRoute, false)
                v?.path?.forEach { if (it in 0 until n) onRoute[it] = true }
                highlightLine = null
                v?.path?.let { fitTo(it, animate = true) }
                // Маршрут «прорисовывается» от начала к концу.
                routeAnim?.cancel()
                routeProgress = 1f
                if (v != null && Kinetics.enabled) {
                    routeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 1100
                        startDelay = 250
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        // Маршрут рисуется поверх записанной схемы - вся схема не перерисовывается.
                        addUpdateListener { routeProgress = it.animatedValue as Float; invalidate() }
                        start()
                    }
                    routeProgress = 0f
                }
                stateChanged()
            }
        private var routeProgress = 1f
        private var routeAnim: android.animation.ValueAnimator? = null
        private var camAnim: android.animation.ValueAnimator? = null
        var onStation: ((Int) -> Unit)? = null
        /** Удержание на станции - карточка с информацией. */
        var onStationInfo: ((Int) -> Unit)? = null

        private fun stateChanged() {
            for (i in 0 until n) picked[i] = onRoute[i] || i == from || i == to
            // Подписи выбранных станций - первыми (порядок считается при изменении, не в каждом кадре).
            labelOrder = if (from < 0 && to < 0 && route == null) {
                labelOrderBase
            } else {
                val out = IntArray(n)
                var j = 0
                for (i in labelOrderBase) if (picked[i]) out[j++] = i
                for (i in labelOrderBase) if (!picked[i]) out[j++] = i
                out
            }
            stateVer++
            invalidate()
        }

        fun stationAt(x: Float, y: Float): Int {
            var best = -1
            var bestD = 26 * d
            for (i in 0 until n) {
                val dd = hypot(px[i] * scale + tx - x, py[i] * scale + ty - y)
                if (dd < bestD) {
                    bestD = dd
                    best = i
                }
            }
            return best
        }
        private val scroller = android.widget.OverScroller(ctx)
        private var lastFlingX = 0
        private var lastFlingY = 0
        private val start = android.os.SystemClock.uptimeMillis()

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val casing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = ContextCompat.getColor(ctx, R.color.colorBackground)
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11 * d
            color = ContextCompat.getColor(ctx, R.color.textPrimary)
        }
        private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (ContextCompat.getColor(ctx, R.color.colorBackground) and 0x00FFFFFF) or 0xC8000000.toInt()
        }
        private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10 * d
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val badgeW = FloatArray(lines.size) { maxOf(18 * d, badgePaint.measureText(badgeText[it]) + 10 * d) }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0x0FFFFFFF else 0x0D000000
            strokeWidth = 1f
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val dashLen = floatArrayOf(10f * d, 7f * d)
        private val dotDash = android.graphics.DashPathEffect(floatArrayOf(3f * d, 2.5f * d), 0f)

        /** Пунктир строящихся линий в координатах схемы - пересоздаётся только при смене масштаба. */
        private var dashWorld: android.graphics.DashPathEffect? = null
        private var dashWorldScale = 0f

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                camAnim?.cancel()
                val ms = minScale()
                val ns = (scale * det.scaleFactor).coerceIn(ms * 0.8f, ms * 14f)
                val f = ns / scale
                tx = det.focusX - (det.focusX - tx) * f
                ty = det.focusY - (det.focusY - ty) * f
                scale = ns
                invalidate()
                return true
            }
        })
        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                camAnim?.cancel()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                tx -= dx
                ty -= dy
                invalidate()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                lastFlingX = 0
                lastFlingY = 0
                scroller.fling(0, 0, vx.toInt(), vy.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
                postInvalidateOnAnimation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomAnimated(2f, e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val best = stationAt(e.x, e.y)
                if (best >= 0) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onStationInfo?.invoke(best)
                }
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val best = stationAt(e.x, e.y)
                if (best >= 0) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onStation?.invoke(best)
                }
                return true
            }
        })

        private fun minScale(): Float {
            val w = (maxX - minX).coerceAtLeast(1f)
            val h = (maxY - minY).coerceAtLeast(1f)
            return minOf(width / w, height / h) * 0.92f
        }

        // Координаты для рисования: сдвиг (ox, oy) - камера при обычной отрисовке или начало слоя при записи.
        private var ox = 0f
        private var oy = 0f
        private var cullL = 0f
        private var cullT = 0f
        private var cullR = 0f
        private var cullB = 0f
        private fun sx(i: Int) = px[i] * scale + ox
        private fun sy(i: Int) = py[i] * scale + oy
        private fun visible(x: Float, y: Float, m: Float) = x >= cullL - m && y >= cullT - m && x <= cullR + m && y <= cullB + m

        /** Отрезок (по описанному прямоугольнику) задевает экран - длинный перегон при сильном приближении тоже. */
        private fun segVisible(ax: Float, ay: Float, bx: Float, by: Float, m: Float) =
            maxOf(ax, bx) >= cullL - m && minOf(ax, bx) <= cullR + m && maxOf(ay, by) >= cullT - m && minOf(ay, by) <= cullB + m

        /** Рисуем прямо на экран: камера и отсечение по краям экрана. */
        private fun setDirect() {
            ox = tx
            oy = ty
            cullL = 0f
            cullT = 0f
            cullR = width.toFloat()
            cullB = height.toFloat()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (scale == 0f || oldw == 0) resetView(animate = false)
        }

        // Сколько схемы закрыто сверху (поля «Откуда/Куда», линии) и снизу (карточка маршрута).
        private var padTop = 0f
        private var padBottom = 0f
        /** Пользователь уже двигал схему - не перестраиваем вид сами. */
        private var touched = false

        fun setInsets(top: Float, bottom: Float) {
            if (top == padTop && bottom == padBottom) return
            padTop = top
            padBottom = bottom
            if (!touched && route == null && highlightLine == null) resetView(animate = false)
        }

        /** Свободная часть экрана - между шапкой и карточкой снизу. */
        private fun freeTop() = padTop.coerceIn(0f, height * 0.45f)
        private fun freeHeight() = (height - freeTop() - padBottom.coerceIn(0f, height * 0.4f)).coerceAtLeast(height * 0.35f)

        /** Вся схема целиком - сразу видны все линии и станции (как при открытии). */
        fun resetView(animate: Boolean = true) {
            if (width == 0 || n == 0) return
            val w = (maxX - minX).coerceAtLeast(1f)
            val h = (maxY - minY).coerceAtLeast(1f)
            // Справа - кнопки масштаба, по краям - небольшие поля.
            val availW = width - 24 * d
            val availH = freeHeight() - 12 * d
            val ns = minOf(availW / w, availH / h).coerceAtLeast(minScale() * 0.8f)
            val cx = width / 2f
            val cy = freeTop() + freeHeight() / 2f
            animateCamera(ns, cx - (minX + maxX) / 2f * ns, cy - (minY + maxY) / 2f * ns, animate)
        }

        private fun animateCamera(ns: Float, ntx: Float, nty: Float, animate: Boolean) {
            camAnim?.cancel()
            if (!animate || !Kinetics.enabled || scale == 0f) {
                scale = ns
                tx = ntx
                ty = nty
                invalidate()
                return
            }
            val s0 = scale
            val x0 = tx
            val y0 = ty
            camAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    // Масштаб - в логарифме, иначе приближение выглядит рывком.
                    scale = (s0 * Math.pow((ns / s0).toDouble(), f.toDouble())).toFloat()
                    // Точка мира в центре экрана движется плавно от старой к новой.
                    val wx0 = (width / 2f - x0) / s0
                    val wy0 = (height / 2f - y0) / s0
                    val wx1 = (width / 2f - ntx) / ns
                    val wy1 = (height / 2f - nty) / ns
                    val wx = wx0 + (wx1 - wx0) * f
                    val wy = wy0 + (wy1 - wy0) * f
                    tx = width / 2f - wx * scale
                    ty = height / 2f - wy * scale
                    invalidate()
                }
                start()
            }
        }

        fun zoomAnimated(f: Float, x: Float = width / 2f, y: Float = height / 2f) {
            if (scale == 0f) return
            val ms = minScale()
            val ns = (scale * f).coerceIn(ms * 0.8f, ms * 14f)
            val ff = ns / scale
            animateCamera(ns, x - (x - tx) * ff, y - (y - ty) * ff, true)
        }

        fun focusAnimated(id: Int) {
            if (id !in 0 until n || width == 0) return
            val ns = maxOf(scale, minScale() * 4f)
            // В центр свободной части экрана, а не под шапку.
            val cy = if (padTop > 0f) freeTop() + freeHeight() / 2f else height / 2f
            animateCamera(ns, width / 2f - px[id] * ns, cy - py[id] * ns, true)
        }

        /** Показать весь маршрут (или линию) целиком. */
        private fun fitTo(ids: List<Int>, animate: Boolean) {
            if (width == 0 || ids.isEmpty()) return
            val minX = ids.minOf { px[it] }
            val maxX = ids.maxOf { px[it] }
            val minY = ids.minOf { py[it] }
            val maxY = ids.maxOf { py[it] }
            val w = (maxX - minX).coerceAtLeast(1500f)
            val h = (maxY - minY).coerceAtLeast(1500f)
            // Сверху - шапка, снизу - карточка маршрута: вписываем в свободную часть.
            val avail = if (padTop > 0f) freeHeight() else height * 0.5f
            val ns = minOf(width / w, avail / h) * 0.85f
            val cy = if (padTop > 0f) freeTop() + freeHeight() / 2f else height * 0.48f
            animateCamera(ns, width / 2f - (minX + maxX) / 2f * ns, cy - (minY + maxY) / 2f * ns, animate)
        }

        fun stop() {
            routeAnim?.cancel()
            camAnim?.cancel()
            scroller.forceFinished(true)
            removeCallbacks(tick)
            tickPosted = false
            releaseLayers()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            touched = true
            scaler.onTouchEvent(event)
            if (!scaler.isInProgress) gestures.onTouchEvent(event)
            return true
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) {
                tx += scroller.currX - lastFlingX
                ty += scroller.currY - lastFlingY
                lastFlingX = scroller.currX
                lastFlingY = scroller.currY
                postInvalidateOnAnimation()
            }
        }

        // ---------- Кадры ----------

        /**
         * Один отложенный кадр на всех. Раньше поезда и пульсация меток ставили каждый свой
         * postInvalidateDelayed в каждом кадре - отложенных кадров становилось всё больше,
         * и схема перерисовывалась чаще, чем нужно (и грела телефон).
         */
        private var tickPosted = false
        private val tick = Runnable {
            tickPosted = false
            invalidate()
        }

        private fun scheduleFrame(ms: Long) {
            if (tickPosted || !isAttachedToWindow || windowVisibility != View.VISIBLE) return
            tickPosted = true
            postDelayed(tick, ms)
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == View.VISIBLE) invalidate()
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(tick)
            tickPosted = false
            releaseLayers()
        }

        // ---------- Записанные слои ----------

        private val below = Layer()
        private val aboveStations = Layer()
        private val aboveLabels = Layer()
        private var layerVer = -1
        private var layerScale = 0f
        private var layerW = 0
        private var layerH = 0
        private var layerLeft = 0f
        private var layerTop = 0f
        private var recordBroken = false
        private var lastVer = -1
        private var lastScale = 0f
        private var lastW = 0
        private var lastH = 0

        private fun releaseLayers() {
            below.release()
            aboveStations.release()
            aboveLabels.release()
            layerVer = -1
        }

        /** Поля вокруг схемы при записи: подписи справа/слева, значки линий сверху. */
        private val layerPad get() = 320 * d

        private fun recordLayers(hw: Boolean, ms: Float) {
            val pad = layerPad
            layerLeft = kotlin.math.floor(minX * scale - pad)
            layerTop = kotlin.math.floor(minY * scale - pad)
            val w = kotlin.math.ceil(maxX * scale + pad - layerLeft).toInt().coerceAtLeast(1)
            val h = kotlin.math.ceil(maxY * scale + pad - layerTop).toInt().coerceAtLeast(1)
            ox = -layerLeft
            oy = -layerTop
            // Записываем схему целиком - без отсечения по экрану.
            cullL = -Float.MAX_VALUE / 4
            cullT = -Float.MAX_VALUE / 4
            cullR = Float.MAX_VALUE / 4
            cullB = Float.MAX_VALUE / 4
            try {
                below.record(hw, w, h) { drawBelow(it) }
                aboveStations.record(hw, w, h) { drawStations(it, ms) }
                aboveLabels.record(hw, w, h) { drawLabels(it, ms) }
                layerVer = stateVer
                layerScale = scale
                layerW = width
                layerH = height
            } catch (_: RuntimeException) {
                // Не удалось записать (например, слишком большая схема для этого устройства) -
                // дальше рисуем как обычно и больше не пытаемся.
                releaseLayers()
                recordBroken = true
            }
            setDirect()
        }

        // ---------- Отрисовка ----------

        // Общие для кадра величины.
        private var lw = 0f
        private var dim = false

        private fun isFaded(li: Int) = dim && (hlGroup < 0 || lineGroup[li] != hlGroup)

        override fun onDraw(canvas: Canvas) {
            if (scale == 0f || n == 0) return
            val ms = minScale()
            dim = route != null || highlightLine != null
            lw = (2.2f * d + scale * 60f).coerceIn(3f * d, 9f * d)
            setDirect()
            drawGrid(canvas)

            // Неподвижная часть схемы (линии, станции, подписи) записывается, когда масштаб и
            // выбор не меняются два кадра подряд: дальше кадры поездов, пульсации меток,
            // прорисовки маршрута и перемещения пальцем её только повторяют со сдвигом.
            // Во время щипка (масштаб меняется каждый кадр) - рисуем как обычно.
            val hw = canvas.isHardwareAccelerated
            val canRecord = !recordBroken && (!hw || android.os.Build.VERSION.SDK_INT >= 23)
            val fresh = layerVer == stateVer && layerScale == scale && layerW == width && layerH == height && below.ready
            val stable = lastVer == stateVer && lastScale == scale && lastW == width && lastH == height
            lastVer = stateVer
            lastScale = scale
            lastW = width
            lastH = height
            var cached = false
            if (canRecord && (fresh || stable)) {
                if (!fresh) recordLayers(hw, ms)
                cached = layerVer == stateVer
            } else if (!fresh && layerVer != -1) {
                // Масштаб или выбор меняются - старые слои больше не понадобятся.
                releaseLayers()
            }
            val dx = tx + layerLeft
            val dy = ty + layerTop

            if (!(cached && below.draw(canvas, dx, dy))) drawBelow(canvas)
            var trainDelay = Long.MAX_VALUE
            if (showTrains) trainDelay = drawTrains(canvas, ms)
            drawRoute(canvas)
            if (!(cached && aboveStations.draw(canvas, dx, dy))) drawStations(canvas, ms)
            val pulse = drawPulse(canvas)
            if (!(cached && aboveLabels.draw(canvas, dx, dy))) drawLabels(canvas, ms)

            var next = trainDelay
            if (pulse) next = minOf(next, com.stoyko.smessenger.ui.LiteMode.liveFrameMs)
            if (next != Long.MAX_VALUE) scheduleFrame(next)
        }

        /** Лёгкая сетка - схема «на бумаге». */
        private fun drawGrid(canvas: Canvas) {
            val step = 1000f * scale
            if (step <= 24 * d) return
            var gx = tx % step
            while (gx < width) {
                canvas.drawLine(gx, 0f, gx, height.toFloat(), grid)
                gx += step
            }
            var gy = ty % step
            while (gy < height) {
                canvas.drawLine(0f, gy, width.toFloat(), gy, grid)
                gy += step
            }
        }

        /**
         * Линии в координатах схемы - строятся один раз; при перемещении и масштабе холст
         * просто сдвигается одним преобразованием на все линии.
         */
        private val worldPaths: Array<Path> by lazy {
            Array(lines.size) { li ->
                Path().also { buildSmooth(it, lines[li].stations, lines[li].ring) }
            }
        }

        /** Плавная линия через станции (сглаживание углов). */
        private fun buildSmooth(p: Path, ids: IntArray, ring: Boolean) {
            p.reset()
            val cnt = ids.size
            if (cnt == 0) return
            fun fx(i: Int) = px[i]
            fun fy(i: Int) = py[i]
            if (cnt < 3) {
                p.moveTo(fx(ids[0]), fy(ids[0]))
                for (i in 1 until cnt) p.lineTo(fx(ids[i]), fy(ids[i]))
                return
            }
            if (ring) {
                p.moveTo((fx(ids[cnt - 1]) + fx(ids[0])) / 2f, (fy(ids[cnt - 1]) + fy(ids[0])) / 2f)
                for (i in 0 until cnt) {
                    val a = ids[i]
                    val b = ids[(i + 1) % cnt]
                    p.quadTo(fx(a), fy(a), (fx(a) + fx(b)) / 2f, (fy(a) + fy(b)) / 2f)
                }
                p.close()
            } else {
                p.moveTo(fx(ids[0]), fy(ids[0]))
                for (i in 1 until cnt - 1) {
                    val a = ids[i]
                    val b = ids[i + 1]
                    p.quadTo(fx(a), fy(a), (fx(a) + fx(b)) / 2f, (fy(a) + fy(b)) / 2f)
                }
                p.lineTo(fx(ids[cnt - 1]), fy(ids[cnt - 1]))
            }
        }

        /** Линии (подложка цвета фона и цвет линии) и перемычки пересадок. */
        private fun drawBelow(canvas: Canvas) {
            val w = lw
            val hl = highlightLine
            val inv = 1f / scale
            if (dashWorldScale != scale || dashWorld == null) {
                dashWorld = android.graphics.DashPathEffect(floatArrayOf(dashLen[0] * inv, dashLen[1] * inv), 0f)
                dashWorldScale = scale
            }
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(scale, scale)
            for (pass in 0..1) {
                for (li in lines.indices) {
                    val l = lines[li]
                    val faded = isFaded(li)
                    val wp = worldPaths[li]
                    if (pass == 0) {
                        if (faded || l.planned != null) continue
                        casing.strokeWidth = (w + 3 * d) * inv
                        canvas.drawPath(wp, casing)
                        continue
                    }
                    linePaint.color = l.color
                    var sw = if (hl != null && !faded) w * 1.4f else w
                    if (l.planned == null) {
                        linePaint.alpha = if (faded) 40 else 255
                    } else {
                        // Строящиеся участки - пунктиром и бледнее.
                        linePaint.alpha = if (faded) 40 else 150
                        linePaint.pathEffect = dashWorld
                    }
                    // МЦД (диаметры) - тоньше и с белой полосой по центру, как на схеме.
                    val mcd = isMcd[li]
                    if (mcd) sw *= 0.8f
                    linePaint.strokeWidth = sw * inv
                    canvas.drawPath(wp, linePaint)
                    if (mcd && !faded) {
                        linePaint.color = Color.WHITE
                        linePaint.strokeWidth = sw * 0.36f * inv
                        canvas.drawPath(wp, linePaint)
                    }
                    linePaint.pathEffect = null
                }
            }
            canvas.restore()
            // Пересадки - серые «перемычки» с белой серединой.
            val grey = if (dark) 0xFF9CA3AF.toInt() else 0xFF6B7280.toInt()
            var k = 0
            while (k < links.size) {
                val a = links[k]
                val b = links[k + 1]
                k += 2
                val ax = sx(a)
                val ay = sy(a)
                val bx = sx(b)
                val by = sy(b)
                if (!segVisible(ax, ay, bx, by, w * 2)) continue
                val hi = onRoute[a] && onRoute[b]
                val alpha = if (dim && !hi) 60 else 255
                linePaint.color = grey
                linePaint.alpha = alpha
                linePaint.strokeWidth = w * 1.5f
                canvas.drawLine(ax, ay, bx, by, linePaint)
                linePaint.color = Color.WHITE
                linePaint.alpha = alpha
                linePaint.strokeWidth = w * 0.6f
                canvas.drawLine(ax, ay, bx, by, linePaint)
            }
        }

        /**
         * Поезда: настоящие составы из вагонов (цвет линии, полоса окон, кабина с фарами),
         * повёрнуты по ходу движения и едут плавно по расписанию. Возвращает, через сколько мс
         * нужен следующий кадр.
         */
        private fun drawTrains(canvas: Canvas, ms: Float): Long {
            val h = headwayNow() ?: return 30_000L // метро закрыто - проверим позже, без кадров впустую
            val clock = clockSec()
            val now = clock.toInt()
            val frac = (clock - now).toFloat()
            val w = lw
            val r = (w * 1.05f).coerceAtLeast(3.5f * d)
            val m = r * 4
            var shown = 0
            for (li in lines.indices) {
                val l = lines[li]
                if (l.planned != null || isFaded(li)) continue
                trainBuf.clear()
                MetroTrains.collect(l, now, h, frac, trainBuf)
                val ta = trainBuf.a
                val tb = trainBuf.b
                val tf = trainBuf.f
                for (j in 0 until trainBuf.n) {
                    val a = ta[j]
                    val b = tb[j]
                    val ax0 = sx(a)
                    val ay0 = sy(a)
                    val x = ax0 + (sx(b) - ax0) * tf[j]
                    val y = ay0 + (sy(b) - ay0) * tf[j]
                    if (!visible(x, y, m)) continue
                    // Направление: по перегону; на конечной - к соседней станции.
                    var ax = sx(b) - ax0
                    var ay = sy(b) - ay0
                    if (a == b) {
                        val kk = l.stations.indexOf(a)
                        val nb = l.stations.getOrNull(if (kk > 0) kk - 1 else kk + 1) ?: a
                        ax = ax0 - sx(nb)
                        ay = ay0 - sy(nb)
                    }
                    val ang = Math.toDegrees(kotlin.math.atan2(ay, ax).toDouble()).toFloat()
                    drawTrain(canvas, x, y, ang, r, l.color)
                    shown++
                }
            }
            // Как часто нужен кадр: столько, чтобы поезд сдвинулся примерно на полпикселя.
            // По реальному времени на общей схеме это раз в секунду, а не 11 раз.
            val energy = !Kinetics.enabled || com.stoyko.smessenger.ui.EnergySaver.on
            val zoomed = scale > ms * 2f
            val floor = when {
                energy -> 250L
                com.stoyko.smessenger.ui.LiteMode.on -> if (zoomed) 80L else 125L
                else -> if (zoomed) 50L else 90L
            }
            val pxPerSec = TRAIN_MPS * trainSpeed.coerceAtLeast(1) * scale
            val need = if (pxPerSec > 0f) (500f / pxPerSec).toLong() else 1000L
            var delay = need.coerceIn(floor, 1000L)
            if (shown == 0) delay = maxOf(delay, 250L)
            return delay
        }

        private val trainRect = android.graphics.RectF()

        /** Состав из трёх вагонов: длина ~ 4 радиуса, головной вагон с лобовым стеклом и фарами. */
        private fun drawTrain(c: Canvas, x: Float, y: Float, angle: Float, r: Float, color: Int) {
            val carL = r * 1.45f
            val carW = r * 1.25f
            val gap = r * 0.14f
            val total = carL * 3 + gap * 2
            val detail = r > 3.2f * d
            c.save()
            c.rotate(angle, x, y)
            val x0 = x - total / 2
            if (!detail) {
                // На общей схеме вагоны мелкие: тень и белая кайма - одним контуром на весь
                // состав (на глаз то же самое, но вдвое меньше команд рисования на поезд).
                trainPaint.color = 0x33000000
                c.drawRoundRect(x0, y - carW / 2 + r * 0.12f, x0 + total, y + carW / 2 + r * 0.12f, carW * 0.35f, carW * 0.35f, trainPaint)
                trainPaint.color = Color.WHITE
                c.drawRoundRect(x0 - r * 0.1f, y - carW / 2 - r * 0.1f, x0 + total + r * 0.1f, y + carW / 2 + r * 0.1f, carW * 0.4f, carW * 0.4f, trainPaint)
            }
            var left = x0
            for (k in 0 until 3) {
                trainRect.set(left, y - carW / 2, left + carL, y + carW / 2)
                val head = k == 2
                if (detail) {
                    // Тень и белая кайма - поезд читается на любой линии.
                    trainPaint.color = 0x33000000
                    c.drawRoundRect(trainRect.left, trainRect.top + r * 0.12f, trainRect.right, trainRect.bottom + r * 0.12f, carW * 0.35f, carW * 0.35f, trainPaint)
                    trainPaint.color = Color.WHITE
                    c.drawRoundRect(trainRect.left - r * 0.1f, trainRect.top - r * 0.1f, trainRect.right + r * 0.1f, trainRect.bottom + r * 0.1f, carW * 0.4f, carW * 0.4f, trainPaint)
                }
                trainPaint.color = color
                val rx = if (head) carW * 0.5f else carW * 0.3f
                c.drawRoundRect(trainRect, rx, rx, trainPaint)
                if (detail) {
                    // Полоса окон.
                    trainPaint.color = 0xCCE0F2FE.toInt()
                    val wy = carW * 0.17f
                    c.drawRect(trainRect.left + carL * 0.12f, y - wy, trainRect.right - carL * (if (head) 0.32f else 0.12f), y + wy, trainPaint)
                    if (head) {
                        // Лобовое стекло и фары.
                        trainPaint.color = 0xFF1E293B.toInt()
                        c.drawRoundRect(trainRect.right - carL * 0.28f, y - carW * 0.3f, trainRect.right - carL * 0.06f, y + carW * 0.3f, carW * 0.12f, carW * 0.12f, trainPaint)
                        trainPaint.color = 0xFFFDE047.toInt()
                        c.drawCircle(trainRect.right - carL * 0.04f, y - carW * 0.36f, carW * 0.08f, trainPaint)
                        c.drawCircle(trainRect.right - carL * 0.04f, y + carW * 0.36f, carW * 0.08f, trainPaint)
                    }
                }
                left += carL + gap
            }
            c.restore()
        }

        /** Маршрут - поверх, толще, со свечением; «прорисовывается» по времени. */
        private fun drawRoute(canvas: Canvas) {
            val r = route ?: return
            val w = lw
            val segs = r.path.size - 1
            val shown = routeProgress * segs
            for (i in 0 until segs) {
                if (i >= shown) break
                val a = r.path[i]
                val b = r.path[i + 1]
                val part = (shown - i).coerceAtMost(1f)
                val ax = sx(a)
                val ay = sy(a)
                val ex = ax + (sx(b) - ax) * part
                val ey = ay + (sy(b) - ay) * part
                if (!segVisible(ax, ay, ex, ey, w * 4)) continue
                val ride = data.stations[a].line == data.stations[b].line
                val c = if (ride) stColor[a] else 0xFF9CA3AF.toInt()
                glow.color = c
                glow.alpha = 70
                glow.strokeWidth = w * 3.2f
                canvas.drawLine(ax, ay, ex, ey, glow)
                linePaint.color = c
                linePaint.alpha = 255
                linePaint.strokeWidth = if (ride) w * 1.8f else w * 0.9f
                canvas.drawLine(ax, ay, ex, ey, linePaint)
            }
        }

        private fun badgeAt(canvas: Canvas, li: Int, end: Int, faded: Boolean) {
            val x = sx(end)
            val y = sy(end)
            if (!visible(x, y, 30f)) return
            val bw = badgeW[li]
            dot.color = lines[li].color
            dot.alpha = if (faded) 60 else 255
            val by = y - 16 * d
            canvas.drawRoundRect(x - bw / 2, by - 9 * d, x + bw / 2, by + 9 * d, 9 * d, 9 * d, dot)
            badgePaint.alpha = dot.alpha
            canvas.drawText(badgeText[li], x, by + 3.5f * d, badgePaint)
        }

        private fun highlighted(i: Int) = picked[i] || (hlGroup >= 0 && stGroup[i] == hlGroup)

        /** Значки номеров на концах линий и кружки станций (только видимые). */
        private fun drawStations(canvas: Canvas, ms: Float) {
            val w = lw
            if (scale > ms * 1.2f) {
                for (li in lines.indices) {
                    val l = lines[li]
                    if (l.ring || l.id.endsWith("A") || l.stations.isEmpty()) continue
                    val faded = isFaded(li)
                    badgeAt(canvas, li, l.stations.first(), faded)
                    badgeAt(canvas, li, l.stations.last(), faded)
                }
            }
            val r0 = w * 0.75f
            ring.strokeWidth = w * 0.45f
            for (i in 0 until n) {
                val x = sx(i)
                val y = sy(i)
                if (!visible(x, y, 50f)) continue
                val alpha = if (dim && !highlighted(i)) 70 else 255
                val rr = if (transfer[i]) r0 * 1.3f else r0
                val building = stBuilding[i]
                dot.color = if (building) casing.color else Color.WHITE
                dot.alpha = alpha
                ring.color = stColor[i]
                ring.alpha = if (building) alpha * 3 / 5 else alpha
                ring.pathEffect = if (building) dotDash else null
                canvas.drawCircle(x, y, rr, dot)
                canvas.drawCircle(x, y, rr, ring)
            }
            ring.pathEffect = null
        }

        /** «Откуда» и «Куда» - пульсирующее кольцо. Возвращает true, если нужен следующий кадр. */
        private fun drawPulse(canvas: Canvas): Boolean {
            if (from < 0 && to < 0) return false
            val live = Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on
            if (!live) return false
            val lite = com.stoyko.smessenger.ui.LiteMode.on
            val r0 = lw * 0.75f
            // В лёгком режиме - неподвижный ореол вместо постоянной анимации.
            val t = if (lite) 0.45f else ((android.os.SystemClock.uptimeMillis() - start) % 1600L) / 1600f
            for (which in 0..1) {
                val sid = if (which == 0) from else to
                if (sid !in 0 until n) continue
                glow.color = if (which == 0) 0xFF22C55E.toInt() else 0xFFE5484D.toInt()
                glow.alpha = ((1f - t) * 160).toInt()
                glow.strokeWidth = 3 * d
                canvas.drawCircle(sx(sid), sy(sid), r0 * 1.6f + t * 18 * d, glow)
            }
            return !lite
        }

        // Подписи без наложений: важные первыми, остальные - если есть место. Проверка наложений -
        // по полосам высотой в строку (а не со всеми уже поставленными подписями подряд).
        private val rowH get() = 24 * d
        private val rows = ArrayList<ArrayList<android.graphics.RectF>>()
        private val rectPool = ArrayList<android.graphics.RectF>()
        private var rowBase = 0f

        private fun rowOf(y: Float) = ((y - rowBase) / rowH).toInt().coerceIn(0, rows.size - 1)

        private fun collides(r: android.graphics.RectF): Boolean {
            for (k in rowOf(r.top)..rowOf(r.bottom)) {
                val list = rows[k]
                for (j in list.indices) if (android.graphics.RectF.intersects(list[j], r)) return true
            }
            return false
        }

        /** Метки «откуда/куда» и подписи станций. */
        private fun drawLabels(canvas: Canvas, ms: Float) {
            val r0 = lw * 0.75f
            for (which in 0..1) {
                val sid = if (which == 0) from else to
                if (sid !in 0 until n) continue
                val x = sx(sid)
                val y = sy(sid)
                dot.color = if (which == 0) 0xFF22C55E.toInt() else 0xFFE5484D.toInt()
                dot.alpha = 255
                canvas.drawCircle(x, y, r0 * 1.7f, dot)
                dot.color = Color.WHITE
                canvas.drawCircle(x, y, r0 * 0.8f, dot)
            }
            // Пересадочные станции подписаны уже на общей схеме (мельче), остальные - при приближении.
            val showLabels = scale > ms * 0.75f
            val all = scale >= ms * 3.2f
            if (!showLabels && !picked.any { it } && hlGroup < 0) return
            val small = scale < ms * 2.6f
            label.textSize = (if (small) 9.5f else 11f) * d
            // Полосы для проверки наложений - по высоте всей схемы (подписи считаются для всей
            // схемы, а не только видимой части: при перемещении они не «прыгают»).
            rowBase = minY * scale + oy - layerPad
            val rowCount = (((maxY - minY) * scale + 2 * layerPad) / rowH).toInt() + 2
            while (rows.size < rowCount) rows.add(ArrayList())
            for (k in 0 until rowCount) rows[k].clear()
            var used = 0
            val pad3 = 3 * d
            val gap4 = 4 * d
            for (i in labelOrder) {
                val highlight = highlighted(i)
                if (!highlight && (!showLabels || dim)) continue
                if (!highlight && !transfer[i] && !all) continue
                val x = sx(i)
                val y = sy(i)
                val bold = highlight || transfer[i]
                label.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val wi = (if (small) 0 else 2) + (if (bold) 1 else 0)
                var tw = labelW[wi][i]
                if (tw < 0f) {
                    tw = label.measureText(labelText[i])
                    labelW[wi][i] = tw
                }
                val rr = if (transfer[i]) r0 * 1.3f else r0
                // Справа от станции; занято - слева.
                var lx = x + rr + gap4
                val ly = y + label.textSize * 0.35f
                if (used == rectPool.size) rectPool.add(android.graphics.RectF())
                val rect = rectPool[used]
                rect.set(lx - pad3, ly - label.textSize, lx + tw + pad3, ly + pad3)
                if (collides(rect)) {
                    lx = x - rr - gap4 - tw
                    rect.set(lx - pad3, ly - label.textSize, lx + tw + pad3, ly + pad3)
                    if (!highlight && collides(rect)) continue
                }
                used++
                for (k in rowOf(rect.top)..rowOf(rect.bottom)) rows[k].add(rect)
                if (rect.right < cullL || rect.left > cullR || rect.bottom < cullT || rect.top > cullB) continue
                labelBg.alpha = 200
                canvas.drawRoundRect(rect, 5 * d, 5 * d, labelBg)
                canvas.drawText(labelText[i], lx, ly, label)
            }
        }
    }
}
