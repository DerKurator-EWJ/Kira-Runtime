# Kira v0.9.8 — BASE Package / Validator / Hot Mount

v0.9.8 closes the runtime side of the Forge boundary introduced in v0.9.7.

## Pipeline

```
Teacher / Hugging Face
        ↓ adapter
Forge Intermediate v1
        ↓ external forge compiler
Kira BASE Package v1 (.kirabase)
        ↓ SHA-256 validate
STAGING
        ↓ structural validation
COMMIT / HOT MOUNT
        ↓
Unified Memory (BASE read-only + LIVE/PERSONAL writable)
```

## Package contents

`manifest.json`, `checksums.json`, `surfaces.bin`, `macros.bin`, `relations.bin`, `traits.bin`, `index.bin`.

The runtime package deliberately excludes teacher vectors. An adapter/forge may use vectors to derive sparse grounded relations, but Kira mounts only the resulting native knowledge structures.

## Atomic BASE lifecycle

- install/update by staged directory swap
- enable/disable without removing the package
- one-step rollback to the previous known-good package
- remove active BASE while retaining it as rollback target
- LIVE/PERSONAL profile data remains outside the BASE tree
- profile BASE requirements still quarantine incompatible DERIVED knowledge instead of silently rewriting it

## Compatibility

Existing v0.9.7 BASE storage is migrated from `equinox_v06_base/store` into the new active package directory on first use. Legacy `.eqkernel` and External Knowledge v1 imports remain as compatibility paths only.
