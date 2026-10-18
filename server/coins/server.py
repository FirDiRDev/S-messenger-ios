# -*- coding: utf-8 -*-
"""
Сервер монет S-messenger: покупка монет за рубли через кошелёк ЮMoney (физлицо).

Как это работает:
 1. Приложение строит ссылку оплаты Quickpay под выбранный пак (см. make_payment_link).
    В параметр label зашит user_id игрока - его СЕКРЕТНЫЙ ID покупок (64 hex-символа):
    приложение создаёт его один раз, хранит в защищённой базе coin_purchases.db и
    переносит вместе с экспортированным профилем. Чужой человек этот ID не знает, поэтому
    не может ни посмотреть, ни «перехватить» чужие покупки.
 2. Человек платит. ЮMoney отправляет на этот сервер HTTP-уведомление (POST /yoomoney/notify).
 3. Сервер проверяет подпись sha1_hash секретным словом, сумму, валюту, повторы и
    начисляет пак: записывает платёж и выпускает ПОДПИСАННЫЙ чек на монеты.
 4. Приложение спрашивает GET /api/receipts?user_id=…, проверяет подпись каждого чека
    открытым ключом и сам зачисляет монеты (каждый чек - только один раз).

Запуск:
    pip install flask cryptography
    python keys.py          # один раз - ключи подписи
    python server.py        # или через gunicorn за nginx с HTTPS (ЮMoney шлёт только на HTTPS)
"""
import base64
import hashlib
import hmac
import re
import sqlite3
import time
import uuid
from urllib.parse import urlencode

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from flask import Flask, abort, jsonify, redirect, request

import config

app = Flask(__name__)

# user_id = открытый ключ аккаунта S-messenger: ровно 64 шестнадцатеричных символа.
USER_ID_RE = re.compile(r"^[0-9A-Fa-f]{64}$")

with open(config.PRIVATE_KEY_PATH, "rb") as _f:
    PRIVATE_KEY = serialization.load_pem_private_key(_f.read(), password=None)


# ---------------------------------------------------------------- база данных

def db():
    """Соединение с SQLite. Таблицы создаются при первом запуске."""
    con = sqlite3.connect(config.DB_PATH, isolation_level=None)  # сами управляем транзакциями
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL")
    con.executescript(
        """
        -- Игроки и сколько монет им всего начислено за покупки.
        CREATE TABLE IF NOT EXISTS users (
            user_id       TEXT PRIMARY KEY,
            coins_balance INTEGER NOT NULL DEFAULT 0
        );
        -- Каждый платёж ЮMoney - ровно один раз (operation_id уникален: повторное
        -- уведомление о том же платеже монеты второй раз не начислит).
        CREATE TABLE IF NOT EXISTS payments (
            operation_id TEXT PRIMARY KEY,
            user_id      TEXT,
            rub          TEXT NOT NULL,
            coins        INTEGER NOT NULL,
            status       TEXT NOT NULL,      -- credited | unmatched | test | donate
            created      INTEGER NOT NULL,
            raw          TEXT
        );
        -- Подписанные чеки для приложения.
        CREATE TABLE IF NOT EXISTS receipts (
            receipt_id   TEXT PRIMARY KEY,
            user_id      TEXT NOT NULL,
            operation_id TEXT NOT NULL UNIQUE,
            coins        INTEGER NOT NULL,
            rub          INTEGER NOT NULL,
            created      INTEGER NOT NULL,
            signature    TEXT NOT NULL
        );
        CREATE INDEX IF NOT EXISTS receipts_user ON receipts(user_id);
        """
    )
    return con


# ---------------------------------------------------------------- 1. ссылка на оплату

