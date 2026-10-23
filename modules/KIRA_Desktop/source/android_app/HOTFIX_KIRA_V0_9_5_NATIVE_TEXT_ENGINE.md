# v0.9.5 implementation note — Native Text Engine

Added `KiraNativeTextEngine.kt` as a pure deterministic surface layer. The engine owns no memory.
It consumes `ChatReplyPlan` candidates that already came from persistent Unified Memory and uses
the v0.9.4 dialectic decision state for stable branch selection.

`ChatReplyKind.NATIVE` marks generated product-facing language so legacy compacting does not
rewrite it and the chat engine does not feed Kira's own generated wording back into learning.
Relationsweg remains independent and continues to expose the underlying relation trace when enabled.
