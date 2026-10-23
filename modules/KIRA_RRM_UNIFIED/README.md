# KIRA RRM Arithmetic + Unified Core adapter 0.3

**Delivered:** actual standalone arithmetic runtime 0.1, opt-in resource policy 0.2, and tested *adapter* to a verbatim copy of `KIRA_UNIFIED_CORE_1_4/numeric_core.py` (0.3). Python standard library only.

Run: `python -m unittest discover -s tests -v` from this directory.

## Semantics
- `Exact`: rational values, no floats; arbitrary finite decimal depth.
- `Bound`: closed rational intervals, nested *knowledge* refinement. `decimal_cell('1.22',2)` models the half-open decimal prefix `[1.22,1.23)` approximately with closed endpoints; do not treat the upper endpoint as included in prefix membership. For strict membership, use `low <= x < high`.
- `Process`: time-varying states OR refining knowledge; distinct modes.
- `ArithmeticBudget`: optional per-operation digit, place and count quotas; they limit resources, **not numerical possibility**. They do not cap memory use across an entire process or all arithmetic in the app.
- `UnifiedRRMAdapter`: writes exact point values into the **unchanged** existing SQLite schema; reads as Exact; exact bounded queries; history remains outside NumericCore. A new observation with the same identity is evidence and does not silently revise its indexed representative.

## What is NOT implemented
This is **not** an in-place migration of the full Unified Core, UUI interpreter, Android, Bridge, EQ/IQ/META, or Godot. The Unified Core file is bundled verbatim for adapter tests, not as a new Kira application release. A dedicated SQLite process-event store, transactional process persistence, schema migrations, compiler integration, and cross-platform benchmarks remain.

## Next before Godot
1. v0.4: integrate RRM types into the actual UUI parser/evaluator, test compatibility.
2. v0.5: persistent coupled IQ/EQ/META experiment with separate direct channel and meta interpretation.
3. v0.6: bridge/desktop, transactional replay, migrations, and benchmarks.
4. v0.7: Android bridge conversion and measured accuracy/performance before Godot gate.

## Quick example
```python
from rrm_arithmetic import Exact, Bound, Process, ArithmeticBudget, UnifiedRRMAdapter
from numeric_core import NumericCore  # from bundled unified_core/ on PYTHONPATH
core = NumericCore(':memory:')
rrm = UnifiedRRMAdapter(core)
id_ = rrm.observe_exact('1.221')
assert rrm.read_exact(id_).value == Exact('1.221').value
```
