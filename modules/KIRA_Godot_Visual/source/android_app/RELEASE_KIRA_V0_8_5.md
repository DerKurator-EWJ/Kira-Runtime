# Kira v0.8.5 — Operational Module Bus

## Kernänderung

MATHE, CODING, FILM und ORCHESTRA sind keine reinen UI-Modi mehr. Sie registrieren sich als operative Generatoren an einem gemeinsamen read-only State-Paket.

`Input -> MIND/FABRIC + Memory -> KiraModuleContext -> aktive Module -> Output`

Das State-Paket enthält:
- aktuellen Textauftrag,
- aktive 63-bit IDs,
- konkurrierende gelernte Chat-Relationen als Hinweise,
- den normalisierten gemeinsamen Präsentationszustand (Aktivität, Erwartung, Relationenergie, Neuheit, Prediction, Crossmodal, Memory-Energie usw.).

Module schreiben nicht direkt in andere Module. Dadurch kann ein späteres lokales Modell, Forge-Backend oder externer Generator hinter derselben Schnittstelle ausgetauscht werden.

## MATHE
- echter lokaler rekursiver Ausdrucksparser
- `+ - * / % ^`, Klammern
- optional `sqrt/sin/cos/tan/abs/ln/log/floor/ceil/round`
- einstellbare Ausgabepräzision
- greift nur ein, wenn ein mathematischer Ausdruck erkannt wird

## CODING
- operative lokale Deterministik für sichere Funktions-/Klassen-Scaffolds
- Kotlin, Python, JavaScript
- STRICT oder SCAFFOLD
- optionaler gelernter Relationskontext
- freie komplexe Codegenerierung kann später denselben Module-Bus mit einem stärkeren Generator verwenden

## FILM
- operativer prozeduraler State-Director
- erzeugt aus State + IDs eine laufende Verformungsdirective für Bild/Video
- Intensität und State-Einfluss einstellbar
- keine Behauptung eines vorhandenen Diffusions-/Videomodells; dessen Backend kann später an dieselbe Directive-Schnittstelle angeschlossen werden

## ORCHESTRA
- bestehende lokale Synthese ist jetzt als operatives Modul geroutet
- Memory/Now-Mix, Stimmenzahl und Tempo skalierbar
- weiterhin keine Samples; der gemeinsame State erzeugt die Szene

## UI
Die obere Leiste besteht jetzt aus exakt fünf Punkten:
1. Text-Input
2. Audio-Input
3. Kamera-Input
4. Ausgaben
5. Einstellungen

Der alte separat schwebende Settings-Knopf ist in der Produktoberfläche ausgeblendet.

## Einstellungen
Neuer Pfad `KIRA / MODULE` mit eigenen Seiten für MATHE, CODING, FILM und ORCHESTRA.

## Kontinuität
Application-ID bleibt `de.equinox.v05`, damit normale APK-Updates die privaten Kira-Daten weiterverwenden können.
