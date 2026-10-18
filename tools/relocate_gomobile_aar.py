#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
# SPDX-License-Identifier: GPL-3.0-only
"""
Переносит gomobile-библиотеку (.aar) из пакета `go` в пакет `gn`, чтобы она могла лежать в одном
APK рядом с другой gomobile-библиотекой (у нас - libv2ray.aar, ядро Xray).

Обе библиотеки, собранные gomobile, приносят одинаковые классы go.Seq / go.Universe / go.error и
одинаковый файл libgojni.so - сборка падает на «Duplicate class go.Seq», а если их всё же склеить,
загрузится только одна из двух .so. После переноса:

  * классы                go/Seq...        -> gn/Seq...   (все ссылки в classes.jar)
  * имя библиотеки        libgojni.so      -> libgnjni.so (System.loadLibrary("gnjni"))
  * JNI-функции в .so     Java_go_Seq_*    -> Java_gn_Seq_*
  * строки JNI в .so      "go/Seq", "(Lgo/Seq$GoObject;)I" ...  -> gn/...

Все замены одинаковой длины, поэтому .so правится на месте, без пересборки. Хэш-таблица
символов (.gnu.hash) после переименования пересчитывается: делается одна корзина и открытый
фильтр Блума - поиск символа идёт простым перебором цепочки (несколько сотен имён - мгновенно),
порядок символов и все релокации остаются нетронутыми.

Запуск:  python3 relocate_gomobile_aar.py вход.aar выход.aar [--abi arm64-v8a --abi armeabi-v7a]
В конце выполняется проверка: каждой native-функции из classes.jar находится символ в .so (через
ту же процедуру поиска, что у загрузчика Android), а каждый класс, который .so ищет по имени,
есть в classes.jar.
"""
import argparse
import io
import re
import struct
import sys
import zipfile

OLD_PKG = b"go"
NEW_PKG = b"gn"
OLD_LIB = b"gojni"
NEW_LIB = b"gnjni"
GO_CLASSES = (b"Seq", b"Universe", b"error")


# ---------------------------------------------------------------- classes.jar

def _patch_utf8(s: bytes) -> bytes:
    if s == OLD_LIB:
        return NEW_LIB
    if s.startswith(OLD_PKG + b"/"):
        s = NEW_PKG + s[2:]
    return s.replace(b"L" + OLD_PKG + b"/", b"L" + NEW_PKG + b"/")


def patch_class(data: bytes) -> bytes:
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    out = bytearray(data[:10])
    i = 10
    idx = 1
    while idx < count:
        tag = data[i]
        if tag == 1:
            ln = struct.unpack(">H", data[i + 1:i + 3])[0]
            s = _patch_utf8(data[i + 3:i + 3 + ln])
            out += bytes([1]) + struct.pack(">H", len(s)) + s
            i += 3 + ln
        elif tag in (7, 8, 16, 19, 20):
            out += data[i:i + 3]; i += 3
        elif tag == 15:
            out += data[i:i + 4]; i += 4
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            out += data[i:i + 5]; i += 5
        elif tag in (5, 6):
            out += data[i:i + 9]; i += 9; idx += 1
        else:
            raise ValueError("unknown constant tag %d" % tag)
        idx += 1
    out += data[i:]
    return bytes(out)


def relocate_jar(jar: bytes) -> bytes:
    zin = zipfile.ZipFile(io.BytesIO(jar))
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            data = zin.read(info)
            if name.endswith(".class"):
                data = patch_class(data)
            if name.startswith("go/"):
                name = "gn/" + name[3:]
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)
    return buf.getvalue()


