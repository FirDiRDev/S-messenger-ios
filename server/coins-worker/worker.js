// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

/**
 * Сервер монет S-messenger для Cloudflare Workers (бесплатно, без своего хостинга).
 * Делает то же, что server/coins/server.py:
 *  - POST /yoomoney/notify - HTTP-уведомление ЮMoney: проверка подписи sha1_hash секретным
 *    словом, защита от повторов (operation_id), точное совпадение суммы с паком, выпуск
 *    чека, подписанного ECDSA P-256 (приложение проверяет подпись открытым ключом);
 *  - GET  /api/receipts?user_id=… - чеки игрока (user_id - его секретный ID покупок);
 *  - GET  /api/packs - паки, которые принимает сервер (приложение показывает «Фишки для
 *    Блэкджека», только если сервер их знает);
 *  - GET  /setup?secret=… - один раз: создаёт ключи подписи и показывает открытый ключ
 *    и готовый блок для update.json;
 *  - GET  /paid - страница «Оплата прошла, вернитесь в приложение».
 *
 * Хранилище - Workers KV (привязка COINS):
 *   users:   u:<user_id>      -> coins_balance (сколько монет куплено всего)
 *   платежи: op:<operation_id> -> {status, user, rub, coins, created}
 *   чеки:    r:<user_id>       -> [{receipt_id, user_id, coins, rub, operation_id, created, signature}]
 *   ключи:   key:private (JWK), key:public (base64 DER)
 *
 * Переменные (Settings → Variables): NOTIFICATION_SECRET (секретное слово из настроек
 * HTTP-уведомлений ЮMoney, тип «Secret»), WALLET (номер кошелька 41001…).
 */

// Паки: название -> [рубли, монеты]. Такие же - в приложении (CoinShop.PACKS).
const PACKS = {
  starter: [99, 250],
  handful: [150, 300],
  bag: [300, 600],
  chest: [500, 1500],
  treasure: [1000, 3500],
  // «Фишки для Блэкджека» - те же монеты; пак узнаётся по точной сумме, цены не совпадают с паками выше.
  bj_stack: [249, 700],
  bj_case: [749, 2400],
  bj_highroller: [1490, 5500],
};

const USER_ID_RE = /^[0-9A-F]{64}$/;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/yoomoney/notify") return await notify(request, env);
      if (request.method === "GET" && url.pathname === "/api/receipts") return await receipts(url, env);
      if (request.method === "GET" && url.pathname === "/api/packs") {
        return new Response(JSON.stringify({ packs: PACKS }), { headers: { "content-type": "application/json", "cache-control": "no-store" } });
      }
      if (request.method === "GET" && url.pathname === "/setup") return await setup(url, env, request);
      if (request.method === "GET" && url.pathname === "/paid") return html("Оплата прошла ✅", "Монеты придут в S-messenger сами через несколько секунд. Вернитесь в приложение.");
      if (request.method === "GET" && url.pathname.startsWith("/u/")) return profilePage(url);
      if (request.method === "GET" && url.pathname === "/") return html("S-messenger · сервер монет", "Работает.");
      return new Response("Not found", { status: 404 });
    } catch (e) {
      return new Response("Error: " + (e && e.message ? e.message : e), { status: 500 });
    }
  },
};

/**
 * Ссылка на профиль S-messenger: https://<воркер>/u/<ID>?n=<имя>.
 * Страница сразу открывает приложение (Android - intent://, иначе smessenger:), а приложение
 * само отправляет заявку в друзья. Нет приложения - кнопка «Скачать».
 */
