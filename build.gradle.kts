// Шифрование строк StringFog: ВЫКЛЮЧЕНО по умолчанию - плагин 5.x не работает с
// Android Gradle Plugin 9 («StringFog plugin must be used with android plugin»).
// Включить (только с AGP 8.x): -Psm.stringfog=true. Защита кода R8 работает всегда.
buildscript {
    val stringFogOn = (findProperty("sm.stringfog") as String?)?.trim() == "true"
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        if (stringFogOn) {
            classpath("com.github.megatronking.stringfog:gradle-plugin:5.2.0")
            classpath("com.github.megatronking.stringfog:xor:5.0.0")
        }
    }
}

plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidApplication) apply false
}

tasks.register("clean").configure {
    delete("build")
}

// ---------------------------------------------------------------------------
// smPrefetch: заранее скачивает ВСЕ зависимости сборки в кэш Gradle.
// build_apk.bat запускает её повторно, пока не скачается всё (сеть может рвать
// соединения), а затем собирает APK с --offline - уже без сети.
// Падает, только если остались сетевые ошибки; прочие (не относящиеся к сборке
// APK конфигурации) игнорируются.
// ---------------------------------------------------------------------------
fun Throwable.isNetworkError(): Boolean = generateSequence(this) { it.cause }.any { t ->
    val m = t.message.orEmpty()
    t is java.io.IOException ||
        "Could not GET" in m || "Could not HEAD" in m || "Could not download" in m ||
        "handshake" in m || "timed out" in m || "Connection reset" in m
}

val kspVersion: String? = runCatching { libs.versions.ksp.get() }.getOrNull()

subprojects {
    val confs = configurations
    val projectPath = path
    val dependencyFactory = dependencies
    tasks.register("smPrefetch") {
        group = "stoyko"
        description = "Скачивает все зависимости для сборки без сети"
        notCompatibleWithConfigurationCache("Resolves configurations directly")
        doLast {
            val network = mutableListOf<String>()
            var files = 0
            confs.filter { c ->
                c.isCanBeResolved && !c.name.contains("Test") && !c.name.contains("test")
            }.forEach { c ->
                try {
                    val view = c.incoming.artifactView { isLenient = true }
                    view.artifacts.forEach { it.file; files++ }
                    view.artifacts.failures.forEach { f ->
                        if (f.isNetworkError()) network += "${c.name}: ${f.message}"
                    }
                } catch (e: Exception) {
                    if (e.isNetworkError()) network += "${c.name}: ${e.message}" else logger.info("skip ${c.name}: $e")
                }
            }
            // KSP докачивает свои библиотеки только во время сборки (отдельная конфигурация) -
            // без этого сборка без сети падала на symbol-processing-aa-embeddable. Качаем заранее.
            if (kspVersion != null) {
                try {
                    val deps = listOf(
                        "com.google.devtools.ksp:symbol-processing-aa-embeddable:$kspVersion",
                        "com.google.devtools.ksp:symbol-processing-common-deps:$kspVersion",
                        "com.google.devtools.ksp:symbol-processing-api:$kspVersion",
                    ).map { dependencyFactory.create(it) }
                    val det = confs.detachedConfiguration(*deps.toTypedArray())
                    val view = det.incoming.artifactView { isLenient = true }
                    view.artifacts.forEach { it.file; files++ }
                    view.artifacts.failures.forEach { f -> if (f.isNetworkError()) network += "ksp: ${f.message}" }
                } catch (e: Exception) {
                    if (e.isNetworkError()) network += "ksp: ${e.message}" else logger.info("skip ksp: $e")
                }
            }
            logger.lifecycle("$projectPath: зависимостей в кэше: $files, сетевых ошибок: ${network.size}")
            if (network.isNotEmpty()) {
                network.take(5).forEach { logger.lifecycle("  $it") }
                throw GradleException("$projectPath: не всё скачалось (${network.size}), нужен повтор")
            }
        }
    }
}
