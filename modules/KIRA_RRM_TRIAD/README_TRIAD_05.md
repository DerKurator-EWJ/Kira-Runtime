# KIRA TRIAD CORE 0.5 — experimental runtime

Based on KIRA RRM + UUI 0.4. Adds `kira_triad.py` and `test_triad_05.py`.

## Run

```bash
python -m unittest test_triad_05 test_rrm_uui_04 -v
python -m unittest discover -p 'test_*.py'
```

## Example

```python
from kira_triad import Triad
core = Triad()
core.tick(internal='0.001')   # EQ-only initial perturbation
core.tick()                   # direct EQ->IQ influence occurs
core.save('triad_state.json')
core = Triad.load('triad_state.json')
print(core.snapshot())
```

## What is implemented
- Three exact-rational process states (`iq`, `eq`, `meta`).
- Asymmetric bidirectional direct IQ/EQ coupling; updates use prior state simultaneously.
- META receives the *result* of the direct update, and adjusts coupling for future ticks.
- Internally initiated EQ perturbations without external stimulus or a forced explanation.
- Exact rational event history, JSON snapshot and atomic replacement, reload and replay-continuation test.
- Arithmetic operation and rational digit quotas via existing RRM budget.

## Limitations
- **This is a deterministic, minimal mathematical proof-of-concept**, not a trained emotional model, sentient agent, full decision system, or integrated Godot/Android runtime.
- IQ and EQ are scalar test channels, **not** fixed emotion meters: subsequent work must generalize to adaptive relational state graphs and multi-resolution bounds.
- No UUI builtins for TRIAD yet; the package includes the 0.4 UUI integration unchanged.
- `internal` perturbations are supplied by caller; an endogenous learned generator is not yet built.
- Meta adaptation is a simple rational feedback rule, not learned semantic interpretation.
- Persistent JSON state is local only; SQLite/Bridge integration pending.

## Next 0.6
1. Replace scalar channels with relational state graphs with process-number/bound edges.
2. Add event provenance, learned endogenous dynamics and measured ablations.
3. Provide UUI builtins and a persistent bridge adapter.
4. Compare numerical accuracy, stability, memory and throughput.
