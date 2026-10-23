#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/kira-base-v1-test.kirabase"
python "$ROOT/external_oven/kira_base_package_v1.py" build \
  "$ROOT/external_oven/forge_intermediate_v1.example.json" "$TMP"
python "$ROOT/external_oven/kira_base_package_v1.py" verify "$TMP"
python - "$TMP" <<'PY'
import sys, zipfile, struct, json
p=sys.argv[1]
with zipfile.ZipFile(p) as z:
    m=json.loads(z.read('manifest.json'))
    assert m['format']=='kira-base-package-v1'
    assert m['counts']['surfaces']==4
    assert m['counts']['macros']==3
    assert m['highestTier']=='E'
    b=z.read('surfaces.bin')
    magic,version,count=struct.unpack_from('>iii',b,0)
    assert magic==0x4B425346 and version==1 and count==4
print('BasePackageV1SelfTest OK')
PY
