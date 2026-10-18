// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.os.Build

/**
 * Определение модели телефона/планшета и подстройка интерфейса под неё (автоматически):
 *
 *  - Xiaomi: Xiaomi (флагманы), Xiaomi T, Xiaomi MIX (складные), Redmi Note, Redmi, Redmi A;
 *    планшеты Xiaomi Pad, Redmi Pad;
 *  - Huawei: Mate, Pura (бывшая P), nova, nova Y; планшеты MatePad, MatePad Air, MatePad Pro;
 *  - POCO: F, X, M, C; планшет POCO Pad;
 *  - Samsung: Galaxy Z Fold, Z Flip, S, A, M, Tab;
 *  - TECNO (Camon, Spark, Pova, Pop, Phantom), Infinix, itel; Honor; realme, OPPO, OnePlus;
 *    vivo/iQOO; Motorola; Google Pixel; Nokia; ZTE/nubia; Meizu; Sony.
 *
 * По модели выбирается класс устройства ([Kind]): бюджетный телефон (720p, мало памяти),
 * обычный, флагман, «раскладушка» (узкий высокий экран), складной, планшет. От класса зависят
 * предел системного шрифта, плотность интерфейса, режим планшета и облегчённые анимации.
 * Режим планшета можно включить/выключить вручную (Настройки → Оформление).
 */
object DeviceProfile {
    enum class Kind { BUDGET, PHONE, FLAGSHIP, FLIP, FOLD, TABLET }

    class Info(val brand: String, val family: String, val kind: Kind, val name: String)

    @Volatile
    private var cached: Info? = null

