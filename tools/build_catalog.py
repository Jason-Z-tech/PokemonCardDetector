#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/catalog.json aus der TCGdex-API (https://tcgdex.dev).

Der Katalog enthaelt alle Sets (deutsche Namen, englische als Fallback) und alle
Karten pro Set. Die App liefert diesen Katalog mit aus, damit Scannen und die
Sammlung sofort offline funktionieren. In der App kann er ueber
"Datenbank aktualisieren" jederzeit online aufgefrischt werden.

Kartenzeile: [localId, nameDe, nameEn, flags, [Varianten-Indizes], KP, Regelzeichen]

Aufruf:  python3 tools/build_catalog.py
"""
import concurrent.futures as cf
import json
import os
import re
import sys
import time
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


def head_ok(url):
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "PokemonCardDetector-catalog-builder"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            time.sleep(1 + attempt)
        except Exception:  # noqa: BLE001
            time.sleep(1 + attempt)
    return False


GRAPHQL = "https://api.tcgdex.net/v2/graphql"
VARIANT_QUERY = '{ cards(filters: {id: "%s-"}, pagination: {page: %d, count: 100}) { id hp regulationMark variants_detailed { type subtype foil stamp size } } }'


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
        parts.append("stamp=" + ",".join(v["stamp"]))
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
            out[lid] = (c.get("hp") or 0, c.get("regulationMark") or "", keys)
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
            hp, reg, keys = set_variants.get(lid, (0, "", []))
            idx = [variant_index.setdefault(k, len(variant_index)) for k in keys]
            card_rows.append([lid, c["de"] or c["en"] or "", c["en"] or c["de"] or "", flags, idx, hp, reg])
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


if __name__ == "__main__":
    main()
