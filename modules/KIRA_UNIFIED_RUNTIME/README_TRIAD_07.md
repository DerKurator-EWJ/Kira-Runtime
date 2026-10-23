# KIRA TRIAD 0.7 — adaptive structural learning + epistemic bounds

- `kira_triad_07.py`: exact rational adaptive graph with per-node epistemic bounds distinct from actual node states; nested refinement; bounded, deterministic coactivation-driven edge creation; weak-edge pruning for auto-created links; atomic tick by copy-and-commit; JSON snapshots.
- `uui_triad.py`: native UUI calls `triad_new`, `triad_node`, `triad_link`, `triad_bound`, `triad_tick`, `triad_value`, `triad_low`, `triad_high`, `triad_links`. The interpreter mutates a TRIAD object referenced by a variable; it does not yet provide durable UUI object persistence.
- `triad_demo.uui`: UUI execution example.
- `test_triad_07.py`: unit and integration tests.

**Scope:** Coactivation is an explicit heuristic; not genuine understanding, emotional feeling, or a learned world model. Nodes still require creation via API or UUI; link creation is autonomous under specified rules. Knowledge intervals represent uncertainty, not hidden exact physical values. Godot and Android remain unchanged.

Run: `python -m unittest discover -p 'test_*.py'`