const DOWNLOAD = "https://github.com/FirDiRDev/S-messenger/releases/latest";
function profilePage(url) {
  const id = (url.pathname.slice(3).match(/^[0-9A-Fa-f]{76}/) || [""])[0].toUpperCase();
  if (!id) return html("S-messenger", "Ссылка повреждена - попросите отправить её ещё раз.");
  const name = (url.searchParams.get("n") || "").slice(0, 40).replace(/[<>&"']/g, "");
  const app = "smessenger:add/" + id;
  const intent = "intent://add/" + id + "#Intent;scheme=smessenger;package=com.stoyko.smessenger;S.browser_fallback_url=" + encodeURIComponent(DOWNLOAD) + ";end";
  const page = `<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${name ? name + " · " : ""}S-messenger</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:radial-gradient(circle at 30% 0,#3b2a8f,#0f0b24 60%);color:#fff;min-height:100vh;display:grid;place-items:center;text-align:center;padding:16px;box-sizing:border-box}
.c{max-width:380px;width:100%}.a{width:96px;height:96px;border-radius:50%;margin:0 auto 14px;display:grid;place-items:center;font-size:44px;background:linear-gradient(135deg,#8b5cf6,#0ea5e9);box-shadow:0 0 0 6px rgba(139,92,246,.25);animation:p 2s ease-in-out infinite}
@keyframes p{50%{box-shadow:0 0 0 14px rgba(139,92,246,.08)}}h1{font-size:24px;margin:6px 0}p{color:#c7c2e6;margin:6px 0 18px}
a.b{display:block;padding:15px;border-radius:16px;font-weight:700;text-decoration:none;color:#fff;margin:10px 0;background:linear-gradient(90deg,#7c3aed,#2563eb)}a.s{background:rgba(255,255,255,.1)}small{color:#8b85a8;word-break:break-all}</style></head>
<body><div class="c"><div class="a">💬</div><h1>${name || "Профиль в S-messenger"}</h1>
<p>Откройте ссылку в S-messenger - заявка в друзья отправится автоматически.</p>
<a class="b" id="o" href="${app}">Открыть в S-messenger</a><a class="b s" href="${DOWNLOAD}">Скачать S-messenger</a>
<small>ID: ${id}</small></div>
<script>var ua=navigator.userAgent;var o=document.getElementById('o');if(/Android/i.test(ua)){o.href=${JSON.stringify(intent)};}setTimeout(function(){location.href=o.href},350);</script></body></html>`;
  return new Response(page, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "public, max-age=3600" } });
}

