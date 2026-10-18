import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinKsp)
}

// Необязательный файл с ключом подписи релиза (в корне проекта):
//   storeFile=C:/keys/smessenger.jks
//   storePassword=...
//   keyAlias=smessenger
//   keyPassword=...
// Если файла нет, release подписывается debug-ключом, чтобы APK сразу ставился на телефон.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Встроенный помощник S-AI: OpenAI-совместимый API. По умолчанию - шлюз Vireonix
// (без регистрации и ключа, модель "auto"). Другой сервис можно задать в ai.properties
// (см. ai.properties.example) - он станет сервисом по умолчанию у всех, кто поставит APK.
val aiProps = Properties().apply {
    val f = rootProject.file("ai.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun aiProp(name: String, def: String): String =
    "\"" + (aiProps.getProperty(name) ?: def).trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Обновления «по воздуху»: адрес, где приложение проверяет новую версию (см. update.properties.example).
// Можно указать GitHub-репозиторий (owner/repo) или прямую ссылку на update.json.
val updateProps = Properties().apply {
    val f = rootProject.file("update.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun updateProp(name: String): String =
    "\"" + (updateProps.getProperty(name) ?: "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// Шифрование строк - только по -Psm.stringfog=true (с AGP 9 плагин не работает, см. корневой build.gradle.kts).
val stringFogOn = providers.gradleProperty("sm.stringfog").orNull?.trim() == "true"
if (stringFogOn) {
    apply(plugin = "stringfog")
    // Настройка через groovy-builder: так файл сборки компилируется и без плагина.
    extensions.getByName("stringfog").withGroovyBuilder {
        setProperty("implementation", "com.github.megatronking.stringfog.xor.StringFogImpl")
        setProperty("packageName", "com.stoyko.smessenger")
        setProperty("enable", true)
        // Только наш код: библиотеки (ядро VPN, AndroidX) не трогаем.
        setProperty("fogPackages", arrayOf("com.stoyko.smessenger"))
    }
}

// Единственный файл на выходе: после assembleRelease APK копируется в папку APK
// под именем S-messenger-universal-release.apk.
val smApkDir = layout.buildDirectory.dir("outputs/apk/release")
val smApkOut = rootProject.layout.projectDirectory.dir("APK")
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val src = smApkDir.get().asFile.listFiles { f -> f.name.endsWith(".apk") }?.maxByOrNull { it.length() }
        if (src != null) {
            val out = smApkOut.asFile.apply { mkdirs() }
            out.listFiles { f -> f.name.endsWith(".apk") }?.forEach { it.delete() }
            src.copyTo(File(out, "S-messenger-universal-release.apk"), overwrite = true)
            println("APK: " + File(out, "S-messenger-universal-release.apk").absolutePath)
        }
    }
}

// Бинарники Tor и lyrebird (obfs4/meek/snowflake) - как в Briar (bramble-android/build.gradle,
// задача unpackTorBinaries): jar с <abi>/libtor.so распаковывается в папку jniLibs при сборке.
val torBinaries: Configuration by configurations.creating
val torLibsDir = layout.buildDirectory.dir("generated/torLibs")
val unpackTorBinaries = tasks.register<Sync>("unpackTorBinaries") {
    from(provider { torBinaries.files.map { zipTree(it) } })
    include("**/*.so")
    into(torLibsDir)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(unpackTorBinaries) }

// Файлы, убранные из проекта (tools/obsolete_files.txt): распаковка нового архива поверх
// старой папки их не стирает, а сборка подхватила бы их в APK. Удаляются перед каждой сборкой -
// и из build_apk.bat, и из Android Studio.
val smObsoleteList: File = rootProject.file("tools/obsolete_files.txt")
val smProjectRoot: File = rootProject.projectDir
val smCleanObsolete = tasks.register("smCleanObsolete") {
    val list = smObsoleteList
    val root = smProjectRoot
    doLast {
        if (list.isFile) {
            list.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.contains("..") }.forEach { rel ->
                val f = File(root, rel)
                if (f.isFile && f.delete()) println("Удалён устаревший файл: $rel")
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(smCleanObsolete) }

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
    }
}

android {
    namespace = "com.stoyko.smessenger"
    // compileSdk можно переопределить: -Psm.compileSdk=36 (build_apk.bat подставляет
    // уже установленную платформу, чтобы не качать android-35 заново).
    compileSdk = (providers.gradleProperty("sm.compileSdk").orNull ?: libs.versions.sdk.target.get()).toInt()
    providers.gradleProperty("sm.buildTools").orNull?.let { buildToolsVersion = it }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "com.stoyko.smessenger"
        minSdk = libs.versions.sdk.min.get().toInt()
        targetSdk = libs.versions.sdk.target.get().toInt()
        versionCode = 102
        versionName = "4.13.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Адрес по умолчанию зашифрован в коде (Obf.x_aiBase) - в BuildConfig только свой, из ai.properties.
        buildConfigField("String", "AI_BASE_URL", aiProp("baseUrl", ""))
        buildConfigField("String", "AI_API_KEY", aiProp("apiKey", "none"))
        buildConfigField("String", "AI_MODEL", aiProp("model", "auto"))
        buildConfigField("String", "UPDATE_GITHUB", updateProp("github"))
        buildConfigField("String", "UPDATE_URL", updateProp("url"))
    }
    signingConfigs {
        getByName("debug") {
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeFile = file("debug.keystore")
            storePassword = "android"
        }
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            // Защита от декомпиляции: R8 сжимает, оптимизирует и переименовывает классы, методы и
            // поля в бессмысленные имена (правила - proguard-rules.pro), строки шифрует StringFog.
            isMinifyEnabled = true
            // Удаляем неиспользуемые ресурсы - APK меньше.
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles("proguard-rules.pro", "proguard-transport.pro", getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Один универсальный APK со всеми процессорами внутри: старые 32-битные (armeabi-v7a,
    // x86) и новые 64-битные (arm64-v8a, x86_64). Отдельные APK под архитектуры не собираются -
    // на выходе только S-messenger-universal-release.apk (см. smUniversalApk ниже).
    splits {
        abi {
            isEnable = false
        }
    }
    defaultConfig {
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }
    // Только два языка: русский (основной, values/) и английский (values-en/).
    // Переводы из библиотек (Material, AndroidX) на другие языки в APK не попадают.
    androidResources {
        localeFilters += listOf("ru", "en")
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    lint {
        disable += setOf("GoogleAppIndexingWarning", "MissingTranslation")
        // Проверка lint при release-сборке тянет из сети свои библиотеки и не нужна для APK.
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        jniLibs {
            // Движок обхода DPI (libciadpi.so) запускается как программа - библиотеки должны
            // распаковываться на устройство (а не читаться прямо из APK).
            useLegacyPackaging = true
            // .so уже без отладочной информации; NDK для их обработки не нужен.
            keepDebugSymbols += "**/libjni-c-toxcore.so"
            keepDebugSymbols += "**/libgojni.so"
            // Ядро Nova (перенесённое в пакет gn): хэш-таблица символов пересобрана вручную - не трогать.
            keepDebugSymbols += "**/libgnjni.so"
            keepDebugSymbols += "**/libciadpi.so"
            keepDebugSymbols += "**/libwg-go.so"
            // Tor и lyrebird запускаются как программы из папки библиотек (поэтому и legacy-упаковка).
            keepDebugSymbols += "**/libtor.so"
            keepDebugSymbols += "**/liblyrebird.so"
        }
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(torLibsDir.get().asFile.path)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))
    // Ядро Xray для встроенного VPN (AndroidLibXrayLite, без geo-файлов, arm/arm64).
    // Файл присылается отдельно: положите libv2ray.aar в папку app\libs.
    val xray = file("libs/libv2ray.aar")
    if (!xray.exists()) {
        throw GradleException(
            "Нет файла app/libs/libv2ray.aar (ядро встроенного VPN). Положите присланный libv2ray.aar в папку app\\libs и соберите снова.",
        )
    }
    implementation(files(xray))
    // Ядро Nova (WARP/AmneziaWG 3.x и Cloudflare MASQUE), перенесённое из пакета go в gn
    // (tools/relocate_gomobile_aar.py), чтобы лежать в одном APK с libv2ray.aar. Работает только
    // в процессе «:nova» (vpn/NovaVpnService.kt). Только armeabi-v7a и arm64-v8a.
    val novaCore = file("libs/nova-core-gn.aar")
    if (!novaCore.exists()) {
        throw GradleException(
            "Нет файла app/libs/nova-core-gn.aar (ядро WARP/MASQUE). Распакуйте вторую часть архива (part2) в папку проекта и соберите снова.",
        )
    }
    implementation(files(novaCore))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment)

    implementation(libs.google.android.material)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    implementation(libs.androidx.preference)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(libs.google.dagger.core)
    ksp(libs.google.dagger.compiler)

    implementation(libs.nayuki.qrcodegen)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.square.picasso)

    // «Каналы связи»: встроенный Tor (как в Briar).
    implementation(libs.briar.onionwrapper.android)
    implementation(libs.briar.dontkillme)
    torBinaries(libs.briar.tor.android)
    torBinaries(libs.briar.lyrebird.android)

    // Расшифровка строк в рантайме (для StringFog; версия - как у плагина в корневом build.gradle.kts).
    if (stringFogOn) implementation("com.github.megatronking.stringfog:xor:5.0.0")

    // LeakCanary убран: в debug-сборке он «анализировал память» (уведомления про анализ),
    // замораживал приложение на секунды и на слабых телефонах вешал систему.

    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.test.rules)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.espresso.core)
    androidTestImplementation(libs.test.espresso.contrib)
    androidTestImplementation(libs.test.junit.ext)
    kspAndroidTest(libs.google.dagger.compiler)

    modules {
        module("com.google.guava:listenablefuture") {
            replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
        }
    }
}