def make_payment_link(user_id: str, pack_name: str, method: str = "AC") -> str:
    """
    Ссылка на оплату пака через Quickpay.
    user_id   - открытый ключ игрока (уйдёт в label - так сервер узнает, кому начислить);
    pack_name - "handful" | "bag" | "chest";
    method    - "AC" банковская карта, "PC" кошелёк ЮMoney.
    """
    if not USER_ID_RE.match(user_id or ""):
        raise ValueError("неверный user_id")
    if pack_name not in config.PACKS:
        raise ValueError("нет такого пака")
    rub, coins = config.PACKS[pack_name]
    params = {
        "receiver": config.YOOMONEY_WALLET,      # мой кошелёк
        "quickpay-form": "button",
        "paymentType": method,
        "sum": f"{rub:.2f}",                     # сумма в рублях
        "label": user_id.upper(),                # ID игрока (до 64 символов - ключ ровно 64)
        "targets": f"S-messenger: {coins} монет",
        "successURL": config.PUBLIC_URL + "/paid",
    }
    return "https://yoomoney.ru/quickpay/confirm?" + urlencode(params)


@app.get("/pay")
def pay():
    """Удобная короткая ссылка: /pay?user_id=…&pack=chest → переход на оплату."""
    try:
        return redirect(make_payment_link(request.args.get("user_id", ""), request.args.get("pack", ""), request.args.get("method", "AC")))
    except ValueError as e:
        abort(400, str(e))


@app.get("/paid")
def paid():
    return "<h2>Спасибо! Оплата прошла.</h2><p>Вернитесь в S-messenger - монеты зачислятся автоматически.</p>"


# ---------------------------------------------------------------- 2. уведомление ЮMoney

def check_sha1(form) -> bool:
    """
    Подлинность уведомления: SHA-1 от строки
    notification_type&operation_id&amount&currency&datetime&sender&codepro&notification_secret&label
    (порядок и состав - по документации ЮMoney). Сравниваем «в постоянное время».
    """
    parts = [
        form.get("notification_type", ""),
        form.get("operation_id", ""),
        form.get("amount", ""),
        form.get("currency", ""),
        form.get("datetime", ""),
        form.get("sender", ""),
        form.get("codepro", ""),
        config.NOTIFICATION_SECRET,
        form.get("label", ""),
    ]
    expected = hashlib.sha1("&".join(parts).encode("utf-8")).hexdigest()
    return hmac.compare_digest(expected, form.get("sha1_hash", "").lower())


def pack_for(rub_paid: float):
    """Пак по сумме, которую заплатил человек. Не совпало ни с одним паком - None."""
    for name, (rub, coins) in config.PACKS.items():
        # Сравниваем в копейках, чтобы не попасть на ошибки дробных чисел.
        if round(rub_paid * 100) == rub * 100:
            return name, rub, coins
    return None


def sign_receipt(receipt_id: str, user_id: str, coins: int, rub: int, operation_id: str, created: int) -> str:
    """Подпись чека (ECDSA P-256 / SHA-256). Строку для подписи приложение собирает так же."""
    payload = f"smc1|{receipt_id}|{user_id}|{coins}|{rub}|{operation_id}|{created}".encode("utf-8")
    return base64.b64encode(PRIVATE_KEY.sign(payload, ec.ECDSA(hashes.SHA256()))).decode()


