# Karten-Scanner – Pokémon-Karten scannen & sammeln (Android)

Android-App zum Einscannen von Pokémon-Sammelkarten. Die App erkennt, aus welchem Set eine Karte ist,
und legt sie in deiner Sammlung ab – sortiert nach Serien und Sets (z. B. *Farbenschock*,
*Karmesin & Purpur*, *Grundset* …), mit Kartenbildern und der Anzahl, wie oft du jede Karte hast.

## Funktionen

- **Scannen mit der Kamera** – Karte in den Rahmen halten, die App erkennt sie automatisch.
  Für schwierige Karten gibt es den Foto-Auslöser oder ein Foto aus der Galerie.
- **Erkennung über die Sammlernummer** (z. B. `025/185`), das Set-Kürzel (ab Karmesin & Purpur, z. B. `PAL DE`),
  den Kartennamen (deutsch **und** englisch) und das Copyright-Jahr. Bei mehreren möglichen Treffern zeigt die App eine Auswahl.
- **Sammlung nach Sets** – alle Sets (206), gruppiert nach Serien, mit Fortschritt (`12/203`), Suchfeld und Filter „Nur meine Sets“.
- **Set-Ansicht** mit allen Karten als Bilder; fehlende Karten grau. Filter *Alle / Habe ich / Fehlen*.
  Tippen → Details mit Anzahl (+/−) und Schalter „In meiner Sammlung“; lange drücken auf eine fehlende Karte
  → direkt als „habe ich“ markieren (mit *Rückgängig*).
- **Bilder an/aus** – Umschalter oben rechts (Textansicht spart mobile Daten).
- **Suche** nach Name oder Nummer, um Karten auch ohne Scan hinzuzufügen.
- **Automatisch hinzufügen** (optional): erkannte Karten landen ohne Tippen in der Sammlung.
- **Sicherung** der Sammlung als JSON-Datei und Wiederherstellen.
- **Offline-fähig**: Der komplette Kartenkatalog ist in der App enthalten, die Texterkennung läuft auf dem Handy
  (Google ML Kit). Nur die Kartenbilder werden aus dem Internet geladen und danach zwischengespeichert.
  Neue Sets: *Einstellungen → Neue Sets herunterladen*.

## Installation

1. `app-release.apk` aufs Handy laden (z. B. aus dem GitHub-Actions-Lauf unter *Artifacts*).
2. Datei öffnen und „Installation aus unbekannten Quellen“ für den Browser/Dateimanager erlauben.
3. App „Karten-Scanner“ öffnen und den Kamerazugriff erlauben.

Voraussetzung: Android 8.0 oder neuer.

**Updates:** Alle APKs dieses Projekts (auch die aus GitHub Actions) sind mit demselben Schlüssel
(`keystore/karten-scanner.jks`) signiert. Eine neue Version lässt sich deshalb einfach über die alte
installieren – deine Sammlung bleibt erhalten. Sichere die Sammlung trotzdem ab und zu
(*Einstellungen → Sammlung sichern*).

> Der Schlüssel liegt bewusst im Repository, damit Builds überall gleich signiert sind. Das ist für eine
> private App in Ordnung. Wer die App öffentlich verteilen will (z. B. im Play Store), sollte einen
> eigenen, geheimen Schlüssel verwenden (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Tipps zum Scannen

- Gutes Licht, Karte möglichst flach und den Rahmen ausfüllend.
- Die **Nummer unten links/rechts** muss scharf sein – sie ist das wichtigste Erkennungsmerkmal.
- Bei Glanz/Holo-Karten das Licht (Blitz-Symbol) ausprobieren oder leicht schräg halten.

## Selbst bauen

```bash
./gradlew :app:testDebugUnitTest   # Tests der Erkennung
./gradlew :app:assembleRelease     # APK unter app/build/outputs/apk/release/
```

Benötigt JDK 17+ und das Android SDK (Platform 35). Den Kartenkatalog aktualisieren:

```bash
python3 tools/build_catalog.py
```

## Technik

Kotlin · Jetpack Compose (Material 3) · CameraX · ML Kit Text Recognition · Room · DataStore · Coil · OkHttp

Kartendaten und -bilder: [TCGdex](https://tcgdex.dev) (freie Pokémon-TCG-Datenbank).

*Pokémon und alle zugehörigen Namen sind Marken von Nintendo, Creatures Inc. und GAME FREAK inc.
Dieses Projekt ist ein privates Fan-Projekt ohne Verbindung zu diesen Unternehmen.*
