#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/catalog.json aus der TCGdex-API (https://tcgdex.dev).

Der Katalog enthaelt alle Sets (deutsche Namen, englische als Fallback) und alle
Karten pro Set. Die App liefert diesen Katalog mit aus, damit Scannen und die
Sammlung sofort offline funktionieren. In der App kann er ueber
"Datenbank aktualisieren" jederzeit online aufgefrischt werden.

Kartenzeile: [localId, nameDe, nameEn, flags, [Varianten-Indizes], KP, Regelzeichen]

Aufruf:  python3 tools/build_catalog.py
"""
import collections
import concurrent.futures as cf
import json
import os
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.tcgdex.net/v2"
EXCLUDED_SERIES = {"tcgp"}  # Pokemon TCG Pocket = nur digital, keine echten Karten
ASSETS_DIR = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets")
OUT = os.path.join(ASSETS_DIR, "catalog.json")
VERSION_OUT = os.path.join(ASSETS_DIR, "catalog_version.txt")
ASSETS = "https://assets.tcgdex.net"

# Unter-Sets (Trainer-Galerie, Galar-Galerie, Glitzer-Tresor) liegen bei den Bildern im Ordner des Haupt-Sets:
# swsh9tg -> swsh9, swsh12.5gg -> swsh12.5, swsh4.5sv -> swsh4.5
SUBSET_RE = re.compile(r"^(swsh\d+(?:\.5)?)(tg|gg|sv)$")


def image_set_id(set_id):
    m = SUBSET_RE.match(set_id)
    return m.group(1) if m else set_id


def natural_key(local_id):
    """Sortierung wie auf den Karten: SV2 vor SV10, 23 vor 100, Buchstaben-Praefix zuerst."""
    m = re.match(r"^([A-Za-z]*)(\d+)(.*)$", local_id)
    if not m:
        return ("~", 10**9, local_id)
    return (m.group(1).upper(), int(m.group(2)), m.group(3))


def head_ok(url, timeout=30):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "PokemonCardDetector-catalog-builder"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            time.sleep(1 + attempt)
        except Exception:  # noqa: BLE001
            time.sleep(1 + attempt)
    return None  # unbekannt (Server-Fehler), nicht "kein Bild"


GRAPHQL = "https://api.tcgdex.net/v2/graphql"
VARIANT_QUERY = '{ cards(filters: {id: "%s-"}, pagination: {page: %d, count: 100}) { id hp regulationMark dexId rarity variants_detailed { type subtype foil stamp size } } }'


def graphql(query, retries=5):
    body = json.dumps({"query": query}).encode()
    for attempt in range(retries):
        try:
            req = urllib.request.Request(GRAPHQL, data=body, headers={"Content-Type": "application/json", "User-Agent": "PokemonCardDetector-catalog-builder"})
            with urllib.request.urlopen(req, timeout=120) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                print(f"FEHLER GraphQL: {e}", file=sys.stderr)
                return {}
            time.sleep(1.5 * (attempt + 1))


def variant_key(v):
    """Stabiler Schluessel einer Variante, z.B. 'reverse|foil=pokeball' oder 'holo|sub=shadowless|stamp=1st-edition'."""
    parts = [v.get("type") or "normal"]
    if v.get("subtype"):
        parts.append("sub=" + v["subtype"])
    if v.get("foil"):
        parts.append("foil=" + v["foil"])
    if v.get("stamp"):
        parts.append("stamp=" + ",".join(st.strip().lower().replace(" ", "-") for st in v["stamp"]))
    if v.get("size") and v["size"] != "standard":
        parts.append("size=" + v["size"])
    return "|".join(parts)


def fetch_variants(set_id):
    """localId -> (hp, regulationMark, [variant keys]) fuer ein Set (GraphQL, je 100 Karten)."""
    out = {}
    page = 1
    while True:
        r = graphql(VARIANT_QUERY % (set_id, page))
        cards = (r.get("data") or {}).get("cards") or []
        for c in cards:
            if not c["id"].startswith(set_id + "-"):
                continue
            lid = c["id"][len(set_id) + 1:]
            keys = []
            for v in c.get("variants_detailed") or []:
                k = variant_key(v)
                if k not in keys:
                    keys.append(k)
            out[lid] = (c.get("hp") or 0, c.get("regulationMark") or "", keys, c.get("dexId") or [], c.get("rarity") or "")
        if len(cards) < 100:
            break
        page += 1
    return set_id, out


def get(path, retries=4):
    url = f"{API}/{path}"
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "PokemonCardDetector-catalog-builder"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:  # noqa: BLE001
            if attempt == retries - 1:
                print(f"FEHLER {url}: {e}", file=sys.stderr)
                return None
            time.sleep(1.5 * (attempt + 1))


def main():
    sets_de = {s["id"]: s for s in get("de/sets")}
    sets_en = {s["id"]: s for s in get("en/sets")}
    series_de = {s["id"]: s["name"] for s in get("de/series")}
    series_en = {s["id"]: s["name"] for s in get("en/series")}

    jobs = [("de", sid) for sid in sets_de] + [("en", sid) for sid in sets_en]
    details = {}
    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        futs = {ex.submit(get, f"{lang}/sets/{sid}"): (lang, sid) for lang, sid in jobs}
        for f in cf.as_completed(futs):
            details[futs[f]] = f.result()

    with cf.ThreadPoolExecutor(max_workers=10) as ex:
        variants = dict(ex.map(fetch_variants, list(dict.fromkeys(list(sets_en) + list(sets_de)))))
    variant_index = {}
    species_names = {}  # Pokedex-Nr. -> deutsche Kartennamen (fuer japanische Karten)

    out_sets = []
    used_series = {}
    for sid in list(dict.fromkeys(list(sets_en) + list(sets_de))):
        de = details.get(("de", sid))
        en = details.get(("en", sid))
        base = de or en
        if not base:
            continue
        serie = (base.get("serie") or {}).get("id", "misc")
        if serie in EXCLUDED_SERIES:
            continue
        used_series[serie] = True
        abbr = (base.get("abbreviation") or {}).get("official") or base.get("tcgOnline")
        if not abbr and en:
            abbr = (en.get("abbreviation") or {}).get("official") or en.get("tcgOnline")
        cc = (base.get("cardCount") or {})
        # Kartenliste: Reihenfolge des Sets, deutsche Karten + englische Zusatzkarten
        cards = {}
        order = []
        for lang, d in (("de", de), ("en", en)):
            if not d:
                continue
            for c in d.get("cards", []):
                lid = c["localId"]
                if lid not in cards:
                    cards[lid] = {"de": None, "en": None, "imgDe": False, "imgEn": False}
                    order.append(lid)
                cards[lid][lang] = c.get("name")
                if "image" in c:
                    cards[lid]["imgDe" if lang == "de" else "imgEn"] = True
        img_set = image_set_id(sid)
        if img_set != sid:
            # Fuer Unter-Sets liefert die API keine Bild-Felder -> Bilder im Haupt-Set-Ordner pruefen
            def probe(lid):
                de_ok = head_ok(f"{ASSETS}/de/{serie}/{img_set}/{lid}/low.webp")
                en_ok = head_ok(f"{ASSETS}/en/{serie}/{img_set}/{lid}/low.webp")
                return lid, de_ok, en_ok
            with cf.ThreadPoolExecutor(max_workers=12) as ex:
                for lid, de_ok, en_ok in ex.map(probe, order):
                    cards[lid]["imgDe"] = cards[lid]["imgDe"] or de_ok
                    cards[lid]["imgEn"] = cards[lid]["imgEn"] or en_ok
        order.sort(key=natural_key)
        card_rows = []
        set_variants = variants.get(sid, {})
        for lid in order:
            c = cards[lid]
            flags = (1 if c["imgDe"] else 0) | (2 if c["imgEn"] else 0) | (4 if c["de"] else 0)
            hp, reg, keys, dex, rarity = set_variants.get(lid, (0, "", [], [], ""))
            idx = [variant_index.setdefault(k, len(variant_index)) for k in keys]
            card_rows.append([lid, c["de"] or c["en"] or "", c["en"] or c["de"] or "", flags, idx, hp, reg, rarity])
            if c["de"] and len(dex) == 1:
                species_names.setdefault(int(dex[0]), []).append(c["de"])
        logo = (de or {}).get("logo") or (en or {}).get("logo")
        symbol = (de or {}).get("symbol") or (en or {}).get("symbol")
        out_sets.append({
            "id": sid,
            "name": (de or {}).get("name") or (en or {}).get("name"),
            "nameEn": (en or {}).get("name") or (de or {}).get("name"),
            "serie": serie,
            "release": base.get("releaseDate") or (en or {}).get("releaseDate") or "",
            "abbr": abbr or "",
            "official": cc.get("official") or 0,
            "total": cc.get("total") or len(card_rows),
            "de": bool(de),
            "logo": logo or "",
            "symbol": symbol or "",
            "img": img_set,
            "cards": card_rows,
        })

    out_series = [
        {"id": s, "name": series_de.get(s) or series_en.get(s) or s, "nameEn": series_en.get(s) or s}
        for s in used_series
    ]
    catalog = {
        # Datum + Uhrzeit: neuere Kataloge vom selben Tag werden in der App trotzdem neu importiert
        "version": time.strftime("%Y-%m-%d %H:%M"),
        "series": out_series,
        # Varianten-Woerterbuch; Karten verweisen per Index darauf
        "variants": [k for k, _ in sorted(variant_index.items(), key=lambda kv: kv[1])],
        "sets": out_sets,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
    with open(VERSION_OUT, "w", encoding="utf-8") as f:
        f.write(catalog["version"])
    n_cards = sum(len(s["cards"]) for s in out_sets)
    print(f"{len(out_sets)} Sets, {n_cards} Karten, {os.path.getsize(OUT) // 1024} KB -> {os.path.normpath(OUT)}")

    if "--ohne-japanisch" not in sys.argv:
        build_japanese(german_species(species_names), catalog["version"])


DE_SUFFIX = re.compile(r"[ -](ex|EX|GX|V|VMAX|VSTAR|V-UNION|BREAK|LEGENDE|LV\.X)$")


def german_species(species_names):
    """Pokedex-Nr. -> deutscher Pokemon-Name (haeufigster einfacher Kartenname, z.B. 6 -> 'Glurak').

    Zusaetze (ex, V, VMAX ...) werden vorher entfernt, sonst gewinnt bei Arten ohne einfachen Namen
    ein Name mit Zusatz ('Hisui-Snieboss V'). Gibt es nur Formen ('Ofenmaske-Ogerpon',
    'Brunnenmaske-Ogerpon' ...) und keine ueberwiegt, zaehlt der gemeinsame Kern ('Ogerpon').
    """
    out = {}
    for dex, names in species_names.items():
        stripped = [DE_SUFFIX.sub("", n) for n in names]
        simple = [n for n in stripped if re.fullmatch(r"[\wÄÖÜäöüß.'’]+", n)]
        pool = simple or stripped
        counts = collections.Counter(pool)
        best = sorted(counts.items(), key=lambda kv: (-kv[1], len(kv[0])))[0][0]
        if not simple and len(counts) > 1 and counts[best] < 0.6 * len(pool) and all("-" in n for n in counts):
            cores = {n.rsplit("-", 1)[1] for n in counts}
            if len(cores) == 1 and len(next(iter(cores))) >= 3:
                best = cores.pop()
        out[dex] = best
    return out


# Japanischer Zusatz am Namensende -> deutsche Schreibweise (V-UNION vor V pruefen!)
JA_SUFFIXES = [("VMAX", " VMAX"), ("VSTAR", " VSTAR"), ("V-UNION", " V-UNION"), ("VUNION", " V-UNION"), ("GX", "-GX"),
               ("EX", "-EX"), ("ex", " ex"), ("V", " V"), ("BREAK", " BREAK")]
# Regionalformen und Formen mit eigenem deutschen Namensteil
JA_REGIONS = [("アローラ", "Alola-"), ("ガラル", "Galar-"), ("ヒスイ", "Hisui-"), ("パルデア", "Paldea-")]
JA_FORMS = [("みどりのめん", "Türkisgrüne-Maske-"), ("いどのめん", "Brunnenmaske-"), ("かまどのめん", "Ofenmaske-"),
            ("いしずえのめん", "Fundamentmaske-"), ("いちげき", "Fokussierter-Angriff-"), ("れんげき", "Fließender-Angriff-"),
            ("はくば", "Schimmelreiter-"), ("こくば", "Rappenreiter-")]


def split_ja_suffix(name_ja):
    """'リザードンex' -> ('リザードン', ' ex'), 'モルペコV-UNION' -> ('モルペコ', ' V-UNION')."""
    for jp, de in JA_SUFFIXES:
        if name_ja.endswith(jp) and len(name_ja) > len(jp):
            return name_ja[:-len(jp)].rstrip(), de
    return name_ja, ""


def dex_list(dex_ids):
    """TCGdex liefert Formen teils als Kommazahl (384.1 = Rayquaza Delta-Spezies)."""
    return [int(d) for d in dex_ids or [] if isinstance(d, (int, float))]


def german_part(part_ja, dex, species, ja_species):
    """Ein Pokemon im japanischen Namen -> deutscher Name mit Mega-/Regional-/Form-Praefix."""
    de = species[dex]
    part_ja = part_ja.strip()
    base = ja_species.get(dex, "")
    # Nur echte Mega-Entwicklungen: メガメガニウム ja, メガニウム / メガヤンマ (Artname) nein
    if base and part_ja.startswith("メガ") and part_ja[2:].startswith(base):
        rest = part_ja[2 + len(base):].strip()
        return "Mega-" + de + (" " + rest[0] if rest[:1] in ("X", "Y") else "")
    if base and part_ja.startswith("M") and part_ja[1:].startswith(base):
        return "M-" + de
    for jp, prefix in JA_FORMS:
        if jp in part_ja:  # 'みどりのめん オーガポン' und 'オーガポン かまどのめん'
            return prefix + de.rsplit("-", 1)[-1]
    for jp, prefix in JA_REGIONS:
        if part_ja.startswith(jp) and not de.startswith(prefix):
            return prefix + de
    return de


def german_hint(name_ja, dex_ids, species, ja_species=None):
    """Deutscher Hinweis fuer eine japanische Karte, z.B. 'リザードンex' + [6] -> 'Glurak ex'."""
    ja_species = ja_species or {}
    dex_ids = [d for d in dex_list(dex_ids) if d in species]
    if not dex_ids:
        return ""
    # Delta-Spezies: 'カイリューex（デルタ種）' -> 'Dragoran ex δ' (deutsche Karten schreiben 'δ')
    delta = OLD_DELTA.search(name_ja)
    if delta:
        name_ja = name_ja[:delta.start()]
    base, suffix = split_ja_suffix(name_ja)
    parts = re.split(r"\s*[&＆]\s*", base)
    if len(parts) != len(dex_ids):
        parts = [base] + [""] * (len(dex_ids) - 1)
    hint = " & ".join(german_part(p, d, species, ja_species) for p, d in zip(parts, dex_ids)) + suffix
    return hint + (" δ" if delta else "")


# Artnamen: Katakana (inkl. ー ・), ♀♂ und die Zeichen in タイプ：ヌル, ポリゴン2, ポリゴンZ
JA_SPECIES_NAME = re.compile(r"[゠-ヿ♀♂:：・2２ZＺ]+")
JA_REGION_PREFIX = re.compile(r"^(アローラ|ガラル|ヒスイ|パルデア)\s*")
JA_MODERN_SERIES = {"SM", "S", "SV", "M"}
# Bis 2007: bei TCGdex viele maschinell uebersetzte Namen ('おしっこ' = Smogmog, '同上' = Ditto)
JA_OLD_SERIES = {"PMCG", "neo", "VS", "web", "e", "ADV", "PCG"}


def japanese_species(card_details, serie_of):
    """Pokedex-Nr. -> japanischer Artname (haeufigster reiner Katakana-Name, z.B. 110 -> 'マタドガス').

    Quelle sind zuerst die modernen Serien (SM/S/SV/M), deren Namen stimmen. Arten, die dort nur mit
    Zusatz vorkommen ('いちげきウーラオスV'), bekommen den Katakana-Kern; die alten Serien zaehlen nie.
    """
    tiers = [collections.defaultdict(collections.Counter) for _ in range(4)]
    for cid, cd in card_details.items():
        if not cd:
            continue
        dex = dex_list(cd.get("dexId"))
        serie = serie_of.get(cid.rsplit("-", 1)[0])
        if len(dex) != 1 or serie in JA_OLD_SERIES:
            continue
        modern = serie in JA_MODERN_SERIES
        name = JA_REGION_PREFIX.sub("", (cd.get("name") or "").strip())
        if JA_SPECIES_NAME.fullmatch(name):
            tiers[0 if modern else 2][dex[0]][name] += 1
            continue
        core = re.sub(r"^[぀-ゟ]+", "", split_ja_suffix(name)[0].split(" ")[-1])
        if len(core) >= 2 and JA_SPECIES_NAME.fullmatch(core):
            tiers[1 if modern else 3][dex[0]][core] += 1
    out = {}
    for tier in tiers:
        for dex, counts in tier.items():
            if dex not in out:
                out[dex] = sorted(counts.items(), key=lambda kv: (-kv[1], len(kv[0])))[0][0]
    return out


def nfkc(s):
    return unicodedata.normalize("NFKC", s or "")


OLD_ASCII_WORD = re.compile(r"(?:[A-Za-z][^A-Za-z]*){3}")      # 'Koffing', 'MUK', 'quagsire'
OLD_TRAINER_WORD = re.compile(r"[A-Za-z]*[a-z][A-Za-z]*")         # 'Max Revive', 'exp。共有' (nicht 'ESP')
OLD_NUMBER_FRAGMENT = re.compile(r"-?\s*\d{3}/\d{3}")             # 'MUK -004/092'
OLD_DELTA = re.compile(r"\s*[（(]デルタ種[)）]\s*$")
OLD_EX = re.compile(r"(?<![a-z])(?:ex|Ex|EX)\s*$")
OLD_STAR = re.compile(r"(\s[Ss]tar|の星|[★☆])$")
OLD_PREFIXES = [
    (re.compile(r"^(暗い|ダーク|dark\s*|わるい)", re.I), "わるい"),
    (re.compile(r"^(軽い|ライト|light\s*|やさしい)", re.I), "やさしい"),
    (re.compile(r"^(輝く|shining\s*|ひかる)", re.I), "ひかる"),
    (re.compile(r"^(ロケット団の|ロケットの|rocket's\s*|rocketの)", re.I), "ロケット団の"),
]


def repair_old_name(name, dex_ids, ja_species):
    """Maschinell uebersetzte Namen alter Sets durch den echten Artnamen ersetzen.

    'おしっこ' [110] -> 'マタドガス', 'Crobat Ex' -> 'クロバットex', 'Snorlax（デルタ種）' -> 'カビゴン（デルタ種）',
    'ダークブラストワイズ' [9] -> 'わるいカメックス'. Ohne Pokedex-Nr. oder Artnamen bleibt der Name leer.
    Gibt (name, geaendert) zurueck.
    """
    norm = nfkc(name)
    dex = dex_list(dex_ids)
    if not dex:
        # Trainer: nur offensichtlicher Unsinn ('Max Revive', 'Moomooミルク', 'exp。共有') wird geleert
        garbage = any(len(w) >= 3 for w in OLD_TRAINER_WORD.findall(norm)) or OLD_NUMBER_FRAGMENT.search(norm)
        return ("", True) if garbage else (name, False)
    species = [ja_species.get(d) for d in dex]
    known = all(species)
    garbage = (OLD_ASCII_WORD.search(norm) or OLD_NUMBER_FRAGMENT.search(norm)
               or (known and not all(nfkc(s) in norm for s in species)))
    if not garbage:
        return name, False
    if not known:
        return "", True
    rest = name
    delta = OLD_DELTA.search(rest)
    tail = ""
    if delta:
        tail = delta.group(0).strip()
        rest = rest[:delta.start()]
    rest = OLD_NUMBER_FRAGMENT.sub("", rest).strip()
    ex = "ex" if OLD_EX.search(rest) else ""
    # Goldstern: 'Kyogre Star', 'セレビの星', 'ギャラドススター' (aber オムスター ist der Artname)
    star = "★" if (OLD_STAR.search(rest) or (rest.endswith("スター") and not species[-1].endswith("スター"))) else ""
    prefix = next((jp for rx, jp in OLD_PREFIXES if rx.match(nfkc(rest))), "")
    return prefix + "&".join(species) + ex + star + tail, True


def ja_image_url(serie, sid, local_id):
    q = urllib.parse.quote
    return f"{ASSETS}/ja/{q(serie)}/{q(sid)}/{q(local_id)}/low.webp"


def probe_images(urls, workers=24):
    """HEAD-Anfragen parallel (kurzer Timeout), url -> True wenn das Bild existiert."""
    urls = list(dict.fromkeys(urls))
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        res = dict(zip(urls, ex.map(lambda u: head_ok(u, timeout=10), urls)))
    failed = [u for u, ok in res.items() if ok is None]
    if failed:
        # Server-Fehler (503) kommen schubweise: nach einer Pause langsamer noch einmal
        time.sleep(5)
        with cf.ThreadPoolExecutor(max_workers=4) as ex:
            res.update(zip(failed, ex.map(lambda u: head_ok(u, timeout=20), failed)))
    return {u: bool(ok) for u, ok in res.items()}


# Bei TCGdex unter "ja" einsortiert, aber vereinfachtes Chinesisch: alle heissen 'トリプレットビート',
# 101 Karten, keine Bilder -> keine japanischen Sets
JA_NOT_JAPANESE = re.compile(r"^CS")


def placeholder_numbers(metas):
    """Sets ohne Kartenliste: Nummern ueber die Bilder ermitteln.

    metas: [(sid, serie, official)]. Ergebnis sid -> (Treffer-Nummern, hoechste offizielle Nummer).
    Erst Stichprobe (001-008, Mitte, letzte); bei Bildern alle Nummern bis offiziell + 8, danach in
    8er-Schritten weiter, bis 8 Nummern hintereinander fehlen (Geheimkarten ueber der Gesamtzahl, z.B. 308/190).
    """
    def url(m, n):
        return ja_image_url(m[1], m[0], f"{n:03d}")

    sample = {m[0]: sorted(set(range(1, 9)) | {max(1, m[2] // 2), max(1, m[2])}) for m in metas}
    res = probe_images([url(m, n) for m in metas for n in sample[m[0]]])
    hits = {m[0]: {n for n in sample[m[0]] if res.get(url(m, n))} for m in metas}
    active = [m for m in metas if hits[m[0]]]
    upto = {m[0]: max(m[2], 1) + 8 for m in active}
    todo = [(m, n) for m in active for n in range(1, upto[m[0]] + 1) if n not in sample[m[0]]]
    while todo:
        res = probe_images([url(m, n) for m, n in todo])
        for m, n in todo:
            if res.get(url(m, n)):
                hits[m[0]].add(n)
        todo = []
        for m in active:
            last = upto[m[0]]
            if any(n in hits[m[0]] for n in range(last - 7, last + 1)):
                upto[m[0]] = last + 8
                todo += [(m, n) for n in range(last + 1, last + 9)]
    return hits


def build_japanese(species, version):
    """Japanische Sets/Karten -> assets/catalog_ja.json (IDs mit Praefix 'ja:', Bilder unter /ja/)."""
    q = urllib.parse.quote
    sets = get("ja/sets") or []
    series = {s["id"]: s["name"] for s in (get("ja/series") or [])}
    set_ids = [s["id"] for s in sets if not JA_NOT_JAPANESE.match(s["id"])]
    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        # IDs wie 'SM1+' muessen kodiert werden, sonst wird '+' zum Leerzeichen
        details = dict(zip(set_ids, ex.map(lambda sid: get(f"ja/sets/{q(sid, safe='')}"), set_ids)))
    card_ids = [c["id"] for d in details.values() if d for c in d.get("cards", [])]
    with cf.ThreadPoolExecutor(max_workers=16) as ex:
        card_details = dict(zip(card_ids, ex.map(lambda cid: get(f"ja/cards/{q(cid, safe='')}", retries=3), card_ids)))
    failed = [cid for cid, cd in card_details.items() if cd is None]
    if failed:
        # Server-Fehler (503) kommen schubweise: nach einer Pause langsamer noch einmal
        print(f"{len(failed)} japanische Karten erneut laden ...", file=sys.stderr)
        time.sleep(10)
        with cf.ThreadPoolExecutor(max_workers=4) as ex:
            card_details.update(zip(failed, ex.map(lambda cid: get(f"ja/cards/{q(cid, safe='')}", retries=6), failed)))
    serie_of = {sid: (d.get("serie") or {}).get("id", "misc") for sid, d in details.items() if d}

    # Deutsche Artnamen fuer die Online-Aktualisierung neuer japanischer Sets (Hinweise ohne Rechenweg)
    with open(os.path.join(ASSETS_DIR, "species_de.json"), "w", encoding="utf-8") as f:
        json.dump({str(d): species[d] for d in sorted(species)}, f, ensure_ascii=False, separators=(",", ":"))

    ja_species = japanese_species(card_details, serie_of)

    # Bilder: die API laesst das Feld "image" fuer ganze Sets weg, obwohl der Bildserver sie hat
    unknown = [ja_image_url(serie_of[sid], sid, c["localId"]) for sid, d in details.items() if d
               for c in d.get("cards", []) if "image" not in c]
    probed = probe_images(unknown)

    # Erster Durchlauf: Namen (alte Sets repariert) und Pokedex-Nummern je Karte
    entries = {}
    repaired = 0
    for sid, d in details.items():
        if not d:
            continue
        for c in d.get("cards", []):
            cd = card_details.get(c["id"]) or {}
            name = c.get("name") or ""
            if serie_of[sid] in JA_OLD_SERIES:
                name, changed = repair_old_name(name, cd.get("dexId"), ja_species)
                repaired += changed
            entries[c["id"]] = (name, dex_list(cd.get("dexId")))
    # Karten ohne Pokedex-Nr. (z.B. SV4a 'リザードンex', S9 'リザードンV'): ueber den Namen ohne Zusatz zuordnen
    by_base = collections.defaultdict(collections.Counter)
    for name, dex in entries.values():
        base = split_ja_suffix(name)[0]
        if dex and base:
            by_base[base][tuple(dex)] += 1
    by_base.update({n: collections.Counter({(d,): 1}) for d, n in ja_species.items() if n not in by_base})

    def guess_dex(name):
        """'リザードンex' -> [6]; sonst ohne Region ('ヒスイ ウインディ'), Form-Kern, Mega-Praefix ('メガリザードンX')."""
        base = split_ja_suffix(name)[0]
        tries = [base, JA_REGION_PREFIX.sub("", base), re.sub(r"^[぀-ゟ]+", "", base.split(" ")[-1])]
        if base.startswith("メガ"):
            tries.append(re.sub(r"[XY]$", "", base[2:]).strip())
        for t in tries:
            if t and t in by_base:
                return list(by_base[t].most_common(1)[0][0])
        return []

    variant_index = {}
    out_sets = []
    used_series = {}
    n_placeholder_sets = 0
    placeholders = [(sid, serie_of[sid], (d.get("cardCount") or {}).get("official") or 0)
                    for sid, d in details.items() if d and not d.get("cards") and d.get("serie")]
    placeholder_hits = placeholder_numbers(placeholders)
    for sid, d in details.items():
        if not d or not d.get("serie"):
            continue
        serie = serie_of[sid]
        cc = d.get("cardCount") or {}
        official = cc.get("official") or 0
        rows = []
        if d.get("cards"):
            for c in sorted(d["cards"], key=lambda c: natural_key(c["localId"])):
                cd = card_details.get(c["id"]) or {}
                keys = []
                for v in cd.get("variants_detailed") or []:
                    k = variant_key({"type": (v.get("type") or "normal").lower(), "subtype": v.get("subtype"),
                                     "foil": v.get("foil"), "stamp": v.get("stamp"), "size": v.get("size")})
                    if k not in keys:
                        keys.append(k)
                idx = [variant_index.setdefault(k, len(variant_index)) for k in keys]
                has_image = "image" in c or probed.get(ja_image_url(serie, sid, c["localId"]), False)
                flags = 16 if has_image else 0
                name, dex = entries[c["id"]]
                if not dex and (cd.get("category") == "Pokemon" or (cd.get("hp") or 0) > 0):
                    dex = guess_dex(name)
                hint = german_hint(name, dex, species, ja_species)
                rows.append([c["localId"], name, hint, flags, idx, cd.get("hp") or 0,
                             cd.get("regulationMark") or "", cd.get("rarity") or ""])
        else:
            # Set ohne Kartenliste: Platzhalter je Nummer (Name leer), Bild wo vorhanden
            # Nummern ohne Bild zwischen den Treffern gibt es trotzdem (Nummerierung ist lueckenlos)
            hits = placeholder_hits.get(sid, set())
            numbers = range(1, max([official] + list(hits)) + 1)
            rows = [[f"{n:03d}", "", "", 16 if n in hits else 0, [], 0, "", ""] for n in numbers]
            n_placeholder_sets += 1
        used_series[serie] = True
        out_sets.append({
            "id": "ja:" + sid,
            "name": d.get("name") or sid,
            "nameEn": d.get("name") or sid,
            "serie": "ja:" + serie,
            "release": d.get("releaseDate") or "",
            "abbr": sid,  # japanische Karten drucken die Set-ID (z.B. 'SV2a') neben die Nummer
            "official": official,
            "total": max(cc.get("total") or 0, len(rows)),
            "de": False,
            "logo": d.get("logo") or "",
            "symbol": d.get("symbol") or "",
            "img": sid,
            "cards": rows,
        })
    out_series = [{"id": "ja:" + s, "name": series.get(s) or s, "nameEn": series.get(s) or s} for s in used_series]
    catalog = {
        "version": version,
        "series": out_series,
        "variants": [k for k, _ in sorted(variant_index.items(), key=lambda kv: kv[1])],
        "sets": out_sets,
    }
    out = os.path.join(ASSETS_DIR, "catalog_ja.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, separators=(",", ":"))
    all_rows = [r for s in out_sets for r in s["cards"]]
    print(f"Japanisch: {len(out_sets)} Sets ({n_placeholder_sets} ohne Kartenliste), {len(all_rows)} Karten, "
          f"{sum(1 for r in all_rows if r[3] & 16)} mit Bild, {sum(1 for r in all_rows if r[2])} mit deutschem Hinweis, "
          f"{repaired} Namen alter Sets repariert, {os.path.getsize(out) // 1024} KB -> {os.path.normpath(out)}")


if __name__ == "__main__":
    main()
