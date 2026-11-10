#!/usr/bin/env python3
"""Builds packs/wmi.json from the wikitext of the English Wikipedia article
"Vehicle identification number" (CC BY-SA 4.0).

Usage:
  curl -A "OditOBD-data" "https://en.wikipedia.org/w/index.php?title=Vehicle_identification_number&action=raw" > vin.wiki
  python3 tools/build_wmi.py vin.wiki packs/wmi.json
"""
import json
import re
import sys
from datetime import date

ORDER = "ABCDEFGHJKLMNPRSTUVWXYZ1234567890"  # ISO 3780 order: letters first, zero last

COUNTRY_BG = {
    "South Africa": "ЮАР", "Morocco": "Мароко", "Egypt": "Египет", "Nigeria": "Нигерия", "Algeria": "Алжир",
    "Tunisia": "Тунис", "Kenya": "Кения", "China": "Китай", "Japan": "Япония", "Israel": "Израел",
    "South Korea": "Южна Корея", "Jordan": "Йордания", "Kyrgyzstan": "Киргизстан", "India": "Индия",
    "Indonesia": "Индонезия", "Thailand": "Тайланд", "Myanmar": "Мианмар", "Mongolia": "Монголия",
    "Kazakhstan": "Казахстан", "Iran": "Иран", "Pakistan": "Пакистан", "Iraq": "Ирак", "Turkey": "Турция",
    "Uzbekistan": "Узбекистан", "Azerbaijan": "Азербайджан", "Tajikistan": "Таджикистан", "Armenia": "Армения",
    "Philippines": "Филипини", "Singapore": "Сингапур", "Malaysia": "Малайзия", "Bangladesh": "Бангладеш",
    "Cambodia": "Камбоджа", "United Arab Emirates": "ОАЕ", "Taiwan": "Тайван", "Vietnam": "Виетнам",
    "Laos": "Лаос", "Saudi Arabia": "Саудитска Арабия", "Hong Kong": "Хонконг", "Russia": "Русия",
    "United Kingdom": "Великобритания", "Germany": "Германия", "Poland": "Полша", "Latvia": "Латвия",
    "Georgia": "Грузия", "Iceland": "Исландия", "Switzerland": "Швейцария", "Czech Republic": "Чехия",
    "Czechia": "Чехия", "Hungary": "Унгария", "Portugal": "Португалия", "Serbia": "Сърбия",
    "Andorra": "Андора", "Netherlands": "Нидерландия", "Spain": "Испания", "Denmark": "Дания",
    "Ireland": "Ирландия", "Romania": "Румъния", "North Macedonia": "Северна Македония",
    "Slovakia": "Словакия", "Bosnia and Herzegovina": "Босна и Херцеговина", "Austria": "Австрия",
    "France": "Франция", "Croatia": "Хърватия", "Estonia": "Естония", "Bulgaria": "България",
    "Greece": "Гърция", "Luxembourg": "Люксембург", "Belgium": "Белгия", "Finland": "Финландия",
    "Malta": "Малта", "Sweden": "Швеция", "Norway": "Норвегия", "Belarus": "Беларус", "Ukraine": "Украйна",
    "Italy": "Италия", "Slovenia": "Словения", "Lithuania": "Литва", "Moldova": "Молдова",
    "Montenegro": "Черна гора", "Cyprus": "Кипър", "Liechtenstein": "Лихтенщайн", "Monaco": "Монако",
    "San Marino": "Сан Марино", "Albania": "Албания", "Kosovo": "Косово",
    "United States": "САЩ", "United States of America": "САЩ", "USA": "САЩ", "Canada": "Канада",
    "Mexico": "Мексико", "Costa Rica": "Коста Рика", "Cayman Islands": "Кайманови острови",
    "Australia": "Австралия", "New Zealand": "Нова Зеландия", "Brazil": "Бразилия", "Argentina": "Аржентина",
    "Chile": "Чили", "Colombia": "Колумбия", "Venezuela": "Венецуела", "Ecuador": "Еквадор", "Peru": "Перу",
    "Uruguay": "Уругвай", "Trinidad and Tobago": "Тринидад и Тобаго", "Côte d'Ivoire": "Кот д'Ивоар",
    "Angola": "Ангола", "Ethiopia": "Етиопия", "Zambia": "Замбия", "Zimbabwe": "Зимбабве",
    "Mozambique": "Мозамбик", "Ghana": "Гана", "Libya": "Либия", "Sri Lanka": "Шри Ланка",
    "Nepal": "Непал", "Qatar": "Катар", "Kuwait": "Кувейт", "Bahrain": "Бахрейн", "Oman": "Оман",
    "Lebanon": "Ливан", "Syria": "Сирия", "Afghanistan": "Афганистан", "Brunei": "Бруней",
    "North Korea": "Северна Корея", "Turkmenistan": "Туркменистан", "Macau": "Макао",
}

