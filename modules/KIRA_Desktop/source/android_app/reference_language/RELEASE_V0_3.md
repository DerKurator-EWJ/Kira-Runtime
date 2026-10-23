# Equinox Language v0.3 — Universal Semantic Compute Fabric

v0.3 ergänzt v0.1 Tree/Mirror und v0.2 Forest/Loom um eine domain-blinde Ausführungsschicht.

## Neu

- `SemanticPacket`: rein numerisches `(subject, relation, counterpart, operation, context, continuation, payload*)`-Paket.
- `SemanticCompiler`: Bedeutung bleibt compilerseitig in Symbol→ID-Bindings.
- `UniversalKernel`: verzweigt nur auf ISA-Operationen, nie auf semantische Domänen.
- `UniversalComputeCell`: keine feste Rolle; dieselbe Cell führt nacheinander beliebige Pakete aus.
- `UniversalComputeFabric`: nur tatsächlich queued work wird ausgeführt; keine Arbeit = kein Tick.
- `HOLD_INFINITY` als suspendierte Continuation ohne Auto-Rekursion.
- Forest/Loom Wake→Packet Adapter.
- v0.3 `FABRIC/WORK/RUN` Syntax.
- numerische Fabric-Kompilierung zurück zu ID→ID→ID.

## Referenzdemo

Eine einzige Compute-Cell verarbeitet nacheinander vier unterschiedliche Kontexte:

- `tree:Vogel`
- `tree:SSS`
- `function:f`
- `process:Omega`

Ergebnis:

- Cells: 1
- Work packets: 4
- Ticks: 4
- Executions on same cell: 4
- Active cells after completion: 0
- Numeric triples: 52
- Numeric identities: 42
- Numeric digest: `290c25772c19689fcb689be8d8a89a13e1029deecb2eb60bdbb814e404146990`

## Härtung

Die gleiche einzelne Cell wurde zusätzlich über **alle 9 aktuellen ISA-Primitiven** geführt. Kein Opcode erfordert eine spezialisierte Cell.

## Regression

- 21/21 Tests bestanden.
- v0.1 Kernmodule `model.py`, `runtime.py`, `parser.py`, `numeric.py` byteidentisch zu v0.2.
- v0.2 Waldmodule `forest.py`, `forest_numeric.py`, `forest_parser.py` byteidentisch zu v0.2.
