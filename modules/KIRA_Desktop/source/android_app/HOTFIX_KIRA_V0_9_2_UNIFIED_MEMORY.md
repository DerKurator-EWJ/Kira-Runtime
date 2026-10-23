# HOTFIX — v0.9.2 Unified Memory

Behoben wurde ein Architekturfehler, bei dem mehrere Speicherpfade nebeneinander existierten und Candidate-Evidenz beim Prozessneustart verloren ging.

Die wichtigste Korrektur ist, dass sub-makro Evidenz jetzt Teil des persistenten Speichers ist. Zusätzlich sind lokales MIND und Text-Recall in denselben IndexedLongTermStore umgezogen. Der Chatverlauf ist nur noch Darstellung.

Kompatibilität:
- v0.9.1 Indexed-Store v1 wird weiterhin gelesen; fehlende v2-Dateien werden als leer interpretiert.
- bestehende lokale MIND-JSONs werden migriert.
- bestehende User-Chat-Turns werden einmalig als Text-Memory migriert, falls der neue Text-Memory noch leer ist.
