#!/usr/bin/env python3
"""
Подключает модуль :shared (Kotlin Multiplatform, iOS) к Gradle-файлам Android-проекта.

Запускается в CI перед сборкой. Правит settings.gradle.kts, build.gradle.kts,
gradle/libs.versions.toml и gradle.properties ТОЛЬКО если нужного там ещё нет, поэтому
новую версию Android-кода можно класть в репозиторий как есть - iOS-часть
допишется сама. Повторный запуск ничего не меняет.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
KOTLIN = "2.3.21"
COMPOSE_MP = "1.12.2"


def edit(rel, fn):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    n = fn(s)
    if n != s:
        p.write_text(n, encoding="utf-8")
        print(f"patched {rel}")
    else:
        print(f"ok      {rel}")


def settings(s):
    if 'include(":shared")' in s:
        return s
    # Android-модули - только без -Psm.iosOnly=true (на macOS-раннере они не нужны).
    incs = re.findall(r'^include\("(:[\w-]+)"\)\s*$', s, flags=re.M)
    s = re.sub(r'^include\("(:[\w-]+)"\)\s*\n', "", s, flags=re.M)
    block = 'val smIosOnly = providers.gradleProperty("sm.iosOnly").orNull == "true"\n'
    block += "if (!smIosOnly) {\n" + "".join(f'    include("{m}")\n' for m in incs) + "}\n"
    block += 'include(":shared")\n'
    return s.rstrip() + "\n\n// --- iOS (Kotlin Multiplatform) ---\n" + block


def root_build(s):
    if "kotlinMultiplatform" in s:
        return s
    add = ("    alias(libs.plugins.kotlinMultiplatform) apply false\n"
           "    alias(libs.plugins.androidMultiplatformLibrary) apply false\n"
           "    alias(libs.plugins.composeMultiplatform) apply false\n"
           "    alias(libs.plugins.composeCompiler) apply false\n")
    m = re.search(r"^plugins\s*\{\n", s, flags=re.M)
    if not m:
        return s + "\nplugins {\n" + add + "}\n"
    return s[:m.end()] + add + s[m.end():]


def toml(s):
    def section(text, name, lines):
        hdr = f"[{name}]\n"
        if hdr not in text:
            return text + "\n" + hdr + lines
        return text.replace(hdr, hdr + lines, 1)
    if "kotlinMultiplatform" not in s:
        s = section(s, "versions", f'kotlin = "{KOTLIN}"\ncompose-multiplatform = "{COMPOSE_MP}"\n'
                    if not re.search(r'^kotlin\s*=', s, flags=re.M) else f'compose-multiplatform = "{COMPOSE_MP}"\n')
        agp = re.search(r'^(android-plugin|agp|androidGradlePlugin)\s*=', s, flags=re.M)
        agp_ref = agp.group(1) if agp else "android-plugin"
        s = section(s, "plugins",
                    'kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }\n'
                    f'androidMultiplatformLibrary = {{ id = "com.android.kotlin.multiplatform.library", version.ref = "{agp_ref}" }}\n'
                    'composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }\n'
                    'composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }\n')
    if "compose-mp-runtime" not in s:
        s = section(s, "libraries",
                    'compose-mp-runtime = { module = "org.jetbrains.compose.runtime:runtime", version.ref = "compose-multiplatform" }\n'
                    'compose-mp-foundation = { module = "org.jetbrains.compose.foundation:foundation", version.ref = "compose-multiplatform" }\n'
                    'compose-mp-ui = { module = "org.jetbrains.compose.ui:ui", version.ref = "compose-multiplatform" }\n')
    if not re.search(r'^kotlinx-coroutines-core\s*=', s, flags=re.M):
        s = section(s, "libraries", 'kotlinx-coroutines-core = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2"\n')
    return s


def props(s):
    add = ""
    for line in ("kotlin.native.ignoreDisabledTargets=true", "kotlin.apple.xcodeCompatibility.nowarn=true"):
        if line.split("=")[0] not in s:
            add += line + "\n"
    return s if not add else s.rstrip() + "\n\n# --- iOS (Kotlin Multiplatform) ---\n" + add


edit("settings.gradle.kts", settings)
edit("build.gradle.kts", root_build)
edit("gradle/libs.versions.toml", toml)
edit("gradle.properties", props)
