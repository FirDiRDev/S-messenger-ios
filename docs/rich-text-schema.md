# Оформление постов и сообщений: JSON-схема блоков

Модель документа редактора оформления (`app/src/main/kotlin/chat/RichText.kt`).
Корень — **массив** блоков. Текст внутри блоков может содержать строчную разметку:
`**жирный**`, `__курсив__`, `~~зачёркнутый~~`, `||спойлер||`, `⁣😀⁣` — живой эмодзи
(эмодзи между невидимыми разделителями U+2063).

По сети документ передаётся компактной построчной разметкой (`RichText.toMarkup`), чтобы
длинные сообщения можно было делить на части и чтобы старые версии видели читаемый текст.
`RichText.parse` + `RichText.toJson` / `RichText.fromJson` + `RichText.toMarkup` переводят
одно в другое без потерь. В приложении: редактор «✨ Оформление» → кнопка «{ } JSON».

| Блок | Разметка |
|---|---|
| `paragraph` | обычные строки |
| `header` (level 1/2) | `# Заголовок`, `## Подзаголовок` |
| `bullet-list` | `• пункт` (или `- пункт`) |
| `table` | строки `\| A \| B \|`, после шапки — `\|---\|---\|` |
| `tg-spoiler` | `\|\|скрытый текст\|\|` на отдельной строке |
| `collapsible-quote` | строки `>> текст` |
| `quote` | строки `> текст` |

## JSON Schema (draft 2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://github.com/FirDiRDev/S-messenger/rich-text.schema.json",
  "title": "S-messenger rich text document",
  "type": "array",
  "items": { "$ref": "#/$defs/block" },
  "$defs": {
    "inlineText": {
      "type": "string",
      "description": "Текст со строчной разметкой: **b**, __i__, ~~s~~, ||spoiler||, U+2063 emoji U+2063"
    },
    "block": {
      "oneOf": [
        {
          "type": "object",
          "required": ["type", "text"],
          "properties": {
            "type": { "const": "paragraph" },
            "text": { "$ref": "#/$defs/inlineText" }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "level", "text"],
          "properties": {
            "type": { "const": "header" },
            "level": { "enum": [1, 2] },
            "text": { "$ref": "#/$defs/inlineText" }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "items"],
          "properties": {
            "type": { "const": "bullet-list" },
            "items": { "type": "array", "minItems": 1, "items": { "$ref": "#/$defs/inlineText" } }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "rows"],
          "properties": {
            "type": { "const": "table" },
            "header": { "type": "boolean", "default": true, "description": "Первая строка - шапка" },
            "rows": {
              "type": "array",
              "minItems": 1,
              "items": { "type": "array", "minItems": 1, "items": { "type": "string" } }
            }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "text"],
          "properties": {
            "type": { "const": "tg-spoiler" },
            "text": { "type": "string" }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "text"],
          "properties": {
            "type": { "const": "collapsible-quote" },
            "text": { "$ref": "#/$defs/inlineText" },
            "collapsed": { "type": "boolean", "default": true }
          },
          "additionalProperties": false
        },
        {
          "type": "object",
          "required": ["type", "text"],
          "properties": {
            "type": { "const": "quote" },
            "text": { "$ref": "#/$defs/inlineText" }
          },
          "additionalProperties": false
        }
      ]
    }
  }
}
```

## Пример документа со всеми блоками

```json
[
  { "type": "header", "level": 1, "text": "Итоги недели" },
  {
    "type": "paragraph",
    "text": "Привет! Это **жирный**, __курсив__ и ~~зачёркнутый~~ текст, а ещё ||спойлер|| прямо в строке ⁣🔥⁣."
  },
  {
    "type": "bullet-list",
    "items": ["Вышла версия 4.8", "Опросы и кнопки в сообщениях", "Файлы прямо в тексте"]
  },
  {
    "type": "table",
    "header": true,
    "rows": [["Платформа", "Загрузки"], ["Android", "12 400"], ["ПК", "3 100"]]
  },
  { "type": "tg-spoiler", "text": "Секрет: в следующей версии - живые анимации" },
  {
    "type": "collapsible-quote",
    "text": "Длинная цитата из обсуждения.\nВторая строка видна после раскрытия.\nИ третья.",
    "collapsed": true
  }
]
```

То же в разметке передачи:

```
# Итоги недели
Привет! Это **жирный**, __курсив__ и ~~зачёркнутый~~ текст, а ещё ||спойлер|| прямо в строке ⁣🔥⁣.
• Вышла версия 4.8
• Опросы и кнопки в сообщениях
• Файлы прямо в тексте
| Платформа | Загрузки |
|---|---|
| Android | 12 400 |
| ПК | 3 100 |
||Секрет: в следующей версии - живые анимации||
>> Длинная цитата из обсуждения.
>> Вторая строка видна после раскрытия.
>> И третья.
```

## Как устроены «живые» части (`chat/RichSpans.kt`, `chat/SelectionMenu.kt`)

- **Спойлер**: текст под `SpoilerSpan` рисуется прозрачным; поверх, в overlay самого TextView,
  `SpoilerOverlay` рисует мерцающие частицы точно по прямоугольникам строк (координаты из
  `Layout`). Нажатие — частицы разлетаются и гаснут за 650 мс, текст проявляется.
- **Сворачиваемая цитата**: свёрнута — первая строка и «▾ Развернуть»; нажатие пересобирает
  текст и плавно анимирует высоту TextView (ValueAnimator по высоте, затем WRAP_CONTENT).
- **Живые эмодзи в строке**: `EmojiSpan` (DynamicDrawableSpan) — пока картинка грузится,
  рисуется обычный символ; затем анимированный WebP (AnimatedImageDrawable) размером со строку,
  перерисовка — через Drawable.Callback к TextView; перенос строк не ломается.
- **Меню над выделением**: системная панель заменена своей; после каждой перерисовки поля
  проверяется выделение, панель (`GlassFrameLayout` — размытие содержимого под ней, кромка,
  блики) встаёт над словом по координатам `Layout`, появляется с пружиной
  x(t) = 1 − e^(−ζωt)·cos(ω_d·t).