def jar_natives(jar: bytes):
    """(класс, метод) всех native-методов - для проверки."""
    res = []
    z = zipfile.ZipFile(io.BytesIO(jar))
    for n in z.namelist():
        if not n.endswith(".class"):
            continue
        d = z.read(n)
        cp = [None]
        count = struct.unpack(">H", d[8:10])[0]
        i = 10
        k = 1
        while k < count:
            t = d[i]
            if t == 1:
                ln = struct.unpack(">H", d[i + 1:i + 3])[0]
                cp.append(d[i + 3:i + 3 + ln]); i += 3 + ln
            elif t in (7, 8, 16, 19, 20):
                cp.append(None); i += 3
            elif t == 15:
                cp.append(None); i += 4
            elif t in (3, 4, 9, 10, 11, 12, 17, 18):
                cp.append(None); i += 5
            elif t in (5, 6):
                cp += [None, None]; i += 9; k += 1
            k += 1
        i += 6  # access, this, super
        ifc = struct.unpack(">H", d[i:i + 2])[0]; i += 2 + 2 * ifc

        def skip_members(i, collect):
            cnt = struct.unpack(">H", d[i:i + 2])[0]; i += 2
            for _ in range(cnt):
                acc, ni, _di, ac = struct.unpack(">HHHH", d[i:i + 8]); i += 8
                if collect and acc & 0x0100:
                    res.append((n[:-6], cp[ni].decode()))
                for _ in range(ac):
                    ln = struct.unpack(">I", d[i + 2:i + 6])[0]; i += 6 + ln
            return i
        i = skip_members(i, False)
        skip_members(i, True)
    return res


def jni_mangle(cls: str, meth: str) -> str:
    def m(s):
        o = []
        for ch in s:
            if ch == "/":
                o.append("_")
            elif ch == "_":
                o.append("_1")
            elif ch == ";":
                o.append("_2")
            elif ch == "[":
                o.append("_3")
            elif ch.isalnum() and ord(ch) < 128:
                o.append(ch)
            else:
                o.append("_0%04x" % ord(ch))
        return "".join(o)
    return "Java_" + m(cls) + "_" + m(meth)


# ---------------------------------------------------------------- ELF (.so)

class Elf:
    def __init__(self, data: bytearray):
        self.d = data
        if data[:4] != b"\x7fELF":
            raise ValueError("not ELF")
        self.is64 = data[4] == 2
        if data[5] != 1:
            raise ValueError("big-endian ELF is not supported")
        e = "<"
        if self.is64:
            (self.phoff, self.shoff) = struct.unpack_from(e + "QQ", data, 0x20)
            (self.phentsize, self.phnum) = struct.unpack_from(e + "HH", data, 0x36)
        else:
            (self.phoff, self.shoff) = struct.unpack_from(e + "II", data, 0x1C)
            (self.phentsize, self.phnum) = struct.unpack_from(e + "HH", data, 0x2A)
        self.loads = []
        dyn = None
        for k in range(self.phnum):
            o = self.phoff + k * self.phentsize
            if self.is64:
                p_type, _fl, p_off, p_vaddr, _pa, p_filesz = struct.unpack_from("<IIQQQQ", data, o)
            else:
                p_type, p_off, p_vaddr, _pa, p_filesz = struct.unpack_from("<IIIII", data, o)
            if p_type == 1:
                self.loads.append((p_vaddr, p_off, p_filesz))
            elif p_type == 2:
                dyn = (p_off, p_filesz)
        if dyn is None:
            raise ValueError("no PT_DYNAMIC")
        self.dyn = {}
        ent = 16 if self.is64 else 8
        fmt = "<qQ" if self.is64 else "<iI"
        for o in range(dyn[0], dyn[0] + dyn[1], ent):
            tag, val = struct.unpack_from(fmt, data, o)
            if tag == 0:
                break
            self.dyn.setdefault(tag, val)

    def off(self, vaddr):
        for va, fo, sz in self.loads:
            if va <= vaddr < va + sz:
                return vaddr - va + fo
        raise ValueError("vaddr %x not mapped" % vaddr)

    def symbols(self):
        """[(index, name_offset_in_file, name, shndx)] для всех символов .dynsym."""
        DT_SYMTAB, DT_STRTAB, DT_GNU_HASH = 6, 5, 0x6FFFFEF5
        symtab = self.off(self.dyn[DT_SYMTAB])
        strtab = self.off(self.dyn[DT_STRTAB])
        nsyms = self.gnu_nsyms(self.off(self.dyn[DT_GNU_HASH]))
        res = []
        ent = 24 if self.is64 else 16
        for k in range(nsyms):
            o = symtab + k * ent
            st_name = struct.unpack_from("<I", self.d, o)[0]
            shndx = struct.unpack_from("<H", self.d, o + (6 if self.is64 else 14))[0]
            no = strtab + st_name
            end = self.d.index(b"\x00", no)
            res.append((k, no, bytes(self.d[no:end]), shndx))
        return res

    def gnu_nsyms(self, gh):
        w = 8 if self.is64 else 4
        nb, symoff, bsz, _sh = struct.unpack_from("<IIII", self.d, gh)
        buckets = gh + 16 + bsz * w
        chain = buckets + nb * 4
        last = max(struct.unpack_from("<%dI" % nb, self.d, buckets)) if nb else 0
        if last < symoff:
            return symoff
        while True:
            h = struct.unpack_from("<I", self.d, chain + (last - symoff) * 4)[0]
            if h & 1:
                return last + 1
            last += 1


