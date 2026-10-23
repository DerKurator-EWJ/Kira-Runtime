#!/usr/bin/env python3
"""Small dependency-free helper for producing Kira Forge Intermediate v1 JSON.

Teacher-specific extraction belongs in an adapter. This module only provides the frozen output
shape and cheap structural checks; Android performs the authoritative Kira validation again.
"""
from __future__ import annotations
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any
import json

FORMAT = "kira-forge-intermediate-v1"
SCHEMA_VERSION = 1

@dataclass
class ForgeDocument:
    package: dict[str, Any]
    source: dict[str, Any]
    atoms: list[dict[str, Any]] = field(default_factory=list)
    vectors: list[dict[str, Any]] = field(default_factory=list)
    relations: list[dict[str, Any]] = field(default_factory=list)
    sequences: list[dict[str, Any]] = field(default_factory=list)
    macroCandidates: list[dict[str, Any]] = field(default_factory=list)
    traits: list[dict[str, Any]] = field(default_factory=list)

    def payload(self) -> dict[str, Any]:
        return {"format": FORMAT, "schemaVersion": SCHEMA_VERSION, **asdict(self)}

    def validate_basic(self) -> list[str]:
        errors: list[str] = []
        if not self.package.get("id"): errors.append("package.id missing")
        if int(self.package.get("version", 0)) <= 0: errors.append("package.version must be > 0")
        if not self.package.get("namespace"): errors.append("package.namespace missing")
        for k in ("id", "adapterId", "adapterVersion", "modality"):
            if not self.source.get(k): errors.append(f"source.{k} missing")
        ids: set[str] = set()
        for family in (self.atoms, self.sequences, self.macroCandidates):
            for row in family:
                rid = str(row.get("id", ""))
                if not rid: errors.append("structural record id missing")
                elif rid in ids: errors.append(f"duplicate structural id: {rid}")
                ids.add(rid)
        for r in self.relations:
            if r.get("from") not in ids or r.get("to") not in ids:
                errors.append(f"relation has unknown endpoint: {r.get('from')} -> {r.get('to')}")
            if str(r.get("evidenceType", "")).lower() == "embedding_similarity" and str(r.get("polarity", "NEUTRAL")).upper() == "OPPOSE":
                errors.append("embedding_similarity cannot directly encode OPPOSE")
        return errors

    def write(self, path: str | Path) -> None:
        errors = self.validate_basic()
        if errors:
            raise ValueError("invalid Forge Intermediate v1: " + "; ".join(errors))
        Path(path).write_text(json.dumps(self.payload(), ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")

if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser(description="Validate a Kira Forge Intermediate v1 document")
    p.add_argument("document")
    args = p.parse_args()
    raw = json.loads(Path(args.document).read_text(encoding="utf-8"))
    doc = ForgeDocument(
        package=raw.get("package", {}), source=raw.get("source", {}), atoms=raw.get("atoms", []),
        vectors=raw.get("vectors", []), relations=raw.get("relations", []), sequences=raw.get("sequences", []),
        macroCandidates=raw.get("macroCandidates", []), traits=raw.get("traits", []),
    )
    errors = doc.validate_basic()
    if raw.get("format") != FORMAT: errors.append(f"format must be {FORMAT}")
    if raw.get("schemaVersion") != SCHEMA_VERSION: errors.append("schemaVersion must be 1")
    if errors:
        print("INVALID")
        for e in errors: print("-", e)
        raise SystemExit(2)
    print("OK", len(doc.atoms), "atoms", len(doc.relations), "relations", len(doc.macroCandidates), "macro candidates")
