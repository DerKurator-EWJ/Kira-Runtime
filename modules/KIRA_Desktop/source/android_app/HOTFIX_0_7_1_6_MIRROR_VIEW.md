# Equinox v0.7.1.6 — Mirror View Router

## Main surface
- Upper TEXT / VIDEO / MUSIK buttons are now pure output ON/OFF switches.
- Double tap no longer changes cross-modal routing.
- Cross-modal input stays in each channel's Settings page.
- A second TEXT / VIDEO / MUSIK selector sits directly above the composer and controls only the main viewport.
- View selection and output activation are intentionally independent.
- The text composer remains reachable in every viewport so text can still feed active VIDEO/MUSIK paths.

## Kira / Kurator identity
- Visible identity labels are now only `kurator` and `kira`.
- No `@equinox`, `@mind`, shell suffix or prompt decoration is shown.
- Identity labels are highlighted in bright Matrix green and bold; message bodies are visually subordinate.
- The composer label is simply `kurator`.

## Mirror text rule
- Kira's visible product output contains letters, whitespace and symbols only.
- Numeric IDs, counters, percentages and diagnostic values are no longer emitted into the conversation surface.
- Numeric state remains available internally and through LAB diagnostics.
- Kira's first-stage response is a deterministic mirror projection of observed concepts, e.g.:
  `◇ lernen · vogel → kann → fliegen ⇄ fliegen ← kann ← vogel`
- BASE, association and repetition states use symbolic/letter-only markers instead of diagnostic dumps.
- Legacy stored diagnostic replies are remapped to the new mirror surface when displayed.

## Validation
- `host_tests/run_mind_core_test.sh` -> `MindCoreSelfTest OK`
- Added assertions that visible mirror replies contain no digits.
