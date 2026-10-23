#!/usr/bin/env python3
"""LEGACY v0.56 bridge: local Hugging Face causal LM -> Android-ready .eqkernel.json.

Kira v0.9.7 prefers teacher adapters that emit kira-forge-intermediate-v1. This file stays for
compatibility with the older flat-kernel path.

No model is downloaded. MODEL_DIR must already contain a local HF model/tokenizer.
The semantic probe contract is supplied by CONFIG.json so Forge itself stays domain-agnostic.
"""
from __future__ import annotations
import argparse, json
from pathlib import Path

from equinox_forge.database import CanonicalDB
from equinox_forge.teacher_behavior import TransformersCausalLMBackend
from equinox_forge.top_down_teacher_structure import (
    AttributeProbeSpec, TopDownTeacherPolicy, _concept_node_id,
    extract_top_down_teacher_structure,
)
from equinox_forge.canonical_id_space import canonical_node_id, materialize_canonical_id_space
from equinox_forge.canonical_relation_facets import materialize_relation_faceted_id_space
from equinox_forge.structural_closure import StructuralClosurePolicy, materialize_structural_closures
from equinox_forge.recursive_macro_collapse import RecursiveMacroPolicy, materialize_recursive_macro_collapse
from equinox_forge.recursive_relation_collapse import RelationMacroPolicy, materialize_recursive_relation_macro_collapse
from equinox_forge.direct_teacher_flat_compiler import compile_flat_kernel_direct


def load_config(path: Path):
    c = json.loads(path.read_text(encoding="utf-8"))
    policy = TopDownTeacherPolicy(
        parent_prompt_template=str(c["parent_probe_template"]),
        attribute_probes=tuple(AttributeProbeSpec(str(x["relation_type"]), str(x["prompt_template"])) for x in c.get("attribute_probes", [])),
        stop_surfaces=tuple(str(x) for x in c.get("stop_surfaces", [])),
        max_parent_depth=int(c.get("max_parent_depth", 8)),
        top_k=int(c.get("top_k", 8)),
        temperature=float(c.get("temperature", 1.0)),
        min_probability=float(c.get("min_probability", 0.25)),
        evidence_confidence=float(c.get("evidence_confidence", 0.90)),
        extraction_namespace=str(c.get("extraction_namespace", "external-hf-oven")),
        attributes_on_internal_concepts_only=bool(c.get("attributes_on_internal_concepts_only", True)),
    )
    policy.validate()
    seeds = [str(x) for x in c["seed_surfaces"]]
    if not seeds:
        raise ValueError("seed_surfaces must not be empty")
    return c, policy, seeds


def main():
    ap = argparse.ArgumentParser(description="Equinox external HF oven -> Android .eqkernel.json")
    ap.add_argument("model_dir")
    ap.add_argument("config")
    ap.add_argument("--out", default="out/hf_android_oven")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--dtype", default="auto")
    ap.add_argument("--trust-remote-code", action="store_true")
    a = ap.parse_args()

    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    config, policy, seeds = load_config(Path(a.config))
    teacher = TransformersCausalLMBackend(a.model_dir, device=a.device, dtype=a.dtype, trust_remote_code=a.trust_remote_code)
    db_path = out / "forge.sqlite"
    kernel_path = out / "knowledge.eqkernel.json"
    if db_path.exists(): db_path.unlink()
    if kernel_path.exists(): kernel_path.unlink()

    with CanonicalDB(db_path) as cdb:
        structure = extract_top_down_teacher_structure(
            cdb.db, teacher, seed_surfaces=seeds,
            source_id=str(config.get("source_id", "external-hf-teacher")),
            source_version=str(config.get("source_version", "1")),
            policy=policy,
            metadata={"oven": "android-bridge-v1", "model_dir": str(Path(a.model_dir).resolve())},
        )
        base = materialize_canonical_id_space(cdb.db)
        facets = materialize_relation_faceted_id_space(cdb.db, source_canonical_run_id=base.run_id, metadata={"oven": "android-bridge-v1"})
        roots = [canonical_node_id(cdb.db, _concept_node_id(policy.extraction_namespace, surface)) for surface in seeds]
        closure = materialize_structural_closures(
            cdb.db, canonical_run_id=facets.derived_canonical_run_id, root_ids=roots,
            policy=StructuralClosurePolicy(max_parent_depth=policy.max_parent_depth),
        )
        concept_macro = materialize_recursive_macro_collapse(
            cdb.db, canonical_run_id=facets.derived_canonical_run_id,
            structural_closure_run_id=closure.run_id, root_ids=roots,
            policy=RecursiveMacroPolicy(min_members=2, min_common_facts=2, max_levels=4),
        )
        relation_macro = materialize_recursive_relation_macro_collapse(
            cdb.db, relation_facet_run_id=facets.run_id,
            policy=RelationMacroPolicy(min_members=2, min_common_meta_facts=1, max_levels=4),
            metadata={"oven": "android-bridge-v1"},
        )
        pack, summary = compile_flat_kernel_direct(
            cdb.db, kernel_path,
            canonical_run_id=facets.derived_canonical_run_id,
            concept_macro_run_id=concept_macro.run_id,
            relation_facet_run_id=facets.run_id,
            relation_macro_run_id=relation_macro.run_id,
            metadata={
                "oven": "android-bridge-v1",
                "teacher_backend": teacher.backend_id,
                "teacher_structure_digest": structure.digest,
            },
        )

    report = {
        "output": str(kernel_path.resolve()),
        "digest": pack.digest,
        "facts": len(pack.facts),
        "surfaces": len(pack.surfaces),
        "concept_shortcuts": len(pack.concept_shortcuts),
        "routes": len(pack.routes),
        "teacher_queries": structure.teacher_queries,
        "teacher_concepts": structure.concept_count,
        "compiler": summary.to_dict(),
    }
    (out / "android-oven-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))

if __name__ == "__main__":
    main()
