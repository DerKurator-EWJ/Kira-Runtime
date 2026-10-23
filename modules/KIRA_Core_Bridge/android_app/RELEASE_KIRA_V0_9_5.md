# Kira v0.9.5 — Native Text Engine

v0.9.5 adds a deterministic local surface generator above the v0.9.4 Unified Decision Core.
It is deliberately not an LLM: readable text memories selected by Unified Memory and the
F→Ω+n hierarchy are converted into short German answers by a small sentence planner.

## Runtime path

```
Unified Memory
    ↓
Universal Macro Compiler
    ↓
Unified Decision Core
    ↓
Native Text Engine
    ↓
product chat
```

The internal mirror/relation loop remains available only through the optional Relationsweg
feature. It is no longer the ordinary product-facing answer surface.

## Native behaviour

- direct identity protocol remains fixed (`Kira`, `Kurator`)
- questions use persistent readable memories selected by the unified memory path
- yes/no questions produce short `Ja.` / `Nein.` answers plus the selected fact
- definition/open questions return one or two concise learned statements
- new declarative facts receive a short acknowledgement instead of a mirrored loop
- repeated facts receive a compact known-state acknowledgement
- unresolved questions still trigger Kira's curiosity/open-state question path
- stable thesis/antithesis decisions select the compatible learned branch
- open thesis/antithesis conflicts remain questions rather than forced answers

## No self-training loop

Native generated wording is a projection, not new evidence. v0.9.5 therefore does not feed
`NATIVE` assistant answers back into Unified Memory. User observations remain the evidence source.

## Persistence

No store migration is required. v0.9.5 reads the same Indexed Store v2 used by v0.9.2–v0.9.4.
Readable text surfaces remain persistent independently of chat history.

## Validation

- `MindCoreSelfTest OK`
- `IndexedStoreV2SelfTest OK`
- native tests cover learned definition, property and yes/no answers, new/repeated acknowledgements,
  dialectic branch selection, identity, curiosity and module passthrough.
