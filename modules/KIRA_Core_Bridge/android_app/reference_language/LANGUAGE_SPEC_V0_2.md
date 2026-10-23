# Equinox Language v0.2 — Forest / Loom Scheduler Contract

v0.2 extends the v0.1 Tree/Mirror semantics without changing them.

## 1. Forest

A forest is a network of locally sealed tree identities:

`FOREST(W) = { TREE_i, LINK_j }`

Each tree remains its own identity. The forest does not flatten tree interiors into one global recomputation space.

## 2. Tree identity versus activity

A tree has a **structural identity** derived only from its topology, roles, triples and mirror structure.

Scheduler state is excluded:

- `ACTIVE`
- `SEALED`
- `HOLD_INFINITY`
- wake history
- scheduler order

Therefore:

`WAKE -> SETTLE`

with no structural refinement preserves the same forest tree identity.

## 3. Links

A forest link connects tree/node endpoints:

`(tree_a, node_a) -relation-> (tree_b, node_b)`

Endpoints may target a concrete node or `*` for any structural change in the source tree. Links can be one-way or bidirectional.

A link is a relevance route, not a claim that one side is a cause, condition, truth or antithesis of the other.

## 4. Delta-driven loom

Only a **real delta** enters the loom queue:

- structural refinement,
- a new local mirror result,
- or an explicit external/world delta.

A scheduler `WAKE` is not a delta.

This is a hard invariant:

`WAKE != CHANGE`

## 5. No automatic cascade

For a chain:

`A -> B -> C`

if A changes:

`A delta -> WAKE(B)`

but C remains asleep.

Only if B subsequently undergoes a real structural change may it emit:

`B delta -> WAKE(C)`

This prevents a connected forest from turning every local change into an automatic whole-world recomputation.

## 6. Local wake

Links may target a specific branch/node. If a link targets `TREE_VOGEL:t`, only that node is activated. Its siblings remain suspended.

## 7. Settle

`SETTLE(tree)` returns an awake tree to its locally sealed macro state without creating a delta when its structure did not change.

A stable tree is therefore computed only when its world changes it in a relevant way.

## 8. Forest seal

`SEAL_FOREST` creates a deterministic forest macro identity from:

- all structural tree identities,
- all forest links.

Runtime activity and history do not affect the forest macro identity.

## 9. Numeric world compilation

A sealed forest compiles into the same substrate as the tree language:

`ID -> ID -> ID`

The numeric forest contains:

- tree macro identities,
- internal tree topology/facts,
- forest link identities,
- direct tree-to-tree relations.

Scheduler-only wake history is excluded from numeric compilation.

## 10. v0.2 non-goals

v0.2 does not yet decide *how* a woken tree should alter itself. The loom routes relevance and schedules local thought; the native reaction/transition semantics remain a later language layer.

It also does not yet define:

- automatic antithesis construction,
- learned link creation,
- learned wake thresholds,
- process-macro learning,
- automatic extinction/decay,
- resource-priority arbitration between simultaneously active trees,
- final surface syntax.
