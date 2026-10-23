#!/usr/bin/env python3
"""Kira BASE Package v1 builder/validator (dependency-free).

Usage:
  python kira_base_package_v1.py build forge_intermediate.json output.kirabase
  python kira_base_package_v1.py verify output.kirabase

Forge Intermediate remains the teacher-neutral build contract. .kirabase is the compact,
checksummed runtime artefact mounted by Kira v0.9.8+.
"""
from __future__ import annotations
import argparse, hashlib, json, math, struct, sys, time, zipfile
from pathlib import Path
from typing import Any

FORMAT = "kira-base-package-v1"
SCHEMA_VERSION = 1
FORGE_FORMAT = "kira-forge-intermediate-v1"
FORGE_SCHEMA_VERSION = 1
BASE_MIN = 1_500_000_000
BASE_MAX = 2_147_482_623
BIN_VERSION = 1
MAGIC_SURFACE = 0x4B425346
MAGIC_MACRO = 0x4B424D41
MAGIC_RELATION = 0x4B425245
MAGIC_TRAIT = 0x4B425452
MAGIC_INDEX = 0x4B424958

FILES = ("surfaces.bin", "macros.bin", "relations.bin", "traits.bin", "index.bin")


def norm(s: str) -> str:
    return " ".join(str(s).strip().lower().split())


def fnv1a64(text: str) -> int:
    h = 14695981039346656037
    for b in text.encode("utf-8"):
        h ^= b
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return h


def stable_id(namespace: str, semantic_key: str) -> int:
    h = fnv1a64(f"kira-forge-v1|{namespace.strip().lower()}|{semantic_key}") & 0x7FFFFFFFFFFFFFFF
    return BASE_MIN + (h % (BASE_MAX - BASE_MIN + 1))


