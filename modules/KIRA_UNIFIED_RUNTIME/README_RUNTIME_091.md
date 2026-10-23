# KIRA Unified Runtime 0.9.1 — Persistent UUI ↔ TRIAD

This build adds **host-bound UUI functions** operating on the *same* SQLite-backed `ExperienceStore` as `KiraRuntime.event()`. Unlike the older `triad_new()` functions, these operate on the durable graph, survive restart, and are visible to CLI commands.

```bash
python -m unittest discover -p 'test_*.py'
python kira_cli_09.py --home ./kira_state uui persistent_demo.uui
python kira_cli_09.py --home ./kira_state state
python kira_cli_09.py --home ./kira_state verify
```

UUI functions: `kira_event(id,kind,source,payload_json)` returns boolean inserted; `kira_node_value(name)` returns exact rational; `kira_node_exists(name)` returns boolean; `kira_link_count()`, `kira_event_count()`, `kira_history_count(limit)` return integers as exact rationals; `kira_snapshot()` returns JSON text. Supported event kinds are those of TRIAD 0.8: node, link, tick, refinement, observation. Caller-supplied event IDs are idempotent if contents match and rejected if contents conflict. No untrusted Python eval/exec is introduced.

**Known limitations:** UUI scripts are NOT atomic transactions: committed events survive later statement failures. Knowledge and experience remain separate SQLite databases; no cross-store transaction exists. Graph adaptation uses heuristic coactivation, not semantic learning. No background service, natural-language understanding, desktop/Android/Godot connection, or evidence of subjective feeling is provided by this release. Legacy `triad_new()` remains an independent in-memory object for backwards compatibility.
