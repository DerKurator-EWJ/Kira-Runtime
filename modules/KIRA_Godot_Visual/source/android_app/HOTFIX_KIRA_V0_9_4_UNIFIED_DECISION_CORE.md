# v0.9.4 implementation note

- Added `KiraDecisionCore.kt` as the single modality-neutral decision stage.
- `DialecticDecision.kt` is now a compatibility/view adapter delegating to the core.
- MASTER-routed TEXT/VIDEO/AUDIO working states are projected by the same decision result.
- Losing evidence is attenuated, never erased; OPEN evidence keeps both branches live.
- Chat receives the raw target decision and can ask on OPEN conflicts.
- Film/DREAM/Orchestra consume decision-projected state.
- DREAM explicitly maps decision confidence/openness into coherence, novelty, tension and corrective motion.
- Existing memory format remains unchanged.
