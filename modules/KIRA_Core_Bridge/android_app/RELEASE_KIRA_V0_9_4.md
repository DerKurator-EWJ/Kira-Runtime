# Kira v0.9.4 — Unified Decision Core

v0.9.4 moves Thesis/Antithesis out of the chat-specific surface and makes it one read-only decision stage above the universal F→Ω+n working memory introduced in v0.9.3.

## One decision mechanism

`KiraDecisionCore` receives modality-neutral evidence from the current universal working set. Text, audio, vision, cross-modal and future Forge/BASE nodes are evaluated by the same rule. The result contains:

- THESIS and ANTITHESIS votes
- real F→Ω+n depth per branch
- branch stability
- confidence/openness
- STABLE / OPEN / EMPTY status
- the IDs supporting each branch

Thesis is the currently reinforcing path; Antithesis is the competing/corrective path. These names do not mean true/false or positive/negative.

## Output projection

MASTER first selects which inputs are allowed to feed an output. The Unified Decision Core then projects that routed working state before it reaches product outputs.

A stable winning path keeps near-full activation. The competing path is attenuated but never deleted. When the decision is OPEN, both paths stay active so Kira can gather evidence or ask instead of fabricating certainty.

The same projection now feeds:

- Text/module state
- Film / procedural video
- DREAM influence
- Orchestra / unified audio

The diagnostic Thesis/Antithesis tier slab remains read-only and is now a view of the same core decision.

## Chat behavior

Chat still exposes only the final surface answer. The relation trace can show whether the decision is stable or open. A near tie / OPEN contradiction may produce a clarification question. Decision status/confidence is stored only as message metadata; it is not learned back as fact.

## DREAM

DREAM receives the same decision state: stable evidence increases coherence, OPEN states increase novelty/tension, and a stable corrective (Antithesis) path can add motion. The world still evolves without memory.

## Compatibility

No memory-store migration is required. v0.9.4 reads the same v0.9.2/v0.9.3 persistent memory. The decision core is a transient read-only projection and does not duplicate or mutate learned data.

## Validation

`bash host_tests/run_mind_core_test.sh`

The host suite covers stable Thesis, stable/open competition, F→Ω+n tier labels, identity/curiosity, operational modules, DREAM and the universal macro compiler.