@app.post("/yoomoney/notify")
def yoomoney_notify():
    # ЮMoney шлёт application/x-www-form-urlencoded.
    form = request.form

    # 1) Подпись. Не совпала - это не ЮMoney (или чужой секрет): ничего не делаем.
    if not check_sha1(form):
        abort(403)

    operation_id = form.get("operation_id", "")
    label = (form.get("label", "") or "").upper()
    currency = form.get("currency", "")
    # amount - сколько пришло мне (за вычетом комиссии), withdraw_amount - сколько заплатил
    # человек. Пак определяем по withdraw_amount: при оплате картой amount меньше цены пака.
    try:
        amount = float(form.get("amount", "0"))
        paid = float(form.get("withdraw_amount") or form.get("amount", "0"))
    except ValueError:
        abort(400)

    con = db()
    try:
        con.execute("BEGIN IMMEDIATE")
        # 2) Повтор того же платежа - уже обработан, отвечаем «ок» и выходим.
        if con.execute("SELECT 1 FROM payments WHERE operation_id=?", (operation_id,)).fetchone():
            con.execute("COMMIT")
            return "OK"

        raw = urlencode(dict(form))
        now = int(time.time())

        # 3) Тестовое уведомление из настроек ЮMoney - монеты не начисляем.
        if form.get("test_notification") == "true":
            con.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?)", (operation_id, label, str(amount), 0, "test", now, raw))
            con.execute("COMMIT")
            return "OK"

        # 4) Перевод с протекцией (codepro) или ещё не зачисленный (unaccepted) - не начисляем.
        if form.get("codepro") == "true" or form.get("unaccepted") == "true" or currency != "643":
            con.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?)", (operation_id, label, str(paid), 0, "unmatched", now, raw))
            con.execute("COMMIT")
            return "OK"

        # 5) Донат «на жизнь проекта» (label=donate) - просто записываем, монеты не начисляются.
        if label == "DONATE":
            con.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?)", (operation_id, label, str(paid), 0, "donate", now, raw))
            con.execute("COMMIT")
            return "OK"

        # 6) Кому начислять: label должен быть ключом игрока.
        pack = pack_for(paid)
        if not USER_ID_RE.match(label) or pack is None:
            # Сумма не совпала ни с одним паком или нет ID - сохраняем для ручной проверки.
            con.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?)", (operation_id, label, str(paid), 0, "unmatched", now, raw))
            con.execute("COMMIT")
            return "OK"

        name, rub, coins = pack
        receipt_id = uuid.uuid4().hex
        sig = sign_receipt(receipt_id, label, coins, rub, operation_id, now)
        con.execute("INSERT INTO payments VALUES (?,?,?,?,?,?,?)", (operation_id, label, str(paid), coins, "credited", now, raw))
        con.execute("INSERT INTO receipts VALUES (?,?,?,?,?,?,?)", (receipt_id, label, operation_id, coins, rub, now, sig))
        con.execute(
            "INSERT INTO users(user_id, coins_balance) VALUES(?, ?) "
            "ON CONFLICT(user_id) DO UPDATE SET coins_balance = coins_balance + excluded.coins_balance",
            (label, coins),
        )
        con.execute("COMMIT")
        return "OK"
    except Exception:
        con.execute("ROLLBACK")
        raise
    finally:
        con.close()


# ---------------------------------------------------------------- 3. чеки для приложения

@app.get("/api/packs")
def packs():
    """
    Паки, которые принимает этот сервер: {"packs": {"bj_stack": [249, 700], …}}. Приложение
    показывает «Фишки для Блэкджека» только если сервер знает эти паки с теми же ценами
    (иначе оплата не нашла бы пак и монеты не пришли бы).
    """
    resp = jsonify({"packs": {name: [rub, coins] for name, (rub, coins) in config.PACKS.items()}})
    resp.headers["Cache-Control"] = "no-store"
    return resp


@app.get("/api/receipts")
def receipts():
    """
    Все чеки игрока по его секретному ID покупок. Приложение само помнит, какие чеки уже
    зачислило (база coin_purchases.db), поэтому сервер ничего не «отмечает».
    """
    user_id = (request.args.get("user_id", "") or "").upper()
    if not USER_ID_RE.match(user_id):
        abort(400)
    con = db()
    try:
        rows = con.execute(
            "SELECT receipt_id, user_id, coins, rub, operation_id, created, signature FROM receipts WHERE user_id=? ORDER BY created",
            (user_id,),
        ).fetchall()
        return jsonify({"receipts": [dict(r) for r in rows]})
    finally:
        con.close()


if __name__ == "__main__":
    db().close()
    app.run(host="0.0.0.0", port=8080)
