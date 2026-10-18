import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Общий модуль StoykoMessenger: Android + iOS (Kotlin Multiplatform).
//
//  commonMain  - чистая логика: кодек и страница HTML-пузырей, правила моста, сезоны,
//                музыка темы (логика «заглушек»), контракты платформы (expect).
//  androidMain - actual для Android (java.util.zip, MediaPlayer, ConnectivityManager...).
//  iosMain     - actual для iOS (zlib, AVAudioPlayer, WKWebView, NWPathMonitor, BGTaskScheduler)
//                + экран на Compose Multiplatform и MainViewController для Swift.
//
// AGP 9: для KMP-библиотеки используется плагин com.android.kotlin.multiplatform.library
// и блок kotlin { androidLibrary { } } вместо android { } (старый com.android.library
// вместе с KMP в AGP 9 не работает).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// Системные фреймворки iOS, которые использует iosMain.
// Для статического фреймворка Xcode-приложение тоже линкует их (см. iosApp/project.yml).
val iosSystemFrameworks = listOf(
    "UIKit",
    "WebKit",
    "AVFoundation",
    "NetworkExtension",
    "OpenGLES",
    "Network",          // NWPathMonitor
    "BackgroundTasks",  // BGTaskScheduler
)

kotlin {
    androidLibrary {
        namespace = "com.stoyko.smessenger.shared"
        compileSdk = (providers.gradleProperty("sm.compileSdk").orNull ?: libs.versions.sdk.target.get()).toInt()
        minSdk = libs.versions.sdk.min.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosX64 (симулятор на Intel-Mac) ВЫКЛЮЧЕН по умолчанию: Compose Multiplatform 1.12
    // его больше не публикует. Включить можно только без Compose: -Psm.iosX64=true
    val withX64 = providers.gradleProperty("sm.iosX64").orNull == "true"
    val iosTargets = buildList {
        if (withX64) add(iosX64())
        add(iosArm64())
        add(iosSimulatorArm64())
    }
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            // Статический фреймворк: не нужно встраивать/подписывать отдельный .framework в .ipa.
            isStatic = true
            linkerOpts(iosSystemFrameworks.flatMap { listOf("-framework", it) } + listOf("-lz"))
        }
    }

    compilerOptions {
        // expect/actual классы (LoopPlayer, NetworkMonitor, KeyValueStore) - без предупреждений Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Compose Multiplatform - только для iOS: Android-приложение остаётся на своём
        // View-интерфейсе и не тянет Compose (и его minSdk) в APK.
        iosMain.dependencies {
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
        }
    }
}

// =============================================================================================
// Автоперенос чистого Kotlin из Android-модулей в commonMain.
//
// Файлы ниже не копируются руками: при каждой сборке задача syncPortedSources берёт их
// из app/ core/ domain/ (то есть всегда свежую версию Android-кода), заменяет немногие
// JVM-вызовы на мультиплатформенные и кладёт в build/generated/ported. Поменяли движок
// шахмат или уровни «Три в ряд» в Android - iOS получит то же самое при следующей сборке.
// =============================================================================================
val portedFiles = listOf(
    "app/src/main/kotlin/games/ChessEngine.kt",
    "app/src/main/kotlin/games/Match3Levels.kt",
    "app/src/main/kotlin/games/Match3Engine.kt",
    "app/src/main/kotlin/games/DurakEngine.kt",
    "core/src/main/kotlin/vo/PublicKey.kt",
    "domain/src/main/kotlin/tox/ToxTypes.kt",
    "domain/src/main/kotlin/tox/ToxIdValidator.kt",
    "domain/src/main/kotlin/tox/ToxConstants.kt",
)

/**
 * Убрать все функции, где в заголовке есть JSONObject/JSONArray (сохранение партий в org.json -
 * только Android). Тело с фигурными скобками вырезается по парным скобкам (строки "..." и
 * символы '.' пропускаются), тело-выражение "= ..." - до пустой строки.
 */