    /** Коммерческое имя («Redmi Note 13 Pro», «HUAWEI nova Y61»…): у многих фирм оно в системных свойствах. */
    private fun marketName(): String {
        val keys = listOf("ro.product.marketname", "ro.product.vendor.marketname", "ro.vendor.product.display", "ro.config.marketing_name", "ro.product.odm.marketname")
        for (k in keys) {
            val v = runCatching {
                val c = Class.forName("android.os.SystemProperties")
                c.getMethod("get", String::class.java).invoke(null, k) as? String
            }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
        return ""
    }

    fun get(ctx: Context): Info = cached ?: detect(ctx).also { cached = it }

    private fun detect(ctx: Context): Info {
        val brand = (Build.BRAND ?: "").lowercase()
        val maker = (Build.MANUFACTURER ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val market = marketName()
        val name = (market.ifBlank { Build.MODEL ?: "" }).trim()
        // POCO без «рыночного» имени в свойствах (часть прошивок HyperOS/MIUI) - по коду модели.
        val pocoCode = POCO_CODES.entries.firstOrNull { model.equals(it.key, true) }?.value.orEmpty()
        val n = (market + " " + model + " " + pocoCode).lowercase()
        val sw = ctx.resources.configuration.smallestScreenWidthDp
        val dm = ctx.resources.displayMetrics
        val aspect = maxOf(dm.widthPixels, dm.heightPixels).toFloat() / minOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
        val lowRam = runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
        }.getOrDefault(false)
        fun has(vararg p: String) = p.any { n.contains(it) }
        fun re(p: String) = Regex(p).containsMatchIn(n)

        var family = "other"
        var kind: Kind? = null
        when {
            // ---------- POCO ----------
            brand == "poco" || n.contains("poco") -> {
                family = "poco"
                kind = when {
                    has("poco pad") -> Kind.TABLET
                    // POCO F3…F8 - флагманские Snapdragon/Dimensity: полные анимации, 120 Гц.
                    re("poco f\\d") -> Kind.FLAGSHIP
                    // POCO X6 Pro, X7 Pro, X8 Pro / Pro Max - тоже быстрые.
                    re("poco x(3|[6-9]|\\d\\d) pro") -> Kind.FLAGSHIP
                    re("poco x\\d") -> Kind.PHONE
                    re("poco [mc]\\d") -> Kind.BUDGET
                    else -> null
                }
            }
            // ---------- Xiaomi / Redmi ----------
            maker == "xiaomi" || brand == "xiaomi" || brand == "redmi" || n.contains("redmi") -> {
                family = if (brand == "redmi" || n.contains("redmi")) "redmi" else "xiaomi"
                kind = when {
                    has("redmi pad", "xiaomi pad", "mi pad") -> Kind.TABLET
                    has("mix fold") -> Kind.FOLD
                    has("mix flip") -> Kind.FLIP
                    re("redmi a\\d|redmi \\d+a\\b|redmi \\d+c\\b") -> Kind.BUDGET
                    has("redmi note") -> Kind.PHONE
                    re("redmi \\d") -> Kind.PHONE
                    re("xiaomi \\d+t") -> Kind.FLAGSHIP
                    re("xiaomi \\d+|xiaomi mix|mi \\d+") -> Kind.FLAGSHIP
                    else -> null
                }
            }
            // ---------- Huawei ----------
            maker == "huawei" || brand == "huawei" -> {
                family = "huawei"
                kind = when {
                    has("matepad") -> Kind.TABLET
                    re("mate x|mate xs|mate xt") -> Kind.FOLD
                    has("pocket") -> Kind.FLIP
                    has("pura") || re("\\bp\\d{2}") -> Kind.FLAGSHIP
                    re("mate \\d") -> Kind.FLAGSHIP
                    has("nova y") -> Kind.BUDGET
                    has("nova") -> Kind.PHONE
                    // Коды моделей: планшеты (BAH, DBY, AGS, GOT…), бюджетные nova Y (MGA, FGD, CTR…).
                    re("^(bah|dby|ags|got|kob|scm|wgr|mrr)") || re("\\b(bah|dby|ags|got|kob|scm)\\d?-") -> Kind.TABLET
                    re("\\b(mga|fgd|ctr|evr|dra)-") -> Kind.BUDGET
                    else -> null
                }
            }
            // ---------- Samsung ----------
            maker == "samsung" || brand == "samsung" -> {
                family = "samsung"
                kind = when {
                    has("fold") || re("sm-f9\\d") -> Kind.FOLD
                    has("flip") || re("sm-f7\\d") -> Kind.FLIP
                    has("galaxy tab") || re("sm-[tx]\\d") -> Kind.TABLET
                    re("sm-s\\d|sm-g9\\d|sm-n9\\d") || has("galaxy s2", "galaxy s1") -> Kind.FLAGSHIP
                    re("sm-a0\\d|sm-a1\\d|sm-m0\\d|sm-m1\\d") -> Kind.BUDGET
                    re("sm-[am]\\d") -> Kind.PHONE
                    else -> null
                }
            }
            // ---------- TECNO / Infinix / itel (Transsion: HiOS, XOS) ----------
            maker == "tecno" || brand == "tecno" || maker.contains("tecno") -> {
                family = "tecno"
                kind = when {
                    has("megapad", "tecno pad") -> Kind.TABLET
                    has("phantom v fold") -> Kind.FOLD
                    has("phantom v flip") -> Kind.FLIP
                    has("phantom") -> Kind.FLAGSHIP
                    // Spark 6/7/8 и Go (KE, KF, KG, KD, KC… - Helio A25/G25/G35, 2-4 ГБ) - бюджетные (4.10.0).
                    has("spark go", "pop ") || re("\\b(bg|bf|bd|bb|be|kg|ke|kf|kd|kc|kb|ka)\\d") -> Kind.BUDGET
                    has("camon", "pova", "spark") || re("\\b(cl|ck|lh|li|lg|kl|kj)\\d") -> Kind.PHONE
                    else -> Kind.PHONE
                }
            }
            maker == "infinix" || brand == "infinix" -> {
                family = "infinix"
                kind = when {
                    has("xpad") -> Kind.TABLET
                    has("zero flip") -> Kind.FLIP
                    has("zero") -> Kind.FLAGSHIP
                    has("smart", "hot 30i", "hot 40i", "hot 50i") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            maker == "itel" || brand == "itel" -> {
                family = "itel"
                kind = if (has("pad")) Kind.TABLET else Kind.BUDGET
            }
            // ---------- Honor ----------
            maker == "honor" || brand == "honor" -> {
                family = "honor"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("magic v") -> Kind.FOLD
                    has("magic") -> Kind.FLAGSHIP
                    re("honor x[5-7]|play") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            // ---------- realme / OPPO / OnePlus ----------
            maker == "realme" || brand == "realme" -> {
                family = "realme"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("gt") -> Kind.FLAGSHIP
                    re("realme c\\d|note 50|narzo n") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            maker == "oppo" || brand == "oppo" -> {
                family = "oppo"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("find n") -> if (has("flip")) Kind.FLIP else Kind.FOLD
                    has("find x") -> Kind.FLAGSHIP
                    re("oppo a\\d|\\bcph2[0-4]") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            maker == "oneplus" || brand == "oneplus" -> {
                family = "oneplus"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("open") -> Kind.FOLD
                    // Nord N (N10, N100, N20, N200, N30…) - бюджетные.
                    re("nord n\\d") -> Kind.BUDGET
                    has("nord") -> Kind.PHONE
                    // OnePlus 5/5T/6/6T (Android 8-10, коды A5000…A6013): шустрые, но уже не новые.
                    re("oneplus a[56]0\\d\\d") -> Kind.PHONE
                    else -> Kind.FLAGSHIP
                }
            }
            // ---------- vivo / iQOO ----------
            maker == "vivo" || brand == "vivo" || brand == "iqoo" -> {
                family = "vivo"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("x fold") -> Kind.FOLD
                    re("vivo x\\d|iqoo \\d") -> Kind.FLAGSHIP
                    re("vivo y\\d") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            // ---------- Motorola, Nokia, Google, ZTE/nubia, Meizu, Sony ----------
            maker == "motorola" || brand == "motorola" -> {
                family = "motorola"
                kind = when {
                    has("razr") -> Kind.FLIP
                    has("edge") -> Kind.FLAGSHIP
                    has("tab") -> Kind.TABLET
                    re("moto e\\d|moto e\\b") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            maker == "google" || brand == "google" -> {
                family = "pixel"
                kind = when {
                    has("fold") -> Kind.FOLD
                    has("tablet") -> Kind.TABLET
                    re("pixel \\d+a") -> Kind.PHONE
                    else -> Kind.FLAGSHIP
                }
            }
            maker == "hmd global" || brand == "nokia" || maker == "nokia" -> {
                family = "nokia"
                kind = if (has("t20", "t21", "pad")) Kind.TABLET else Kind.BUDGET
            }
            maker == "zte" || brand == "zte" || brand == "nubia" -> {
                family = "zte"
                kind = when {
                    has("pad") -> Kind.TABLET
                    has("flip") -> Kind.FLIP
                    has("red magic", "nubia z") -> Kind.FLAGSHIP
                    has("blade a", "blade l") -> Kind.BUDGET
                    else -> Kind.PHONE
                }
            }
            maker == "meizu" || brand == "meizu" -> {
                family = "meizu"
                kind = Kind.PHONE
            }
            maker == "sony" || brand == "sony" -> {
                family = "sony"
                kind = if (has("xperia 1")) Kind.FLAGSHIP else Kind.PHONE
            }
        }
        // Неизвестная модель - по экрану и памяти.
        val k = kind ?: when {
            sw >= 600 -> Kind.TABLET
            aspect >= 2.35f && sw < 400 -> Kind.FLIP
            lowRam || dm.densityDpi <= 320 && sw <= 360 -> Kind.BUDGET
            else -> Kind.PHONE
        }
        // Складной телефон раскрыт - работает как планшет.
        val effective = if (k == Kind.FOLD && sw < 600) Kind.PHONE else k
        return Info(brand, family, effective, name)
    }

    /** Коды моделей POCO -> имя (если прошивка не отдаёт рыночное имя). */
    private val POCO_CODES = mapOf(
        "M2012K11AG" to "poco f3",
        "M2007J20CG" to "poco x3",
        "M2007J20CT" to "poco x3",
        "M2102J20SG" to "poco x3 pro",
        "M2102J20SI" to "poco x3 pro",
        "22021211RG" to "poco f4",
        "21121210G" to "poco f4 gt",
        "2201116PG" to "poco x4 pro",
        "22041216G" to "poco x4 gt",
        "23049PCD8G" to "poco f5",
        "23013PC75G" to "poco f5 pro",
        "22111317PG" to "poco x5",
        "22101320G" to "poco x5 pro",
        "24069PC21G" to "poco f6",
        "23113RKC6G" to "poco f6 pro",
        "23122PCD1G" to "poco x6",
        "2311DRK48G" to "poco x6 pro",
        "24095PCADG" to "poco x7",
        "24117RK2CG" to "poco x7 pro",
    )

    /** Предел системного шрифта для этого устройства (крупнее - вёрстка не помещается). */
    fun maxFontScale(ctx: Context): Float = when (get(ctx).kind) {
        Kind.BUDGET -> 1.2f
        Kind.FLIP -> 1.18f
        Kind.TABLET, Kind.FOLD -> 1.35f
        else -> 1.3f
    }

    /** Множитель плотности: планшеты - крупнее, узкие «раскладушки» - чуть плотнее. */
    fun densityBoost(ctx: Context): Float {
        val sw = ctx.resources.configuration.smallestScreenWidthDp
        return when (get(ctx).kind) {
            Kind.TABLET, Kind.FOLD -> if (sw >= 720) 1.1f else if (sw >= 600) 1.05f else 1f
            else -> 1f
        }
    }

    /** Слабое устройство: облегчённые анимации по умолчанию. */
    fun lite(ctx: Context): Boolean = get(ctx).kind == Kind.BUDGET

    /** Похоже на планшет (для режима «Авто» двух окон). */
    fun tabletLike(ctx: Context): Boolean = get(ctx).kind == Kind.TABLET || get(ctx).kind == Kind.FOLD

    /** Строка для «О приложении» / диагностики. */
    fun describe(ctx: Context): String {
        val i = get(ctx)
        val kind = ctx.getString(
            when (i.kind) {
                Kind.BUDGET -> com.stoyko.smessenger.R.string.device_kind_budget
                Kind.PHONE -> com.stoyko.smessenger.R.string.device_kind_phone
                Kind.FLAGSHIP -> com.stoyko.smessenger.R.string.device_kind_flagship
                Kind.FLIP -> com.stoyko.smessenger.R.string.device_kind_flip
                Kind.FOLD -> com.stoyko.smessenger.R.string.device_kind_fold
                Kind.TABLET -> com.stoyko.smessenger.R.string.device_kind_tablet
            },
        )
        return i.name + " · " + kind
    }
}
