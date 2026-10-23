# Kira v0.8.6 — Shared Macro Core / Mode Router

## Kernänderung

Kira besitzt jetzt einen einzigen persistenten Speicher, aber einen umschaltbaren primären Ausdrucksmodus:

- SCHREIBEN
- VIDEO
- MUSIK
- CODING
- MATHE

Der erste Punkt der oberen Fünferleiste ist kein Tastatur-Schalter mehr. Er öffnet den Core-Mode-Router. Mikrofon und Kamera bleiben physische Input-Schalter, `◈` bleibt die Output-Workbench und `⚙` die Einstellungen.

## Reiz-Routing pro Kernmodus

Jeder Core-Modus speichert separat nur seine Routingmaske für TEXT / AUDIO / KAMERA. Die Wissens- und Makrodatenbank bleibt gemeinsam.

Ein deaktivierter Reiz:

- wird nicht in den Working-State des gewählten Core-Modus gemischt,
- liefert keine IDs an dessen Renderer,
- schreibt in diesem Modus nicht in den betreffenden Lernpfad.

Damit kann z. B. VIDEO nur auf Kamera reagieren, während CODING Text + Audio verwenden darf, ohne getrennte Gedächtnisse zu erzeugen.

## Text ist jetzt am rekursiven Makro-Lernen angeschlossen

Bis v0.8.5.1 lief Chat-Lernen primär über den lokalen MIND-Triple-Layer. Die rekursive `MemoryModel`-Makrohierarchie wurde für Text nicht direkt gefüttert.

v0.8.6 ergänzt:

- stabile Text-Token-IDs in einem eigenen ID-Bereich,
- `MemoryModel.observeText(...)`,
- saubere Message-Grenzen über `beginTextTurn()`,
- rekursive Text-Makros über denselben `feedToken`-Pfad wie Audio/Visual,
- Persistenz der gemeinsamen Memory-Datei beim Verlassen des Vordergrunds.

Der MIND-Triple-Layer bleibt für semantische Relationen erhalten. Bei ausgeschaltetem TEXT-Reiz wird der Chat weiterhin protokolliert, aber die neue Nachricht wird nicht in MIND/Text-Makro-Lernen geschrieben.

## ID-Tiefe / Makro-Slab

Ein neues schwarzes Glass-Slab visualisiert read-only den aktuell tatsächlich verwendeten Working-State:

- aktive IDs,
- Modalität,
- Abstraktionstiefe,
- Tier,
- Verschachtelung zwischen Ebenen.

Die Anzeige folgt dem gerade gewählten Core-Modus und dessen Reizmaske. Sie kann unter `Ausgaben → ID-TIEFE / MAKROS` ein- oder ausgeblendet werden.

## Operative Module

Der Core-Modus schaltet folgende Module primär zu:

- VIDEO → FILM
- MUSIK → ORCHESTRA
- CODING → CODING
- MATHE → MATH
- SCHREIBEN → normaler Kira-/Chat-Pfad

Zusätzliche Module aus der Output-Workbench bleiben weiterhin kombinierbar.

## Datenkontinuität

Application-ID bleibt `de.equinox.v05`. Es gibt weiterhin nur einen Memory-/MIND-Speicher für alle Core-Modi. Normale Updates über die bestehende Installation behalten die Daten; Deinstallation oder Android „Speicher löschen“ entfernt sie.
