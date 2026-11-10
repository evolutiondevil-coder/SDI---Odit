# Odit OBD — данни (пакети)

Таблиците, които приложението ползва, са JSON файлове тук. Същите файлове:

- се вграждат в APK-то при билд (офлайн основа) — Gradle задачата `copyOditData`;
- се публикуват от клон `main` на това хранилище; приложението ги тегли, когато е включена
  „Онлайн помощ“, има интернет и в `manifest.json` има по-нова версия.

Адрес, от който приложението чете:
`https://raw.githubusercontent.com/evolutiondevil-coder/SDI---Odit/main/odit-data/manifest.json`

## Структура

```
odit-data/
  manifest.json          списък на пакетите: id, version, file, bytes, sha256, makes
  packs/
    wmi.json             производител и държава по позиции 1–3 на VIN
    vin-rules.json       модел и година по VIN, правила по марки
    modules.json         имена на модули по марки (CAN адреси)
    dtc-generic.json     допълнение към общите кодове на грешки
    dtc-ford.json …      кодове на производителя (ползват се само за P1, P30–P33, B1/B2, C1/C2, U1/U2
                         и само при разпозната марка от "makes")
  tools/
    build_wmi.py         wmi.json от статията в Wikipedia
    build_dtc.py         dtc-*.json от таблиците в скрипта
    build_manifest.py    преизчислява manifest.json
```

## Общ формат на пакет

```json
{
  "id": "dtc-ford",            // = името на файла
  "version": 3,                // цяло число; увеличете го при всяка промяна
  "schema": 1,                 // формат; приложението отказва по-нов, отколкото разбира
  "title": "Кодове на производителя: Ford",
  "sources": [{"name": "...", "url": "...", "license": "...", "retrieved": "2026-11-10"}],
  "verified": false,           // по желание: дали данните са сверени с първоизточник
  "makes": ["Ford", "Lincoln"],// само за пакети по марки
  "data": { ... }              // съдържанието, различно за всеки вид
}
```

Съдържание (`data`):

- `dtc-*`: `{"codes": {"P1450": "описание", ...}}`
- `modules`: `{"groups": {"VAG": [{"tx": "713", "rx": "77D", "name": "ABS / ESP"}, ...]}}`
  (групи: VAG, FORD, TOYOTA, HYUNDAI_KIA, RENAULT_NISSAN)
- `wmi`: `{"order": "...", "countries": [{"from": "XA", "to": "XC", "country": "България"}],
  "wmi": {"WVW": {"maker": "...", "make": "Volkswagen", "group": "VAG", "country": "Германия"}}}`
- `vin-rules`: `{"yearCodes": "...", "default": {...}, "northAmerica": {...}, "maps": {...},
  "makes": {"Volkswagen": {"years": [{"pos": 10, "policy": "reliable"}],
  "models": [{"if": {"pos": "4-6", "is": "ZZZ"}, "code": "7-8", "label": "тип", "map": "vw"}]}}}`
  — `policy`: `reliable` (показва се като сигурна), `probable` („вероятно“), `none` (не се показва).

## Как да обновите таблица

1. Редактирайте пакета (или скрипта в `tools/` и го пуснете от папка `odit-data`).
2. Увеличете `version` на пакета.
3. `python3 tools/build_manifest.py`
4. Commit в `main`. Телефоните го теглят при следващата проверка (до 1 път на ден или от
   „Информация“ → „Провери за обновени данни“). Тестът `PacksTest.manifestMatchesPacks`
   проверява, че манифестът съвпада с файловете.

Приложението проверява размера и SHA-256 на всеки файл, `id`, `version` и `schema` преди да го
приеме; ако нещо не съвпада, остава старата версия.

## Източници и лицензи

- `wmi.json`, `vin-rules.json`: Wikipedia и Wikibooks, **CC BY-SA 4.0** — при промяна запазете
  източника; производните таблици остават под същия лиценз.
- `dtc-generic.json`, `dtc-<марка>.json`, `modules.json`: собствени описания на Odit OBD (CC0 1.0).
  Значенията на кодовете не са сверени ред по ред с първоизточник (SAE J2012 е платен, а
  документацията на производителите не е публична) — затова са с `"verified": false` и
  приложението показва „непроверено“ при кодовете на производителя.
- Данни, чийто лиценз не позволява разпространение, не се добавят тук.
