"""Reject production source drift against the preserved upstream-behavior baseline."""
from pathlib import Path
import hashlib
import json

ROOT=Path(__file__).resolve().parents[2]
expected=json.loads((ROOT/'.github/release-1.1.5/production-source-sha256.json').read_text())
paths=[]
for folder in [ROOT/'core/src/main/java',*(ROOT/'versions').glob('*/src/main/java')]:
    paths.extend(folder.rglob('*.java'))
paths=[p for p in paths if not p.is_relative_to(ROOT/'versions/1.21.11')]
actual={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_text(encoding='utf-8').encode()).hexdigest() for p in paths}
fingerprint=hashlib.sha256(json.dumps(actual,sort_keys=True,separators=(',',':')).encode()).hexdigest()
assert fingerprint==expected['fingerprint'],'Production source differs from preserved behavior'
assert len(actual)==expected['count']==1431
print('PRODUCTION_SOURCE_BASELINE_PASS',len(actual))