def w_i32(buf: bytearray, n: int): buf += struct.pack(">i", int(n))
def w_i64(buf: bytearray, n: int): buf += struct.pack(">q", int(n))
def w_f64(buf: bytearray, x: float): buf += struct.pack(">d", float(x))
def w_u8(buf: bytearray, n: int): buf += struct.pack(">B", int(n) & 0xFF)
def w_str(buf: bytearray, s: str):
    b = str(s).encode("utf-8")
    w_i32(buf, len(b)); buf += b


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_forge(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if raw.get("format") != FORGE_FORMAT: raise ValueError(f"format must be {FORGE_FORMAT}")
    if int(raw.get("schemaVersion", 0)) != FORGE_SCHEMA_VERSION: raise ValueError("Forge schemaVersion must be 1")
    pack = raw.get("package") or {}
    source = raw.get("source") or {}
    if not pack.get("id") or int(pack.get("version", 0)) <= 0 or not pack.get("namespace"):
        raise ValueError("package.id/version/namespace required")
    for k in ("id", "adapterId", "adapterVersion", "modality"):
        if not source.get(k): raise ValueError(f"source.{k} required")
    return raw


def compile_forge(raw: dict[str, Any]) -> dict[str, Any]:
    pack = raw["package"]; source = raw["source"]; namespace = str(pack["namespace"])
    atoms = list(raw.get("atoms") or [])
    seqs = list(raw.get("sequences") or [])
    macros = list(raw.get("macroCandidates") or [])
    relations = list(raw.get("relations") or [])
    traits = list(raw.get("traits") or [])
    atom_by = {str(x.get("id", "")): x for x in atoms}
    seq_by = {str(x.get("id", "")): x for x in seqs}
    macro_by = {str(x.get("id", "")): x for x in macros}
    all_ids = list(atom_by) + list(seq_by) + list(macro_by)
    if any(not x for x in all_ids) or len(set(all_ids)) != len(all_ids): raise ValueError("structural IDs missing/duplicated")

    key_memo: dict[str, str] = {}
    visiting: set[str] = set()
    def skey(i: str) -> str:
        if i in key_memo: return key_memo[i]
        if i in visiting: raise ValueError(f"cycle at {i}")
        visiting.add(i)
        if i in atom_by:
            a=atom_by[i]
            k=str(a.get("stableKey") or "").strip() or f"atom|{norm(a.get('modality',''))}|{norm(a.get('kind','OTHER'))}|{norm(a.get('canonical',''))}"
        elif i in seq_by:
            s=seq_by[i]
            k=str(s.get("stableKey") or "").strip() or f"sequence|{norm(s.get('modality',''))}|" + ">".join(skey(str(m)) for m in s.get("members", []))
        elif i in macro_by:
            m=macro_by[i]
            k=str(m.get("stableKey") or "").strip() or f"macro|{norm(m.get('modality',''))}|" + ">".join(skey(str(x)) for x in m.get("members", []))
        else: raise ValueError(f"unknown structural id {i}")
        visiting.remove(i); key_memo[i]=k; return k

    ids: dict[str,int] = {}
    used: dict[int,str] = {}
    for i in sorted(all_ids):
        sid=stable_id(namespace, skey(i))
        if sid in used and used[sid] != i: raise ValueError(f"stable ID collision: {used[sid]} <-> {i}")
        ids[i]=sid; used[sid]=i

    depth_memo: dict[str,int] = {}
    def depth(i: str) -> int:
        if i in depth_memo: return depth_memo[i]
        if i in atom_by or i in seq_by: d=0
        elif i in macro_by:
            members=[str(x) for x in macro_by[i].get("members", [])]
            if len(members)<2: raise ValueError(f"macro {i} needs >=2 members")
            d=1+max((depth(x) for x in members), default=0)
        else: raise ValueError(f"unknown member {i}")
        depth_memo[i]=d; return d
    for i in macro_by: depth(i)

    def fam(mod: str)->str:
        m=norm(mod)
        if m.startswith("text") or m=="mind": return "TEXT"
        if m.startswith("audio") or m.startswith("music"): return "AUDIO"
        if m.startswith("vision") or m.startswith("video") or m.startswith("camera"): return "VISION"
        if m.startswith("crossmodal") or m.startswith("joint"): return "CROSSMODAL"
        if m.startswith("forge"): return "FORGE"
        return "OTHER"

    for mid,m in macro_by.items():
        pd=depth(mid); pf=fam(str(m.get("modality","")))
        child_depths={depth(str(x)) for x in m.get("members", [])}
        child_fams={fam(str((atom_by.get(str(x)) or seq_by.get(str(x)) or macro_by.get(str(x)) or {}).get("modality",""))) for x in m.get("members", [])}
        if pf != "CROSSMODAL":
            if any(cf != pf for cf in child_fams if cf != "OTHER"): raise ValueError(f"modality mix in {mid}")
            if pd > 1 and child_depths != {pd-1}: raise ValueError(f"tier gap in {mid}")
        elif any(depth(str(x)) >= pd for x in m.get("members", [])): raise ValueError(f"invalid crossmodal depth in {mid}")

    surface_rows=[]
    for a in atoms:
        i=str(a["id"])
        surface_rows.append((ids[i], i, str(a.get("modality","")), "atom:"+str(a.get("kind","OTHER")).lower(), str(a.get("canonical","")), float(a.get("confidence",1.0)), int(a.get("support",1))))
    for s in seqs:
        i=str(s["id"])
        surface_rows.append((ids[i], i, str(s.get("modality","")), "sequence", "", float(s.get("confidence",1.0)), int(s.get("support",1))))

    macro_rows=[]
    for m in sorted(macros, key=lambda x:(depth(str(x["id"])), str(x["id"]))):
        i=str(m["id"]); d=depth(i); members=[ids[str(x)] for x in m.get("members",[])]
        macro_rows.append((ids[i], d, str(m.get("modality","")), members, int(m.get("support",1)), float(m.get("confidence",1.0)), i))

    polmap={"SUPPORT":0,"OPPOSE":1,"NEUTRAL":2}
    rel_rows=[]
    for r in relations:
        f=str(r.get("from","")); t=str(r.get("to",""))
        if f not in ids or t not in ids: raise ValueError(f"relation unknown endpoint {f}->{t}")
        strength=float(r.get("strength",0.0)); conf=float(r.get("confidence",1.0))
        if not math.isfinite(strength) or not 0<=strength<=1 or not math.isfinite(conf) or not 0<=conf<=1: raise ValueError("relation score out of range")
        polarity=str(r.get("polarity","NEUTRAL")).upper(); evidence=str(r.get("evidenceType","explicit"))
        if evidence.lower()=="embedding_similarity" and polarity=="OPPOSE": raise ValueError("embedding_similarity cannot be OPPOSE")
        row=(ids[f],ids[t],str(r.get("kind","")),strength,conf,polmap.get(polarity,2),evidence)
        rel_rows.append(row)
        if str(r.get("direction","DIRECTED")).upper()=="BIDIRECTIONAL": rel_rows.append((row[1],row[0],*row[2:]))

    trait_rows=[]
    for t in traits:
        owner=str(t.get("ownerId",""))
        if owner not in ids: raise ValueError(f"trait owner unknown {owner}")
        has_num="number" in t; has_text="text" in t
        if has_num==has_text: raise ValueError("trait needs exactly one value")
        trait_rows.append((ids[owner],str(t.get("key","")), t.get("number") if has_num else None, t.get("text") if has_text else None, float(t.get("confidence",1.0))))

    index_rows=[]
    for sid, ext, mod, kind, canonical, conf, support in surface_rows:
        k=norm(canonical)
        if k: index_rows.append((k,sid))
    index_rows.sort()
    highest=max((r[1] for r in macro_rows), default=0)
    tiers=["ATOM","F","E","D","C","B","A","S","SS","SSS","Ω"]
    highest_tier = tiers[highest] if highest < len(tiers) else f"Ω+{highest-10}"
    return dict(pack=pack,source=source,surfaces=surface_rows,macros=macro_rows,relations=rel_rows,traits=trait_rows,index=index_rows,highestDepth=highest,highestTier=highest_tier)


def encode(comp: dict[str,Any]) -> dict[str,bytes]:
    out: dict[str,bytes]={}
    b=bytearray(); w_i32(b,MAGIC_SURFACE); w_i32(b,BIN_VERSION); w_i32(b,len(comp["surfaces"]))
    for sid,ext,mod,kind,canon,conf,support in comp["surfaces"]:
        w_i32(b,sid); w_str(b,ext); w_str(b,mod); w_str(b,kind); w_str(b,canon); w_f64(b,conf); w_i64(b,support)
    out["surfaces.bin"]=bytes(b)
    b=bytearray(); w_i32(b,MAGIC_MACRO); w_i32(b,BIN_VERSION); w_i32(b,len(comp["macros"]))
    for sid,level,mod,members,weight,conf,ext in comp["macros"]:
        w_i32(b,sid); w_i32(b,level); w_str(b,mod); w_i32(b,len(members)); [w_i32(b,x) for x in members]; w_i64(b,weight); w_f64(b,conf); w_str(b,ext)
    out["macros.bin"]=bytes(b)
    b=bytearray(); w_i32(b,MAGIC_RELATION); w_i32(b,BIN_VERSION); w_i32(b,len(comp["relations"]))
    for f,t,kind,strength,conf,pol,evidence in comp["relations"]:
        w_i32(b,f); w_i32(b,t); w_str(b,kind); w_f64(b,strength); w_f64(b,conf); w_u8(b,pol); w_str(b,evidence)
    out["relations.bin"]=bytes(b)
    b=bytearray(); w_i32(b,MAGIC_TRAIT); w_i32(b,BIN_VERSION); w_i32(b,len(comp["traits"]))
    for owner,key,num,text,conf in comp["traits"]:
        w_i32(b,owner); w_str(b,key)
        if num is not None: w_u8(b,1); w_f64(b,float(num))
        else: w_u8(b,2); w_str(b,str(text))
        w_f64(b,conf)
    out["traits.bin"]=bytes(b)
    b=bytearray(); w_i32(b,MAGIC_INDEX); w_i32(b,BIN_VERSION); w_i32(b,len(comp["index"]))
    for key,sid in comp["index"]: w_str(b,key); w_i32(b,sid)
    out["index.bin"]=bytes(b)
    return out


def build(src: Path, dest: Path):
    raw=load_forge(src); comp=compile_forge(raw); payloads=encode(comp)
    hashes={name:sha256(payloads[name]) for name in sorted(FILES)}
    digest_input="".join(f"{name}:{hashes[name]}\n" for name in sorted(FILES)).encode("utf-8")
    payload_digest=sha256(digest_input)
    p=comp["pack"]; s=comp["source"]
    manifest={
        "format":FORMAT,"schemaVersion":SCHEMA_VERSION,"forgeSchemaVersion":FORGE_SCHEMA_VERSION,
        "package":{"id":p["id"],"version":int(p["version"]),"namespace":p["namespace"]},
        "source":{k:s.get(k,"") for k in ("id","version","adapterId","adapterVersion","modality","modelFamily","revision","license","fingerprint")},
        "createdAtMs":int(time.time()*1000),
        "counts":{"surfaces":len(comp["surfaces"]),"macros":len(comp["macros"]),"relations":len(comp["relations"]),"traits":len(comp["traits"]),"indexRows":len(comp["index"])},
        "highestDepth":comp["highestDepth"],"highestTier":comp["highestTier"],"payloadDigest":payload_digest,
    }
    checks={"algorithm":"SHA-256","files":hashes,"payloadDigest":payload_digest}
    dest.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(dest,"w",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        z.writestr("manifest.json",json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2))
        z.writestr("checksums.json",json.dumps(checks,sort_keys=True,indent=2))
        for name in FILES: z.writestr(name,payloads[name])
    print(f"BUILT {dest} · {len(comp['surfaces'])} surfaces · {len(comp['macros'])} macros · {len(comp['relations'])} relations · {comp['highestTier']}")


def verify(path: Path):
    with zipfile.ZipFile(path,"r") as z:
        names=set(z.namelist()); required={"manifest.json","checksums.json",*FILES}
        missing=required-names
        if missing: raise ValueError("missing entries: "+", ".join(sorted(missing)))
        manifest=json.loads(z.read("manifest.json")); checks=json.loads(z.read("checksums.json"))
        if manifest.get("format")!=FORMAT or int(manifest.get("schemaVersion",0))!=SCHEMA_VERSION: raise ValueError("bad package schema")
        hashes={name:sha256(z.read(name)) for name in sorted(FILES)}
        for name,d in hashes.items():
            if checks.get("files",{}).get(name)!=d: raise ValueError(f"checksum mismatch: {name}")
        digest=sha256("".join(f"{name}:{hashes[name]}\n" for name in sorted(FILES)).encode())
        if checks.get("payloadDigest")!=digest or manifest.get("payloadDigest")!=digest: raise ValueError("payload digest mismatch")
        print("OK",manifest["package"]["id"],"v"+str(manifest["package"]["version"]),manifest.get("counts"),manifest.get("highestTier"),digest[:16])


def main():
    ap=argparse.ArgumentParser(); sub=ap.add_subparsers(dest="cmd",required=True)
    b=sub.add_parser("build"); b.add_argument("forge"); b.add_argument("output")
    v=sub.add_parser("verify"); v.add_argument("package")
    a=ap.parse_args()
    try:
        if a.cmd=="build": build(Path(a.forge),Path(a.output))
        else: verify(Path(a.package))
    except Exception as e:
        print("ERROR",e,file=sys.stderr); raise SystemExit(2)
if __name__=="__main__": main()
