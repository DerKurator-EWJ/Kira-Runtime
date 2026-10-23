# KIRA Unified Runtime 0.9.2 — Local Desktop API

This is a **persistent local HTTP process**, not a desktop GUI or an Android connection. It reuses the 0.9.1 runtime and its persistent UUI-to-TRIAD journal.

Start from this folder:

```bash
python kira_service_092.py --home ./kira_state --port 8765
```

On first launch, a random API token is saved in `kira_state/api_token.txt` (Unix permission 0600 where supported). Use `Authorization: Bearer <token>` for every request. Do not publish the token or forward the port. Server binds to `127.0.0.1` only, with no TLS, and is not designed for Internet or LAN exposure.

Routes:
- `GET /health` — version and liveness
- `GET /state` — TRIAD graph snapshot
- `GET /verify` — journal hash-chain check
- `GET /history` — last 100 journal entries
- `POST /event` — JSON `{"id":"unique-id","kind":"observation","source":"desktop","payload":{"content":"hello"}}`
- `POST /uui` — JSON `{"source":"hier(kira_event_count())"}`

Requests are limited to 64 KiB; concurrent requests serialize all runtime/SQLite operations. Duplicate event IDs remain idempotent. Runtime state persists in `--home` across service restarts. All code is Python standard library plus the included Kira modules.

**Limitations:** `/uui` executes the project's UUI language, not arbitrary Python, but do not expose this endpoint to untrusted clients. Errors after committed UUI event writes do not roll back earlier events. Numeric knowledge and TRIAD experiences still use distinct SQLite files. There is no cross-database transaction, semantic learning validation, desktop UI, Android client, or Godot link in this release.

Run tests: `python -m unittest discover -p 'test_*.py'`.
