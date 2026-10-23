# Kira v0.9.2 — Unified Memory Core

## Ziel
v0.9.2 beseitigt die Fragmentierung zwischen Chat-Recall, MIND/Loom-Triples und dem rekursiven MemoryModel. Lokales Wissen besitzt jetzt einen gemeinsamen persistenten Profil-Store.

## Zentrale Änderungen

- `IndexedLongTermStore` wurde auf Format v2 erweitert.
- Pattern-Candidates unterhalb der Makro-Promotion werden persistent gespeichert.
- Per-Stream-Hierarchiezähler werden gespeichert, damit Lernen nach einem Neustart an derselben Evidenzposition fortgesetzt wird.
- Cross-Modal-Candidates werden persistent gespeichert.
- Lokale MIND-Triples liegen nicht mehr in einer separaten `local_language_triples.json`, sondern im gleichen Indexed-Store wie Quants, Makros und Relationen.
- Persistente Text-Surfaces liegen im gleichen Indexed-Store und dienen als rekonstruierbare Recall-Oberfläche.
- Der Chat benutzt den Chatverlauf nicht mehr als Gedächtnis. `clear chat` löscht nur die UI-Historie.
- v0.9.1-Chat-Historie wird beim ersten Start einmalig in den Unified Memory Core migriert.
- Alte `local_language_triples.json`-Dateien werden einmalig migriert und anschließend als `.migrated_v092` archiviert.
- MIND/Loom-Triples speisen denselben rekursiven F→Ω+n-Makro-Compiler (`text_mind`).
- Die MIND-Working-View bevorzugt echte aktive Makros und zeigt dadurch dieselben Tier-Labels wie andere Modalitäten.
- Produktbetrieb startet standardmäßig in `LEARN`, sofern der Nutzer nicht explizit `PLAY` als read-only gewählt hat. Der bevorzugte Live-Modus wird nach internen ARCHIVE/HYBRID/DREAM-Läufen wiederhergestellt.
- DREAM AUTONOMOUS kehrt beim Verlassen in den bevorzugten Live-Modus zurück statt pauschal auf PLAY zu fallen.

## Persistenzklassen nach v0.9.2

- MEMORY: BASE + PERSONAL/LIVE + persistente Candidates/Relations/Text-Surfaces/MIND
- ENCODER: Audio-/Vision-Codebooks (Identitätsabbildung, kein semantisches Wissen)
- RUNTIME: DREAM World State
- UI: Chat-History und Settings

Nur MEMORY ist das eigentliche Wissenssystem.

## Wichtiger Verhaltenstest

1. Kira etwas beibringen.
2. App beenden.
3. App neu starten.
4. Chat-History löschen.
5. Dasselbe Wissen erneut abfragen.

Das Wissen muss erhalten bleiben, weil Recall jetzt aus dem Unified Memory Core stammt und nicht aus der UI-Historie.

## Validierung

- `host_tests/run_mind_core_test.sh` → `MindCoreSelfTest OK`
- `host_tests/run_store_v2_test.sh` → `IndexedStoreV2SelfTest OK`
- Store-v2-Test schreibt und liest Candidates, Stream-State, Cross-Modal-Candidates, MIND-Triples und Text-Memory nach einem echten Reopen.

Ein vollständiger Android-APK-Build ist in der aktuellen Container-Umgebung weiterhin nicht möglich, da Android SDK/Gradle-Wrapper fehlen.
