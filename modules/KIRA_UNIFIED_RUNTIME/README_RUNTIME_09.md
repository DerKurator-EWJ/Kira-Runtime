# KIRA Unified Runtime 0.9 — Integration Build

This is a **working Python orchestration layer** combining the existing UUI VM and NumericCore bridge with the TRIAD 0.8 persistent event journal. It does not claim to implement general intelligence, consciousness, semantic understanding, or a production-grade distributed runtime.

## Start

```bash
python -m unittest discover -p 'test_*.py'
python kira_cli_09.py --home ./my_kira uui hello.uui
python kira_cli_09.py --home ./my_kira event n1 node runtime '{"name":"thought","owner":"IQ","state":"1"}'
python kira_cli_09.py --home ./my_kira event n2 node runtime '{"name":"affect","owner":"EQ","state":"1"}'
python kira_cli_09.py --home ./my_kira event t1 tick clock '{}'
python kira_cli_09.py --home ./my_kira event t2 tick clock '{}'
python kira_cli_09.py --home ./my_kira state
python kira_cli_09.py --home ./my_kira verify
```

Two persistent SQLite databases under `--home`: `knowledge.sqlite` (existing UUI/Unified Numeric Core bridge) and `experience.sqlite` (TRIAD journal). The UUI VM runs via `KiraRuntime.execute()`; the event journal via `KiraRuntime.event()`.

**Verified integration boundary:** one process starts both systems, UUI code executes, events update the TRIAD graph, automatic coactivation creates connections, event hashes validate, and a new process restores the same graph. **Not yet integrated:** UUI-language calls to the *same* persistent TRIAD graph (the older `triad_new()` creates an in-memory graph); one atomic transaction across both databases; semantic experience-driven response changes; a continuous daemon/API; desktop, Android and Godot live connections. These are subsequent milestones, not features of this build.

**Learning test caveat:** automatic coactivation changes the relational graph. This is a test of structural adaptation, not evidence of a learned meaningful response to natural language.