def gnu_hash(name: bytes) -> int:
    h = 5381
    for c in name:
        h = (h * 33 + c) & 0xFFFFFFFF
    return h


def rebuild_gnu_hash(elf: Elf, syms):
    """Одна корзина + открытый фильтр Блума; порядок символов не меняется."""
    w = 8 if elf.is64 else 4
    gh = elf.off(elf.dyn[0x6FFFFEF5])
    nb, symoff, bsz, shift = struct.unpack_from("<IIII", elf.d, gh)
    old_size = 16 + bsz * w + nb * 4 + (len(syms) - symoff) * 4
    hashed = syms[symoff:]
    if not hashed:
        raise ValueError("no hashed symbols")
    new = bytearray()
    new += struct.pack("<IIII", 1, symoff, 1, shift)
    new += b"\xff" * w                       # фильтр Блума пропускает всё
    new += struct.pack("<I", symoff)         # единственная корзина - первый хэшируемый символ
    for k, (_i, _no, name, _sh) in enumerate(hashed):
        h = gnu_hash(name) & ~1
        if k == len(hashed) - 1:
            h |= 1                           # конец цепочки
        new += struct.pack("<I", h)
    if len(new) > old_size:
        raise ValueError("new .gnu.hash is larger than the old one")
    new += b"\x00" * (old_size - len(new))
    elf.d[gh:gh + old_size] = new