# Canonical makes recognised in the manufacturer text (first match wins), and their diagnostic group.
MAKES = [
    ("Volkswagen", "Volkswagen", "VAG"), ("VW ", "Volkswagen", "VAG"), ("Audi", "Audi", "VAG"),
    ("Škoda", "Škoda", "VAG"), ("Skoda", "Škoda", "VAG"), ("Cupra", "Cupra", "VAG"), ("SEAT", "SEAT", "VAG"),
    ("Seat", "SEAT", "VAG"), ("Porsche", "Porsche", None), ("Lamborghini", "Lamborghini", None),
    ("Bentley", "Bentley", None), ("Bugatti", "Bugatti", None),
    ("Mercedes", "Mercedes-Benz", None), ("Smart", "Smart", None), ("Maybach", "Maybach", None),
    ("BMW", "BMW", None), ("Mini", "MINI", None), ("MINI", "MINI", None), ("Rolls-Royce", "Rolls-Royce", None),
    ("Opel", "Opel", None), ("Vauxhall", "Vauxhall", None),
    ("Ford", "Ford", "FORD"), ("Lincoln", "Lincoln", "FORD"), ("Mercury", "Mercury", "FORD"),
    ("Mazda", "Mazda", "FORD"),
    ("Lexus", "Lexus", "TOYOTA"), ("Toyota", "Toyota", "TOYOTA"), ("Daihatsu", "Daihatsu", None),
    ("Hyundai", "Hyundai", "HYUNDAI_KIA"), ("Kia", "Kia", "HYUNDAI_KIA"), ("Genesis", "Genesis", "HYUNDAI_KIA"),
    ("Dacia", "Dacia", "RENAULT_NISSAN"), ("Renault", "Renault", "RENAULT_NISSAN"),
    ("Infiniti", "Infiniti", "RENAULT_NISSAN"), ("Nissan", "Nissan", "RENAULT_NISSAN"),
    ("Peugeot", "Peugeot", None), ("Citroën", "Citroën", None), ("Citroen", "Citroën", None),
    ("DS Automobiles", "DS", None), ("Alfa Romeo", "Alfa Romeo", None), ("Lancia", "Lancia", None),
    ("Abarth", "Abarth", None), ("Fiat", "Fiat", None), ("Jeep", "Jeep", None), ("Chrysler", "Chrysler", None),
    ("Dodge", "Dodge", None), ("Ram ", "Ram", None), ("Maserati", "Maserati", None), ("Ferrari", "Ferrari", None),
    ("Honda", "Honda", None), ("Acura", "Acura", None), ("Mitsubishi", "Mitsubishi", None),
    ("Subaru", "Subaru", None), ("Suzuki", "Suzuki", None), ("Isuzu", "Isuzu", None),
    ("Volvo", "Volvo", None), ("Polestar", "Polestar", None), ("Saab", "Saab", None),
    ("Land Rover", "Land Rover", None), ("Range Rover", "Land Rover", None), ("Jaguar", "Jaguar", None),
    ("Aston Martin", "Aston Martin", None), ("Lotus", "Lotus", None), ("MG ", "MG", None), ("Rover", "Rover", None),
    ("Tesla", "Tesla", None), ("Chevrolet", "Chevrolet", None), ("Cadillac", "Cadillac", None),
    ("Buick", "Buick", None), ("GMC", "GMC", None), ("Pontiac", "Pontiac", None), ("Daewoo", "Daewoo", None),
    ("SsangYong", "SsangYong", None), ("KGM", "SsangYong", None), ("Lada", "Lada", None),
    ("AvtoVAZ", "Lada", None), ("UAZ", "UAZ", None), ("GAZ", "GAZ", None), ("Chery", "Chery", None),
    ("BYD", "BYD", None), ("Geely", "Geely", None), ("Great Wall", "Great Wall", None), ("Haval", "Haval", None),
    ("Iveco", "Iveco", None), ("MAN ", "MAN", None), ("Scania", "Scania", None), ("DAF", "DAF", None),
    ("Proton", "Proton", None), ("Tata", "Tata", None), ("Mahindra", "Mahindra", None),
]

