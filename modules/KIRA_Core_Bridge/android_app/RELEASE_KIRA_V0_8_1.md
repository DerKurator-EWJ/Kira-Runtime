# Kira v0.8.1 — Input / State / Output

## Product surface

- Top dock = input only: text, microphone, camera.
- Fourth top control opens one output menu.
- Output menu: Kira text, ID flow, ID flow behind Kira, Das Tor zum Jetzt, Klang.
- Lower three symbols remain view selectors only: Kira, ID flow, Now Gate.

## Kira

- Chat names remain `kurator` and `kira`.
- Messages use translucent black glass surfaces over the ID-flow background.
- After send, the composer immediately reacquires focus and reopens the keyboard when appropriate.
- When a question matches an already learned local relation, Kira now projects the recalled relation as `◆ antwort` instead of merely mirroring the question.
- Unknown input still stays honest: it is learned/mirrored rather than fabricated into a free-language answer.

## ID flow

- Remains a dedicated full viewport.
- Can also be composited behind the Kira chat.
- Glass chat surfaces attenuate the flow locally while leaving the full effect visible around messages.
- Speed remains presentation-only and never changes core compute speed.

## Das Tor zum Jetzt

- Reworked from node-centred rotating trees to an originless tree matrix.
- Virtual roots live outside the viewport, so no single beginning is visible.
- Recursive spread collapses toward the tips until branches become long rays.
- Motion is a slow global field deformation rather than trees simply rotating at node centres.
- State still determines colour, density, branching and motion.

## Audio

- Relational sound output is restored as a first-class output toggle in the output menu.
- Audio and camera are inputs only when their top input switches are enabled. Outputs never start sensors implicitly.

## Compatibility

- Existing package/application ID remains `de.equinox.v05`.
- Legacy per-output cross-input preferences are preserved on disk but are no longer required for the shared-state product path.