fun cutJsonFunctions(src: String): String {
    var s = src
    val head = Regex("(?m)^[ \\t]*(?:(?:private|internal|public|override)\\s+)*fun [^\\n]*JSON(?:Object|Array)[^\\n]*$")
    while (true) {
        val m = head.find(s) ?: break
        val start = m.range.first
        val brace = s.indexOf('{', start)
        val end: Int = if (brace in m.range) {
            var depth = 0
            var i = brace
            var inStr = false
            var inChar = false
            while (i < s.length) {
                val ch = s[i]
                when {
                    inStr -> { if (ch == '\\') i++ else if (ch == '"') inStr = false }
                    inChar -> { if (ch == '\\') i++ else if (ch == '\'') inChar = false }
                    ch == '"' -> inStr = true
                    ch == '\'' -> inChar = true
                    ch == '{' -> depth++
                    ch == '}' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 }
        } else {
            s.indexOf("\n\n", start).let { if (it < 0) s.length else it + 1 }
        }
        s = s.removeRange(start, end)
    }
    return s
}

fun portToCommon(name: String, src: String): String {
    var s = src.replace("\r\n", "\n")
    // Сохранение партии/уровня в org.json - только Android.
    s = cutJsonFunctions(s)
    // «Дурак»: SecureRandom + setSeed -> kotlin.random с тем же зерном (тасовка Фишера-Йетса не меняется).
    s = s.replace(Regex("val rnd = java\\.security\\.SecureRandom\\(\\)\\s*\\n\\s*rnd\\.setSeed\\((\\w+)\\)")) { m ->
        "val rnd = kotlin.random.Random(${m.groupValues[1]} xor com.stoyko.smessenger.shared.platform.nowNanos())"
    }
    s = s.replace(Regex("(?m)^import org\\.json\\..*\\n"), "")
    // java.util.Random / SecureRandom -> kotlin.random.Random (те же nextInt/nextFloat/nextLong).
    s = s.replace("import java.util.Random\n", "import kotlin.random.Random\n")
    s = s.replace("rnd: java.util.Random = java.security.SecureRandom()", "rnd: kotlin.random.Random = kotlin.random.Random.Default")
    s = s.replace("java.security.SecureRandom()", "kotlin.random.Random.Default")
    s = s.replace("java.util.Random(", "kotlin.random.Random(")
    // Время.
    s = s.replace("System.currentTimeMillis()", "com.stoyko.smessenger.shared.platform.nowMillis()")
    s = s.replace("System.nanoTime()", "com.stoyko.smessenger.shared.platform.nowNanos()")
    // System.arraycopy(a, 0, b, 0, n) -> a.copyInto(b, 0, 0, n)
    s = Regex("System\\.arraycopy\\(\\s*([\\w.]+)\\s*,\\s*0\\s*,\\s*([\\w.]+)\\s*,\\s*0\\s*,\\s*([\\w.]+)\\s*\\)")
        .replace(s) { m -> "${m.groupValues[1]}.copyInto(${m.groupValues[2]}, 0, 0, ${m.groupValues[3]})" }
    // "%02X".format(byte) -> без String.format (его нет в Kotlin/Native).
    s = s.replace("\"%02X\".format(it)", "it.toUByte().toString(16).padStart(2, '0').uppercase()")
    // @JvmInline / @JvmField / @JvmStatic: на JVM пакет kotlin.jvm импортирован сам, в общем коде - нет.
    if (s.contains("@Jvm") && !s.contains("import kotlin.jvm.")) {
        s = s.replaceFirst(Regex("(?m)^(package [\\w.]+)\\s*$"), "$1\n\nimport kotlin.jvm.*")
    }
    return "// АВТОМАТИЧЕСКИ перенесено из $name задачей :shared:syncPortedSources - правьте оригинал.\n$s"
}

/**
 * Страница HTML-пузыря для iOS: CSP, KIT_CSS, KIT_JS, SIZE_JS берутся из Android-файла
 * HtmlBubbles.kt при каждой сборке - стили и функции пузырей всегда как в Android-версии.
 */
fun bubblePage(hb: File): String {
    val txt = if (hb.exists()) hb.readText().replace("\r\n", "\n") else ""
    val a = txt.indexOf("    private const val CSP =")
    val b = txt.indexOf("    fun page(html: String")
    val consts = if (a >= 0 && b > a) {
        txt.substring(a, b).replace("private const val", "internal const val")
    } else {
        logger.warn("syncPortedSources: в HtmlBubbles.kt не найдены CSP/KIT - пузыри без набора стилей")
        "    internal const val CSP = \"\"\n    internal const val KIT_CSS = \"\"\n    internal const val KIT_JS = \"\"\n    internal const val SIZE_JS = \"\"\n"
    }
    return """
        |// АВТОМАТИЧЕСКИ собрано из app/src/main/kotlin/chat/HtmlBubbles.kt задачей :shared:syncPortedSources.
        |package com.stoyko.smessenger.shared.bubbles
        |
        |object BubblePage {
        |""".trimMargin() + "\n" + consts + """
        |    fun page(html: String, paramsJson: String = "{}"): String {
        |        val p = paramsJson.replace("</", "<\\/")
        |        return "<!doctype html><html><head><meta charset=utf-8>${'$'}CSP" +
        |            "<meta name=viewport content=\"width=device-width,initial-scale=1,maximum-scale=1\">" +
        |            "<style>${'$'}KIT_CSS</style><script>window.P=${'$'}p;</script><script>${'$'}KIT_JS</script></head><body>${'$'}html${'$'}SIZE_JS</body></html>"
        |    }
        |}
        |""".trimMargin()
}

val portedDir = layout.buildDirectory.dir("generated/ported/commonMain/kotlin")
val syncPortedSources = tasks.register("syncPortedSources") {
    group = "stoyko"
    description = "Переносит чистый Kotlin из Android-модулей в commonMain модуля :shared"
    val root = rootProject.layout.projectDirectory.asFile
    val srcs = portedFiles.map { File(root, it) }
    inputs.files(srcs.filter { it.exists() })
    outputs.dir(portedDir)
    doLast {
        val out = portedDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        for (f in srcs) {
            if (!f.exists()) {
                logger.warn("syncPortedSources: нет файла ${f.path} - пропускаю")
                continue
            }
            val rel = f.relativeTo(root).invariantSeparatorsPath
            File(out, f.name).writeText(portToCommon(rel, f.readText()))
        }
        File(out, "BubblePage.kt").writeText(bubblePage(File(root, "app/src/main/kotlin/chat/HtmlBubbles.kt")))
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(syncPortedSources)
