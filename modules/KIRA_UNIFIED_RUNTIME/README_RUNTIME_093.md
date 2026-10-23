# KIRA Unified Runtime 0.9.3 — Desktop client + Android protocol planning

## Status

Implemented: Tkinter desktop client for the existing loopback-only 0.9.2 service; API transport, local token loading, UUI execution, TRIAD state, event history and journal verification. No additional Python dependencies. The central Kira runtime remains the only writer of persistent state.

Not implemented: Android application changes, LAN/WAN connectivity, pairing, TLS, device identity, mobile camera/microphone integration, unified database transactions, full semantic learning, Godot bridge.

## Start on the desktop

In this project directory, run in terminal A:

```sh
python kira_service_092.py --home ./kira_state --port 8765
```

Run in terminal B:

```sh
python kira_desktop_client_093.py --home ./kira_state --port 8765
```

The GUI automatically reads `kira_state/api_token.txt` locally. Never share that file. The service stays bound to `127.0.0.1`. The GUI uses background worker threads for HTTP calls, then marshals display updates back to Tk's main thread. Run on a machine with a graphical desktop/Tkinter installed.

Tests:

```sh
python -m unittest discover -p 'test_*.py'
```

## Android communication contract (DESIGN ONLY)

Do **not** expose the 0.9.2 bearer-token HTTP service to Wi-Fi, forward port 8765, or reuse its bearer token on Android. Proposed separate secure gateway:

1. Android initiates a pairing request, desktop user confirms a one-time challenge on both devices.
2. Each device creates its own cryptographic identity; store private credentials in Android Keystore and desktop protected storage.
3. Android connects via authenticated TLS 1.3 (or mutually authenticated secure transport), with certificate/identity pinning and explicit revocation.
4. Gateway grants device-scoped permissions (`events:write`, `state:read`) and rate/size limits; no unrestricted UUI execution by default.
5. Gateway validates event schema, source, event ID, timestamps and replay protection; passes validated events into the *one* local Kira runtime.
6. Camera/audio require separate user permissions and explicit activation; avoid storing raw recordings by default.
7. Add end-to-end tests for pairing, reconnect, duplicate events, invalid certificates, token rotation, offline queue and consent revocation.

This is a specification, **not a functioning Android connection**.
