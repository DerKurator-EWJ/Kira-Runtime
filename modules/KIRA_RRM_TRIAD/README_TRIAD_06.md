# KIRA RRM TRIAD 0.6 — relational process graph

Extends TRIAD 0.5 without replacing its compatible `kira_triad.py` API.

## New
- `kira_relational_triad.py`: arbitrary named IQ/EQ/META nodes, sparse directed weighted links, rational state/drift and optional plasticity.
- Direct IQ↔EQ coupling executes before META integration; META cannot veto it.
- Nodes/links can be added at runtime; no fixed emotion dimensions or numeric 0–1 scale.
- All values use exact `fractions.Fraction`, no float coercion. Finite decimal refinement can continue as resources permit.
- JSON snapshots with exact numerator/denominator pairs, atomic replace, deterministic continuation.
- Explicit resource budgets for nodes, links, rational bit length and history.
- Tests in `test_relational_triad_06.py`.

## Run
`python -m unittest discover -p 'test_*.py'`

## Caveats
This is a functional computational experiment, not evidence of felt emotion or consciousness. `drift` is an endogenous update rule, not spontaneous subjective experience. Numeric refinement is not measurement accuracy. The graph currently uses simple additive dynamics and correlation-based plasticity; it is not a trained cognitive model. UUI syntax bindings for graph creation and edits are future work, although earlier UUI/RRM integration remains in the package. No Godot changes.
