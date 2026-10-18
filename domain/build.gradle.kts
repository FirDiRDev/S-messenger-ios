plugins {
    alias(libs.plugins.androidLibrary)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
    }
}

android {
    namespace = "com.stoyko.smessenger.domain"
    // compileSdk можно переопределить: -Psm.compileSdk=36 (build_apk.bat подставляет
    // уже установленную платформу, чтобы не качать android-35 заново).
    compileSdk = (providers.gradleProperty("sm.compileSdk").orNull ?: libs.versions.sdk.target.get()).toInt()
    providers.gradleProperty("sm.buildTools").orNull?.let { buildToolsVersion = it }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = libs.versions.sdk.min.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Правила R8 для приложения: мост к нативной библиотеке нельзя переименовывать.
        consumerProguardFiles("consumer-rules.pro")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    // Готовые нативные библиотеки toxcore (TRIfA jni-c-toxcore 1.0.201) лежат в
    // src/main/jniLibs/<abi>/libjni-c-toxcore.so и попадают в APK автоматически.
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.javax.inject)
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit"))
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit.ext)
    androidTestImplementation(libs.kotlinx.coroutines.test) {
        // Conflicts with a lot of things due to having embedded "byte buddy" instead of depending on it.A
        exclude("org.jetbrains.kotlinx", "kotlinx-coroutines-debug")
    }

    modules {
        module("com.google.guava:listenablefuture") {
            replacedBy("com.google.guava:guava", "listenablefuture is part of guava")
        }
    }
}
