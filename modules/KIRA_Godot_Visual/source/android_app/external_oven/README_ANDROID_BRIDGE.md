# External Oven → Kira Bridge

Kira v0.9.7 freezes **Forge Intermediate v1** as the preferred teacher-neutral boundary.
Hugging Face remains optional and external to the Android runtime.

## Preferred path from v0.9.7

1. A teacher-specific desktop adapter reads the local model/data.
2. The adapter writes `kira-forge-intermediate-v1` (`*.kiraforge.json`).
3. Validate it with `python kira_forge_intermediate_v1.py file.kiraforge.json`.
4. Copy the document to Android and use **ARCHIVE INTAKE → FORGE v1 · DRY-RUN**.
5. Kira recomputes deterministic IDs, F→Ω+n depth, promotion scores and relation semantics.
6. v0.9.7 intentionally stops after validation/planning. Atomic BASE materialisation arrives in v0.9.8.

The exact schema is in `FORGE_INTERMEDIATE_V1.md` and `kira_forge_intermediate_v1.schema.json`.

## Legacy v0.56 path

The existing `hf_oven_to_android.py` → `knowledge.eqkernel.json` route is kept for compatibility
with already-produced Forge v0.56 kernels. It is no longer the preferred contract for new teachers.

Legacy steps:

1. Use a local Hugging Face causal-LM directory (Forge never downloads a model behind your back).
2. In `Equinox_Forge_v0_56` install the optional HF runtime: `pip install -e '.[hf-runtime]'`.
3. Run `PYTHONPATH=Equinox_Forge_v0_56 python hf_oven_to_android.py /path/to/model hf_oven_config.example.json --out out/my_model`.
4. Copy `out/my_model/knowledge.eqkernel.json` to Android.
5. Use **MIND → LEGACY FORGE KERNEL → MOUNT .EQKERNEL**.

The Android app remains self-contained after either path; no teacher or Python runtime is required at runtime.
