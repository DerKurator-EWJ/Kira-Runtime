# KIRA TRIAD 0.8 — persistent episodic journal

`kira_experience_08.py` adds an SQLite-backed, transactional event journal with source attribution, event IDs for idempotency, SHA-256 hash chaining, and a full post-event TRIAD checkpoint. Events: `node`, `link`, `tick`, `refinement`, `observation`. Observations are recorded without automatically changing the TRIAD graph: provenance is not semantic understanding. Exact rational values and epistemic intervals are preserved through JSON snapshots. Failed graph transitions do not append an event or change in-memory state. On startup, the journal is verified and the latest checkpoint restored. `snapshot_at(event_id)` allows inspecting earlier states.

Run: `python -m unittest discover -p 'test_*.py'`.

Example:
```python
from kira_experience_08 import ExperienceStore
store = ExperienceStore('kira_experience.sqlite')
store.append('evt-001', 'node', 'runtime', {'name':'affect', 'owner':'EQ', 'state':'1.22'})
store.append('evt-002', 'observation', 'microphone', {'content':'speech detected'})
store.append('evt-003', 'tick', 'clock', {})
store.close()
```

## Limits / not implemented

- Journal and graph are one persistent experimental runtime, **not** the entire Kira BASE/PERSONAL/EPISODIC storage architecture.
- No semantic embedding, salience learning, forgetting/consolidation, automatic interpretation of observations, or continuous background execution.
- SQLite protects transactional consistency, not against malicious modification. The unkeyed hash chain detects accidental edits, not adversarial rewriting of the full chain.
- The complete graph is stored per event for auditability; this is intentionally inefficient for large event streams. Add incremental checkpoints and compaction later.
- UUI does not yet expose journal functions. Existing UUI integration remains unchanged.
- This architecture does not demonstrate subjective feeling or consciousness.