def clean(cell: str) -> str:
    s = re.sub(r"<ref[^>]*/>", "", cell)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"<[^>]+>", " ", s)
    s = s.replace("'''", "").replace("''", "")
    return " ".join(s.split()).strip(" ,;")

def strip_attr(cell: str) -> (str, int):
    """'rowspan=19| South Africa' -> ('South Africa', 19)."""
    m = re.match(r'\s*((?:rowspan|colspan|style|class|align)\s*=\s*"?[^|"]*"?\s*)+\|(.*)$', cell)
    if m:
        span = re.search(r"rowspan\s*=\s*\"?(\d+)", m.group(0))
        return m.group(2), int(span.group(1)) if span else 1
    return cell, 1

def parse_wmis(text: str):
    start = text.index("==List of common WMI==")
    end = text.index("==See also==")
    body = text[start:end]
    entries = {}
    country, left = None, 0
    for row in body.split("\n|-"):
        lines = [l for l in row.split("\n") if l.startswith("|") and not l.startswith("|}") and not l.startswith("|-")]
        cells = []
        for l in lines:
            cells += [c for c in l[1:].split("||")]
        cells = [c for c in cells if c.strip() != ""]
        if len(cells) < 2:
            continue
        codes_cell = clean(cells[0])
        if left <= 0:
            country = None
        if len(cells) >= 3:
            ctext, span = strip_attr(cells[1])
            country, left = clean(ctext), span
            maker = cells[2]
        else:
            maker = cells[1]
            if strip_attr(maker)[1] > 1:
                maker = strip_attr(maker)[0]
        left -= 1
        maker = clean(strip_attr(maker)[0])
        for code in codes_cell.split():
            if not re.fullmatch(r"[A-HJ-NPR-Z0-9]{3}", code):
                continue  # 6-character small-maker WMIs and notes are skipped
            # The make named first in the text wins ("Nissan car & Infiniti car" -> Nissan).
            make, group, best = None, None, None
            for key, canon, grp in MAKES:
                m = re.search(r"(?<![A-Za-z])" + re.escape(key.strip()) + r"(?![a-z])", maker)
                if m and (best is None or m.start() < best):
                    make, group, best = canon, grp, m.start()
            item = {"maker": maker, "country": COUNTRY_BG.get(country or "", country)}
            if make:
                item["make"] = make
            if group:
                item["group"] = group
            entries.setdefault(code, []).append(item)
    return entries

def parse_countries(text: str):
    part = text[text.index("====Country or region codes===="):text.index("===Vehicle descriptor section===")]
    out = []
    for m in re.finditer(r"'''([A-Z0-9]{1,2})(?:-([A-Z0-9]{1,2}))?'''\s*([^<\n|]*)", part):
        a, b, name = m.group(1), m.group(2) or m.group(1), clean(m.group(3))
        if not name:
            continue
        out.append({"from": a, "to": b, "country": COUNTRY_BG.get(name, name)})
    return out

def main(src, dst):
    text = open(src, encoding="utf-8").read()
    wmis = parse_wmis(text)
    compact = {}
    for code, items in sorted(wmis.items()):
        # Prefer the entry that names a known make; keep the others as alternatives in the text.
        items.sort(key=lambda i: 0 if "make" in i else 1)
        first = dict(items[0])
        others = [i["maker"] for i in items[1:] if i["maker"] != first["maker"]]
        if others:
            first["maker"] = first["maker"] + " / " + " / ".join(others[:2])
        compact[code] = first
    pack = {
        "id": "wmi",
        "version": 1,
        "schema": 1,
        "title": "Производители по WMI (позиции 1–3 на VIN)",
        "sources": [{
            "name": "Wikipedia: Vehicle identification number (List of common WMI, Country or region codes)",
            "url": "https://en.wikipedia.org/wiki/Vehicle_identification_number",
            "license": "CC BY-SA 4.0",
            "retrieved": str(date.today()),
        }],
        "data": {"order": ORDER, "countries": parse_countries(text), "wmi": compact},
    }
    with open(dst, "w", encoding="utf-8") as f:
        json.dump(pack, f, ensure_ascii=False, separators=(",", ":"), sort_keys=False)
    print(f"{len(compact)} WMI, {len(pack['data']['countries'])} country ranges -> {dst}")

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
