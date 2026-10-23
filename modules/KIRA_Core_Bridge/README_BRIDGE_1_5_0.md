# KIRA Core Bridge 1.5.0 — Integrationspaket

## Enthalten
- Vollständige Kopien der beiden gelieferten Archive unter `python_core/` und `android_app/`.
- **Neu**: `python_core/KIRA_UNIFIED_CORE_1_4/kira_core_bridge.py`: lokaler HTTP-Eventserver mit SQLite WAL, UUID-Idempotenz, Konflikterkennung und Token-Authentifizierung.
- **Neu**: `android_app/app/src/main/java/de/equinox/v05/KiraCoreBridgeClient.kt`: opt-in Android HTTP-Client.
- Android-Manifest: `INTERNET`-Berechtigung und lokale Cleartext-Ausnahme (nur Loopback).
- **Neu**: `test_kira_core_bridge.py`.

## Start auf Windows (PC)
Im Ordner `python_core/KIRA_UNIFIED_CORE_1_4`:

```powershell
$env:KIRA_BRIDGE_TOKEN = 'HIER_EIN_LANGES_ZUFAELLIGES_GEHEIMNIS_EINTRAGEN'
python -m unittest -v test_kira_core_bridge
python kira_core_bridge.py --db kira_bridge.sqlite3 --port 8765
```

Für ein **physisches Android-Gerät** mit USB-Debugging:

```powershell
adb reverse tcp:8765 tcp:8765
```

In der Android-App den Client **explizit** aus einem Hintergrundthread aufrufen:

```kotlin
val bridge = KiraCoreBridgeClient(token = "DASSELBE_GEHEIMNIS")
val response = bridge.postEvent("Kira", "observation", "EPISODIC", org.json.JSONObject().put("text", "Testereignis"))
```

**Achtung:** Das Beispiel zeigt nur den Aufruf. Der Client ist absichtlich **noch nicht** in die Chat-/Dream-/Memory-Pipeline verdrahtet. Den Token niemals fest in eine veröffentlichte App schreiben. Der Server lauscht ausschließlich auf `127.0.0.1`; über `adb reverse` kann das angeschlossene Gerät zugreifen. Für spätere Netzwerkeinsätze TLS und sichere Schlüsselverwaltung ergänzen.

## Verifizierter Umfang / Grenzen
Der Server speichert Ereignisse persistent, liefert sie zurück und verhindert doppelte Einträge. Die Android-Klasse ist ein Transportadapter, keine vollständige Kira-Core-Migration. Es findet **noch kein automatischer** Austausch mit `EquinoxMindCore`, `KiraDecisionCore`, `MemoryModel` oder `ExperimentPlanner` statt. Lernentscheidungen, autonome Aktionen, Berechtigungsgates und synchronisierte relationale Zustände bleiben Folgearbeiten. Dies ist absichtlich ein kleiner, testbarer Integrationsschritt ohne zweite Gedächtnisarchitektur.
