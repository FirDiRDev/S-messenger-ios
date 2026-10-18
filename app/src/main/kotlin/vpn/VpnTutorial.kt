// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.content.Context
import com.stoyko.smessenger.ui.Guide

private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)

/**
 * Обучение VPN: что даёт каждый режим (Серверы, Обход DPI, WARP), как его получить, зачем он
 * и помогает ли при «белых списках». Показывается при первом открытии VPN и по кнопке «?».
 */
object VpnTutorial {
    private const val PREFS = "vpn_tutorial"

    private fun pages() = listOf(
        Guide.Page(
            "🛡",
            t("VPN в S-messenger", "VPN in S-messenger"),
            t(
                "Здесь три способа обойти блокировки: 🌐 Серверы, 🛡 Обход DPI и ☁️ WARP. Работает всегда один из них.\n\n" +
                    "«🙂 Обычный» вид - одна большая кнопка: в режиме «Авто» приложение само выберет, чем подключиться. " +
                    "«🛠 Продвинутый» - все настройки каждого режима.\n\nДальше - коротко о каждом режиме и о «белых списках».",
                "There are three ways around blocking: 🌐 Servers, 🛡 DPI bypass and ☁️ WARP. Only one works at a time.\n\n" +
                    "The “🙂 Simple” view is one big button: in “Auto” the app picks how to connect. " +
                    "“🛠 Advanced” has every setting of every mode.\n\nNext - a short guide to each mode and to “whitelists”.",
            ),
        ),
        Guide.Page(
            "🌐",
            t("Серверы - самый надёжный способ", "Servers - the most reliable way"),
            t(
                "ЗАЧЕМ: трафик идёт через сервер в другой стране - открывается всё, меняется ваш IP, мессенджер находит сеть даже там, где узлы блокируют.\n\n" +
                    "КАК ПОЛУЧИТЬ:\n• В приложении уже есть бесплатная подписка ProxyNova - нажмите «🔄 Обновить», серверы загрузятся сами.\n" +
                    "• Есть ссылка vless://, vmess://, trojan://, ss:// или ссылка-подписка - скопируйте её и нажмите «📋 Вставить».\n" +
                    "• Друг может поделиться сервером прямо в чате.\n• Самый стабильный вариант - свой сервер (VPS) или платная подписка.\n\n" +
                    "Нажмите «Сервер для узлов» - приложение проверит все серверы и выберет лучший (подробнее - на следующих страницах).",
                "WHY: traffic goes through a server abroad - everything opens, your IP changes, and the messenger reaches the network even where nodes are blocked.\n\n" +
                    "HOW TO GET:\n• The app already has a free ProxyNova subscription - tap “🔄 Refresh” and servers load by themselves.\n" +
                    "• Got a vless://, vmess://, trojan://, ss:// or subscription link - copy it and tap “📋 Paste”.\n" +
                    "• A friend can share a server right in a chat.\n• The most stable option is your own server (VPS) or a paid subscription.\n\n" +
                    "Tap “Server for nodes” - the app tests every server and picks the best one (details on the next pages).",
            ),
        ),
        Guide.Page(
            "🔗",
            t("Сервер для узлов - зачем он", "Server for nodes - why"),
            t(
                "Мессенджер работает без центрального сервера: он сам находит в интернете «узлы» сети и через них доставляет сообщения.\n\n" +
                    "У некоторых провайдеров и мобильных операторов узлы закрыты - тогда мессенджер долго «Подключается…» или не подключается совсем.\n\n" +
                    "Решение - пустить мессенджер через VPN-сервер, который до узлов достаёт (на схеме: прямой путь перечёркнут, сообщения идут через 🛡). " +
                    "Но не каждый сервер подходит: одни медленные, другие сами не пускают к узлам. Поэтому сервер надо подобрать.",
                "The messenger has no central server: it finds network “nodes” on the internet and delivers messages through them.\n\n" +
                    "Some providers and mobile carriers block the nodes - then the messenger stays “Connecting…” for a long time or never connects.\n\n" +
                    "The fix is to route the messenger through a VPN server that can reach the nodes (on the diagram: the direct path is crossed out, messages go via 🛡). " +
                    "Not every server works: some are slow, others don't reach the nodes themselves. So the server has to be picked.",
            ),
            scene = { c -> VpnScenes.route(c) },
        ),
        Guide.Page(
            "🧭",
            t("Как идёт подбор", "How the tuning works"),
            t(
                "Нажмите «Сервер для узлов» (в обычном виде или во вкладке «Серверы»). Подбор идёт в фоне, ход виден в шторке, окно можно закрыть.\n\n" +
                    "Шаг 1. Пинг всех серверов сразу - неотвечающие отсеиваются (красные на схеме).\n" +
                    "Шаг 2. Через каждый живой сервер приложение пробным подключением открывает каталог узлов сети и проверочный адрес и меряет, сколько это заняло.\n\n" +
                    "Важно: ваш VPN при этом НЕ переключается и мессенджер НЕ отключается от сети - проверка идёт отдельно, вы продолжаете переписку.\n\n" +
                    "Чем короче полоска - тем быстрее сервер. Лучшему достаётся 👑.",
                "Tap “Server for nodes” (in the simple view or on the “Servers” tab). Tuning runs in the background, progress is in the shade, you can close the window.\n\n" +
                    "Step 1. All servers are pinged at once - the silent ones drop out (red on the diagram).\n" +
                    "Step 2. Through each live server the app opens the network node catalog and a test address with a trial connection and measures the time.\n\n" +
                    "Important: your VPN is NOT switched and the messenger does NOT drop off the network - the check runs separately, you keep chatting.\n\n" +
                    "The shorter the bar, the faster the server. The best one gets the 👑.",
            ),
            scene = { c -> VpnScenes.testing(c) },
        ),
        Guide.Page(
            "🔔",
            t("Что потом", "What next"),
            t(
                "Когда подбор закончится, придёт уведомление «Найден лучший VPN для узлов». Этот сервер станет выбранным по умолчанию, а трафик мессенджера - идти через VPN.\n\n" +
                    "Сам VPN приложение НЕ включает - вы решаете, когда: нажмите большую кнопку VPN (или плитку в шторке быстрых настроек).\n\n" +
                    "Советы:\n• Повторяйте подбор, если связь снова стала медленной - серверы в подписках меняются.\n" +
                    "• Перед подбором нажмите «🔄 Обновить», чтобы загрузить свежие серверы.\n" +
                    "• Если ни один сервер не подошёл - добавьте свою ссылку или подписку, либо попробуйте Wi-Fi.",
                "When tuning finishes you get a “Best VPN for nodes found” notification. That server becomes the default, and messenger traffic is routed through VPN.\n\n" +
                    "The app does NOT turn the VPN on - you decide when: tap the big VPN button (or the quick settings tile).\n\n" +
                    "Tips:\n• Repeat the tuning if the connection gets slow again - subscription servers change.\n" +
                    "• Tap “🔄 Refresh” before tuning to load fresh servers.\n" +
                    "• If no server fits - add your own link or subscription, or try Wi-Fi.",
            ),
            scene = { c -> VpnScenes.result(c) },
        ),
        Guide.Page(
            "🛡",
            t("Обход DPI - без сервера", "DPI bypass - no server needed"),
            t(
                "ЗАЧЕМ: провайдер узнаёт заблокированные сайты по «отпечатку» соединения (DPI). Обход режет и перемешивает пакеты так, что фильтр их не узнаёт. " +
                    "Открываются YouTube и другие заблокированные сайты, скорость почти не падает, ваш IP и страна не меняются.\n\n" +
                    "КАК ПОЛУЧИТЬ: ничего не нужно - движок встроен. Нажмите «Стратегия DPI»: приложение в фоне переберёт все стратегии и включит лучшую " +
                    "(напротив каждого сервиса видно - ✓ работает или ✗ закрыт).",
                "WHY: providers recognize blocked sites by the connection “fingerprint” (DPI). The bypass splits and shuffles packets so the filter doesn't recognize them. " +
                    "YouTube and other blocked sites open, speed barely drops, your IP and country don't change.\n\n" +
                    "HOW TO GET: nothing needed - the engine is built in. Tap “DPI strategy”: the app tests all strategies in the background and applies the best one " +
                    "(each service shows ✓ working or ✗ blocked).",
            ),
        ),
        Guide.Page(
            "☁️",
            t("WARP - бесплатно от Cloudflare", "WARP - free from Cloudflare"),
            t(
                "ЗАЧЕМ: бесплатный туннель Cloudflare (AmneziaWG с маскировкой). Меняет IP, хорошо подходит для сайтов и приложений.\n\n" +
                    "КАК ПОЛУЧИТЬ: «🛠 Продвинутый» → вкладка WARP → «Получить конфиг» - одно нажатие, регистрация не нужна. Можно импортировать свой .conf.\n\n" +
                    "У некоторых провайдеров WARP заблокирован - тогда используйте серверы или обход DPI. При смене WARP на другой режим приложение " +
                    "перезапускается на секунду - так устроены ядра VPN.",
                "WHY: a free Cloudflare tunnel (AmneziaWG with obfuscation). Changes your IP, good for sites and apps.\n\n" +
                    "HOW TO GET: “🛠 Advanced” → WARP tab → “Get config” - one tap, no sign-up. You can also import your own .conf.\n\n" +
                    "Some providers block WARP - then use servers or DPI bypass. Switching between WARP and other modes restarts the app for a second - " +
                    "that's how the VPN engines work.",
            ),
        ),
        Guide.Page(
            "📵",
            t("А если «белые списки»?", "What about “whitelists”?"),
            t(
                "При «белых списках» (их включают на мобильном интернете) оператор пропускает ТОЛЬКО разрешённые сайты и адреса, всё остальное закрыто полностью.\n\n" +
                    "• Обход DPI - не помогает: блокируют по адресу, а не по «отпечатку».\n" +
                    "• WARP - не помогает: адресов Cloudflare в списке нет.\n" +
                    "• Серверы - помогают только особые серверы, адреса которых попали в белый список. Встроенная подписка ProxyNova «Обход БС (Тест)» как раз из таких: " +
                    "нажмите «🔄 Обновить» и «Сервер для узлов». Гарантий нет - такие серверы быстро меняются, обновляйте подписку.\n" +
                    "• Wi-Fi белые списки обычно не затрагивают - при проблемах подключитесь к Wi-Fi.",
                "With “whitelists” (enabled on mobile internet) the carrier lets through ONLY approved sites and addresses; everything else is fully closed.\n\n" +
                    "• DPI bypass - doesn't help: blocking is by address, not by fingerprint.\n" +
                    "• WARP - doesn't help: Cloudflare addresses aren't on the list.\n" +
                    "• Servers - only special servers whose addresses are whitelisted help. The built-in ProxyNova “Whitelist bypass (Test)” subscription is one of those: " +
                    "tap “🔄 Refresh” and “Server for nodes”. No guarantees - such servers change quickly, refresh the subscription.\n" +
                    "• Wi-Fi is usually not affected - connect to Wi-Fi if you have trouble.",
            ),
        ),
        Guide.Page(
            "🤖",
            t("Всё само", "Fully automatic"),
            t(
                "• «Авто»: большая кнопка сама выбирает самый быстрый рабочий сервер, а без серверов - обход DPI.\n" +
                    "• Подбор сервера и стратегии идёт в фоне, прогресс - в шторке; лучший сервер для узлов выбирается по умолчанию, а включаете VPN вы сами.\n" +
                    "• Нерабочие серверы можно удалять автоматически, самый быстрый выбирается сам.\n\n" +
                    "Обучение можно открыть снова кнопкой «?» вверху экрана VPN.",
                "• “Auto”: the big button picks the fastest working server, or DPI bypass if there are no servers.\n" +
                    "• Server and strategy tuning run in the background, progress is in the shade; the best server for nodes becomes the default, and you turn the VPN on yourself.\n" +
                    "• Dead servers can be removed automatically, the fastest one is picked by itself.\n\n" +
                    "Open this guide again with the “?” button at the top of the VPN screen.",
            ),
        ),
    )

    fun show(ctx: Context) = Guide.show(ctx, pages())

    fun showOnce(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getBoolean("shown_v2", false)) return
        p.edit().putBoolean("shown_v2", true).apply()
        show(ctx)
    }
}
