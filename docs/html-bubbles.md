# HTML-пузыри

Сообщение-мини-приложение: HTML сжимается (deflate, base64) и кладётся в заголовок сообщения
(`ChannelPost`, ключ `"h"`), видимый текст - подпись. Предел - `HtmlBubbles.MAX_PACKED` (1150 символов
в сжатом виде, одно сообщение Tox).

Песочница: WebView без сети (`blockNetworkLoads`, CSP `default-src 'none'`, перехват запросов), без
файлов и окон. Ссылки открываются только после подтверждения. `reply`/`copy`/`toast` работают лишь
сразу после нажатия человека (1,5 с), ответ - не чаще раза в секунду.

Встроенный набор:
- стили: `.card .t .m .b (g r o s) .row .pic .pr .old .tag`
- кнопки без JS: `data-reply="текст"`, `data-copy="текст"`, `data-url="https://…"`
- функции: `reply(t)`, `copy(t)`, `go(url)`, `toast(t)`
- график: `chart('id', [числа], ['подписи'], {type:'line'|'bar', live:1, suffix:' ₽', color:'#8B7CFF'})`

Пример:
```html
<div class=card><div class=t>Идём в кино?</div>
<div class=row><button class="b g" data-reply="Да">Да</button><button class="b r" data-reply="Нет">Нет</button></div></div>
```

## Живые сообщения (галерея)

Встроенные шаблоны лежат в `app/src/main/assets/bubbles/<id>.html` (launch, easing, music, pipeline,
streaming, clock, linkpreview, reactions, activity, slots, counter, tictactoe, poll, boardingpass,
product, revenue, faq). В сообщении передаётся только id шаблона (ключ `"ht"` заголовка ChannelPost),
сам HTML берётся из приложения получателя; у старой версии - подсказка обновиться.

Отправка: «/» в пустом поле ввода (список с фильтром), или «Отправить» (удерживать) → «🧩 HTML-пузырь»
→ «✨ Живые сообщения» (с просмотром). Новый шаблон: положить файл в assets/bubbles и добавить строку
в `HtmlBubbles.TEMPLATES`. В шаблоне доступны функции набора (`reply`, `copy`, `go`, `toast`, `chart`)
и флаг `SBL` (облегчённый режим - меньше частиц).
