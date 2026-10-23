# Kira v0.9.2.1 — Identity + Curiosity

This hotfix adds a fixed non-learned dialogue constitution on top of the v0.9.2 Unified Memory Core.

## Identity protocol

- Visible terminal names are now `Kira` and `Kurator`.
- Kira answers direct identity questions with `Ich bin Kira.`.
- The user-facing terminal identity is fixed as `Kurator`.
- Rename/control prompts such as `Nenn dich ...` do not overwrite Kira's identity.
- Identity-control messages are not written as learned semantic knowledge.

## Autonomous questions

Kira may now turn unresolved internal states into questions instead of exposing a dead-end status:

- unknown direct question -> asks what it should learn;
- repeated unresolved material -> asks what is decisive;
- near-tied thesis/antithesis contradiction -> asks which statement should receive more weight;
- strong simultaneous Audio + Vision context on a new unresolved statement -> may ask whether the inputs should be linked.

Questions are stored in chat history only and are not fed back as learned facts.

`EquinoxChatEngine.askAutonomously(...)` is also available for DREAM/sensor/planner runtimes to emit future unsolicited questions without contaminating semantic memory.

## Validation

- MindCoreSelfTest OK
- IndexedStoreV2SelfTest OK
