// SPDX-License-Identifier: GPL-3.0-only
//
// Мост к готовой нативной библиотеке toxcore проекта TRIfA
// (https://github.com/zoff99/ToxAndroidRefImpl, libjni-c-toxcore.so).
// Библиотека жёстко привязана к имени класса com.zoffcc.applications.trifa.MainActivity:
// JNI-функции называются Java_com_zoffcc_applications_trifa_MainActivity_*,
// а из C вызываются статические методы этого класса по точным сигнатурам.
// Поэтому имя класса, имена и сигнатуры методов МЕНЯТЬ НЕЛЬЗЯ.
// Это НЕ Android Activity - просто Java-класс.

package com.zoffcc.applications.trifa;

import android.util.Log;
import java.nio.charset.StandardCharsets;

/** Нужен нативной библиотеке: logger() и safe_string() вызываются из C. */
public final class TrifaToxService {
    private static final String TAG = "toxcore";

    /** Последняя ошибка расшифровки профиля (неверный пароль) - проверяется после init(). */
    public static volatile boolean decryptFailed = false;
    public static volatile boolean verbose = false;

    private TrifaToxService() {}

    static void logger(int level, String text) {
        try {
            if (text == null) return;
            // Разные версии библиотеки пишут об ошибке расшифровки по-разному.
            if (text.contains("tox_pass_decrypt failed") || text.contains("tox_pass_decrypt:res=0")) {
                decryptFailed = true;
            }
            if (text.startsWith("E:")) {
                Log.e(TAG, text);
            } else if (text.startsWith("W:")) {
                Log.w(TAG, text);
            } else if (verbose) {
                Log.d(TAG, text);
            }
        } catch (Throwable ignored) {
        }
    }

    static String safe_string(byte[] in) {
        try {
            if (in == null) return "";
            return new String(in, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }
}