def gnu_lookup(elf: Elf, name: bytes):
    """Поиск символа так же, как в загрузчике bionic (soinfo::gnu_lookup)."""
    w = 8 if elf.is64 else 4
    bits = w * 8
    gh = elf.off(elf.dyn[0x6FFFFEF5])
    nb, symoff, bsz, shift = struct.unpack_from("<IIII", elf.d, gh)
    if bsz & (bsz - 1):
        raise ValueError("maskwords not a power of 2")
    h = gnu_hash(name)
    bloom = struct.unpack_from("<Q" if w == 8 else "<I", elf.d, gh + 16 + ((h // bits) & (bsz - 1)) * w)[0]
    h2 = h >> shift
    if not ((bloom >> (h % bits)) & (bloom >> (h2 % bits)) & 1):
        return None
    buckets = gh + 16 + bsz * w
    chain = buckets + nb * 4
    n = struct.unpack_from("<I", elf.d, buckets + (h % nb) * 4)[0]
    if n == 0:
        return None
    syms = {s[0]: s for s in elf.symbols()}
    while True:
        ch = struct.unpack_from("<I", elf.d, chain + (n - symoff) * 4)[0]
        if ((ch ^ h) >> 1) == 0 and syms[n][2] == name and syms[n][3] != 0:
            return n
        if ch & 1:
            return None
        n += 1


def relocate_so(data: bytes):
    d = bytearray(data)
    elf = Elf(d)
    syms = elf.symbols()
    renamed = 0
    old_prefix = b"Java_" + OLD_PKG + b"_"
    for _i, no, name, _sh in syms:
        if name.startswith(old_prefix):
            d[no + 5:no + 7] = NEW_PKG
            renamed += 1
    # Строки JNI: "go/Seq", "(Lgo/Seq$GoObject;)I", ... (C-строки с нулём на конце).
    strings = 0
    for m in re.finditer(rb"(?<=\x00)[^\x00]{0,200}\x00", bytes(d)):
        s = m.group()[:-1]
        if not re.search(rb"(?:^|L)" + OLD_PKG + rb"/(?:" + b"|".join(GO_CLASSES) + rb")\b", s):
            continue
        ns = re.sub(rb"(^|L)" + OLD_PKG + rb"/", lambda mm: mm.group(1) + NEW_PKG + b"/", s)
        assert len(ns) == len(s)
        d[m.start():m.start() + len(s)] = ns
        strings += 1
    elf = Elf(d)
    syms = elf.symbols()
    rebuild_gnu_hash(elf, syms)
    return bytes(d), renamed, strings


def jni_class_strings(data: bytes):
    out = set()
    for m in re.finditer(rb"(?<=\x00)[^\x00]{0,200}\x00", data):
        s = m.group()[:-1]
        # Имена классов JNI: последний сегмент с заглавной буквы (go/types/expr - это путь
        # исходника Go в таблице строк, не класс).
        for c in re.findall(rb"(?:^|L)((?:gn|go|nova)/(?:[a-z0-9_]+/)*[A-Z][A-Za-z0-9_$]*)", s):
            out.add(c.decode())
    return out


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--abi", action="append", help="оставить только эти ABI (по умолчанию все)")
    a = ap.parse_args()

    zin = zipfile.ZipFile(a.src)
    new_jar = None
    sos = {}
    with zipfile.ZipFile(a.dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            name = info.filename
            data = zin.read(info)
            if name == "classes.jar":
                data = new_jar = relocate_jar(data)
            elif name.startswith("jni/") and name.endswith("/lib" + OLD_LIB.decode() + ".so"):
                abi = name.split("/")[1]
                if a.abi and abi not in a.abi:
                    continue
                data, nren, nstr = relocate_so(data)
                name = "jni/%s/lib%s.so" % (abi, NEW_LIB.decode())
                sos[abi] = data
                print("%s: JNI-функций переименовано %d, строк JNI %d" % (abi, nren, nstr))
            elif name == "proguard.txt":
                data = data.replace(b"class go.**", b"class gn.**")
            elif name == "AndroidManifest.xml":
                data = re.sub(rb'package="[^"]*"', b'package="gn.nova.gnjni"', data)
            zi = zipfile.ZipInfo(name, date_time=info.date_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            zout.writestr(zi, data)

    # ---------- проверка
    ok = True
    names = set(n[:-6] for n in zipfile.ZipFile(io.BytesIO(new_jar)).namelist() if n.endswith(".class"))
    if any(n.startswith("go/") for n in names):
        print("ОШИБКА: в classes.jar остались классы go/"); ok = False
    for n in zipfile.ZipFile(io.BytesIO(new_jar)).namelist():
        if n.endswith(".class"):
            d = zipfile.ZipFile(io.BytesIO(new_jar)).read(n)
            if b"Lgo/" in d or re.search(rb"\x00\x06gojni", d) or re.search(rb"\x01\x00.go/", d):
                print("ОШИБКА: ссылка на go/ осталась в", n); ok = False
    natives = jar_natives(new_jar)
    for abi, so in sos.items():
        elf = Elf(bytearray(so))
        for cls, meth in natives:
            sym = jni_mangle(cls, meth).encode()
            if gnu_lookup(elf, sym) is None:
                print("ОШИБКА %s: нет символа %s" % (abi, sym.decode())); ok = False
        for s in elf.symbols():
            if s[3] != 0 and s[0] >= struct.unpack_from("<I", elf.d, elf.off(elf.dyn[0x6FFFFEF5]) + 4)[0]:
                if gnu_lookup(elf, s[2]) != s[0]:
                    print("ОШИБКА %s: символ %s не находится поиском" % (abi, s[2].decode())); ok = False
        for c in jni_class_strings(so):
            base = c
            if base not in names:
                print("ОШИБКА %s: .so ищет класс %s, а его нет в classes.jar" % (abi, c)); ok = False
        if any(s[2].startswith(b"Java_go_") for s in elf.symbols()):
            print("ОШИБКА %s: остались символы Java_go_" % abi); ok = False
        print("%s: native-методов %d - все находятся; классов из .so: %s" % (abi, len(natives), sorted(jni_class_strings(so))))
    if not ok:
        sys.exit(1)
    print("Готово:", a.dst)


if __name__ == "__main__":
    main()