function html(title, text) {
  const page = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><body style="font-family:system-ui;background:#120e2e;color:#fff;display:grid;place-items:center;min-height:90vh;text-align:center;padding:16px">
<div><h1 style="color:#fde047">${title}</h1><p style="font-size:18px">${text}</p></div>`;
  return new Response(page, { headers: { "content-type": "text/html; charset=utf-8" } });
}

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));

/** Сравнение без утечки по времени. */
function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

/** Подпись уведомления: sha1(notification_type&operation_id&amount&currency&datetime&sender&codepro&secret&label). */
async function checkSha1(form, secret) {
  const parts = ["notification_type", "operation_id", "amount", "currency", "datetime", "sender", "codepro"].map((k) => form.get(k) || "");
  parts.push(secret, form.get("label") || "");
  const digest = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(parts.join("&")));
  return safeEqual(hex(digest), (form.get("sha1_hash") || "").toLowerCase());
}

/** Пак по точной сумме в копейках. */
function packFor(paid) {
  for (const [name, [rub, coins]] of Object.entries(PACKS)) {
    if (Math.round(paid * 100) === rub * 100) return { name, rub, coins };
  }
  return null;
}

/** WebCrypto отдаёт подпись как r||s (64 байта), приложению (Java) нужен формат DER. */
function rawToDer(raw) {
  const bytes = new Uint8Array(raw);
  const int = (b) => {
    let i = 0;
    while (i < b.length - 1 && b[i] === 0) i++;
    b = b.slice(i);
    if (b[0] & 0x80) {
      const n = new Uint8Array(b.length + 1);
      n.set(b, 1);
      b = n;
    }
    return b;
  };
  const r = int(bytes.slice(0, 32));
  const s = int(bytes.slice(32, 64));
  const out = new Uint8Array(6 + r.length + s.length);
  out.set([0x30, 4 + r.length + s.length, 0x02, r.length], 0);
  out.set(r, 4);
  out.set([0x02, s.length], 4 + r.length);
  out.set(s, 6 + r.length);
  return out;
}

async function privateKey(env) {
  const jwk = await env.COINS.get("key:private");
  if (!jwk) throw new Error("ключи не созданы - откройте /setup?secret=…");
  return crypto.subtle.importKey("jwk", JSON.parse(jwk), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

async function signReceipt(env, id, user, coins, rub, op, created) {
  const payload = `smc1|${id}|${user}|${coins}|${rub}|${op}|${created}`;
  const raw = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, await privateKey(env), new TextEncoder().encode(payload));
  return b64(rawToDer(raw));
}

async function notify(request, env) {
  const form = new URLSearchParams(await request.text());
  // 1) Подпись. Не совпала - это не ЮMoney: ничего не делаем.
  if (!env.NOTIFICATION_SECRET || !(await checkSha1(form, env.NOTIFICATION_SECRET))) return new Response("bad signature", { status: 403 });
  const op = form.get("operation_id") || "";
  if (!op) return new Response("no operation", { status: 400 });
  const label = (form.get("label") || "").toUpperCase();
  const paid = parseFloat(form.get("withdraw_amount") || form.get("amount") || "0");
  const now = Math.floor(Date.now() / 1000);
  // 2) Повтор того же платежа - уже обработан.
  if (await env.COINS.get("op:" + op)) return new Response("OK");
  const save = (status, extra = {}) => env.COINS.put("op:" + op, JSON.stringify({ status, user: label, paid, created: now, ...extra }));
  // 3) Тест из настроек ЮMoney.
  if (form.get("test_notification") === "true") {
    await save("test");
    return new Response("OK");
  }
  // 4) Протекция, не зачислен, не рубли - не начисляем.
  if (form.get("codepro") === "true" || form.get("unaccepted") === "true" || form.get("currency") !== "643") {
    await save("unmatched");
    return new Response("OK");
  }
  // 5) Донат «на жизнь проекта».
  if (label === "DONATE") {
    await save("donate");
    return new Response("OK");
  }
  // 6) Пак и игрок.
  const pack = packFor(paid);
  if (!USER_ID_RE.test(label) || !pack) {
    await save("unmatched");
    return new Response("OK");
  }
  const id = crypto.randomUUID().replace(/-/g, "");
  const signature = await signReceipt(env, id, label, pack.coins, pack.rub, op, now);
  const list = JSON.parse((await env.COINS.get("r:" + label)) || "[]");
  if (!list.some((x) => x.operation_id === op)) {
    list.push({ receipt_id: id, user_id: label, coins: pack.coins, rub: pack.rub, operation_id: op, created: now, signature });
    await env.COINS.put("r:" + label, JSON.stringify(list));
    const bal = parseInt((await env.COINS.get("u:" + label)) || "0", 10) + pack.coins;
    await env.COINS.put("u:" + label, String(bal));
  }
  await save("credited", { coins: pack.coins, rub: pack.rub, receipt_id: id });
  return new Response("OK");
}

async function receipts(url, env) {
  const user = (url.searchParams.get("user_id") || "").toUpperCase();
  if (!USER_ID_RE.test(user)) return new Response("bad user", { status: 400 });
  const list = JSON.parse((await env.COINS.get("r:" + user)) || "[]");
  // no-store: прокси и кэши по дороге не должны отдавать старый список (чек «не приходил»).
  return new Response(JSON.stringify({ receipts: list }), { headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

async function setup(url, env, request) {
  if (!env.NOTIFICATION_SECRET || url.searchParams.get("secret") !== env.NOTIFICATION_SECRET) {
    return new Response("Укажите ?secret=<NOTIFICATION_SECRET>", { status: 403 });
  }
  let pub = await env.COINS.get("key:public");
  if (!pub) {
    const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
    await env.COINS.put("key:private", JSON.stringify(await crypto.subtle.exportKey("jwk", pair.privateKey)));
    pub = b64(await crypto.subtle.exportKey("spki", pair.publicKey));
    await env.COINS.put("key:public", pub);
  }
  const origin = new URL(request.url).origin;
  const block = JSON.stringify({ coinShop: { wallet: env.WALLET || "41001…", server: origin, publicKey: pub } }, null, 2);
  return html(
    "Сервер монет готов ✅",
    `1) В ЮMoney → Настройки → HTTP-уведомления укажите адрес <b>${origin}/yoomoney/notify</b> и то же секретное слово.<br><br>` +
      `2) Добавьте в update.json (в корне репозитория) этот блок:<pre style="text-align:left;background:#000;padding:12px;border-radius:12px;white-space:pre-wrap;word-break:break-all">${block}</pre>` +
      `Ключи созданы один раз - повторное открытие этой страницы их не меняет.`,
  );
}
