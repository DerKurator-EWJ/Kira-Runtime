# Equinox v0.7.1.2 — Chat Send Hotfix

- SEND is now an explicit high-contrast button with click + haptic feedback.
- IME SEND/DONE submits the message.
- Hardware Enter submits; Shift+Enter remains available for line breaks where supported.
- MainActivity uses `adjustResize` so the composer stays above the software keyboard.
- Chat submission runs off the UI thread and disables SEND while processing.
- Chat history is rendered before HYPNO projection, so a renderer failure cannot hide a valid text reply.
- Existing Matrix/Clean/Raw modes, local MIND triples, BASE/Forge mounting and chat history format are unchanged.
