# KIRA RRM + UUI 0.4 (integration prototype)

Includes original UUI_CORE_1_0_1 files (copied from mounted ZIP), RRM Arithmetic 0.3, and a **real integration** into `uui_lang.Runtime.evaluate` built-in calls. Original language syntax, compiler and normal numeric literals remain compatible. No Godot integration yet.

## Run
`python -m unittest test_rrm_uui_04 -v` or `python -m unittest discover -p 'test_*.py'` (legacy suite may require additional setup).

`python -c "from uui_lang import Runtime; print(Runtime(None).run(open('rrm_demo.uui').read()))"`

## UUI builtins
- `rrm_exact("1.22")`: exact rational from decimal string; no float conversion.
- `rrm_bound("1.22","1.23")`, `rrm_low(b)`, `rrm_high(b)`
- `rrm_step(x, places, direction)` direction ±1
- `rrm_append(x, digit, new_place)`
- `rrm_refine(bound, low, high)` (nested knowledge bound)
- `rrm_shift(bound, delta)` (state shift, not refinement)
- `rrm_state(x)`, `rrm_knowledge(low,high)`, `rrm_next(process,new_state_or_bound)`, `rrm_current(process)`, `rrm_history(process)`

UUI's ordinary numeric literals remain `Fraction`. RRM types are opt-in. RRM process updates are copy-on-write, and `Process.to_json()` provides serialization; no persistent event-store integration is claimed. ArithmeticBudget is per Runtime, not a global memory limit. Numeric result display is rational rather than decimal-formatted. 

## Next: 0.5
Coupled IQ/EQ/META experimental runtime with separately measured direct and interpreted channels, persistent state and reproducible tests. No subjective feeling claim.
