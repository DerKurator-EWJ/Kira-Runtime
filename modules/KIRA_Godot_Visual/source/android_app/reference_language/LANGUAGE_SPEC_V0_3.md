# Equinox Language v0.3 — Universal Semantic Compute Fabric

## Zweck

v0.3 trennt **Bedeutung** strikt von **physischer/logischer Ausführungsressource**.
Eine Compute-Cell besitzt keine feste Domäne wie Baum, Mathematik, Funktion, Bild oder Ω-Prozess. Sie führt ausschließlich numerische Arbeits-Pakete aus.

Das ist ein Softwaremodell einer später denkbaren universellen Hardware-Fabric — **kein Anspruch, dass ein einzelner Transistor allein eine semantische Entscheidung berechnet**. Ein Transistor schaltet; eine Compute-Cell modelliert eine beliebig kleine wiederverwendbare Schaltgruppe.

## Universelles Arbeitspaket

Jeder aktive Schritt wird kompiliert zu

`E = (A, R, B, O, C, K, P*)`

- `A` — subject ID
- `R` — relation ID
- `B` — counterpart ID
- `O` — operation ID
- `C` — context ID
- `K` — continuation ID
- `P*` — optionale Payload-IDs

Im Runtime-Paket sind alle Felder positive Integer-IDs. Es gibt **kein** Feld `domain`, `tree_type`, `math_type` o.ä.

## Semantische Regel

Die Sprache/der Compiler darf wissen, dass ein Kontext z. B. `tree:Vogel`, `tree:SSS`, `function:f` oder `process:Omega` heißt. Die Compute-Cell sieht davon nur `context_id`.

Der Kernel darf auf `operation_id` verzweigen (ISA-Semantik), aber **nicht auf Kontext, Subjekt oder Domäne**.

## Aktuelle universelle ISA

- `RELATE`
- `SPLIT`
- `MIRROR`
- `UNIFY`
- `DISSONATE`
- `HOLD`
- `WAKE`
- `SEAL`
- `EMIT`

Dies sind Prozessprimitive, keine Wissensdomänen.

## Keine Daueraktivität

Eine Cell ist `IDLE`, solange kein Paket in der Queue liegt. Ein leerer Tick führt zu keinem Schalt-/Ausführungsschritt und erhöht auch den Fabric-Takt nicht.

Nach Ausführung eines Pakets kehrt die Cell sofort nach `IDLE` zurück und kann im nächsten Tick eine völlig andere semantische Rolle ausführen.

## Offene Prozesse

`HOLD_INFINITY` ist eine Continuation-ID. Sie bedeutet:

> Der Prozess bleibt fortsetzbar, wird aber nicht automatisch erneut eingeplant.

Damit bleibt die v0.1/v0.2-Regel erhalten:

`Fortsetzbarkeit != Fortsetzungszwang`.

## Verbindung zum Forest/Loom Scheduler

Ein v0.2 `WakeRecord` kann compilerseitig in genau dasselbe universelle Paketformat übersetzt werden. Nach der Kompilierung besitzt die Cell keine Kenntnis mehr davon, dass das Paket ursprünglich aus einem Baum/Wald kam.

## Syntax v0.3

```text
FABRIC EquinoxLoom CELLS 1
WORK vogel Vogel faehigkeit fliegen MIRROR tree:Vogel HOLD_INFINITY
WORK sss SSS spiegel gegenpol MIRROR tree:SSS HOLD_INFINITY
WORK funktion f abbildung x RELATE function:f SEAL
WORK omega Omega verfeinerung naechste_schranke RELATE process:Omega HOLD_INFINITY
RUN
```

## Numerische Rückkompilierung

Auch Fabric-Programme und Ergebnisse werden wieder als reine

`ID -> ID -> ID`

Tripel serialisiert. Damit bleibt v0.3 kompatibel mit dem fundamentalen Equinox-Zahlenraum.

## Nicht behauptet

v0.3 beweist **nicht**:

- einen neuen physischen Transistoraufbau,
- Energieeffizienz auf realem Silizium,
- Turing-Vollständigkeit der aktuellen 9-Opcode-ISA,
- oder dass ein einzelner Transistor allein beliebige Berechnung ausführt.

v0.3 beweist die Softwarearchitektur: dieselbe unspezialisierte Compute-Cell kann jede aktuell definierte Equinox-ISA-Operation in beliebigen semantischen Kontexten ausführen.
