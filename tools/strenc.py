#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
# SPDX-License-Identifier: GPL-3.0-only
"""
Генератор зашифрованных строк для app/src/main/kotlin/Obf.kt.

    python tools/strenc.py name "строка" [name2 "строка2" ...]

Печатает готовые функции вида `fun x_name() = d(byteArrayOf(...))` - вставьте их в Obf.kt.
Ключ и начальное значение должны совпадать с Obf.KEY и `prev = 0x5A` в Obf.d().
"""
import sys

KEY = [83, 109, 46, 55, 0xA9, 28, 0xF4, 0x80]
SEED = 0x5A


def enc(s):
    out, prev = [], SEED
    for i, c in enumerate(s.encode("utf-8")):
        x = (c ^ KEY[i % len(KEY)] ^ prev) & 0xFF
        out.append(x)
        prev = x
    return out


def dec(arr):
    out, prev = [], SEED
    for i, x in enumerate(arr):
        out.append((x ^ KEY[i % len(KEY)] ^ prev) & 0xFF)
        prev = x
    return bytes(out).decode("utf-8")


def main(argv):
    if len(argv) < 2 or len(argv) % 2:
        print(__doc__)
        return 1
    for name, s in zip(argv[0::2], argv[1::2]):
        arr = enc(s)
        assert dec(arr) == s
        body = ", ".join(str(x - 256 if x > 127 else x) for x in arr)
        print(f"    /** {s!r} */")
        print(f"    fun x_{name}() = d(byteArrayOf({body}))")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
