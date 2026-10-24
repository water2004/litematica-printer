"""Collect only the minimum-profile production JARs after both profiles pass."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import sys
import zipfile

ROOT=Path(__file__).resolve().parents[2]
mc=sys.argv[1]
lock=json.loads((ROOT/'.github/minimum-dependencies.json').read_text())[mc]
results=ROOT/'.ci/minimum-results'/mc
assert json.loads((results/'summary.json').read_text())['status']=='PASS'
out=ROOT/'.ci/release-candidate'/mc
out.mkdir(parents=True)
proof={'minecraft':mc,'version':'1.1.4','commit':os.environ['GITHUB_SHA'],
       'run':os.environ['GITHUB_RUN_ID'],'profiles':{},'files':[]}
for profile in ('minimum','current'):
    p=results/profile
    assert json.loads((p/'functional-summary.json').read_text())['status']=='PASS'
    expected_loader=lock['loader'] if profile=='minimum' else '0.19.3'
    checks={}
    for mode in ('full','legacy','nochain'):
        lines=(p/mode/'printer-smoke-result.txt').read_text().splitlines()
        count=16 if mode=='nochain' else (19 if mc=='1.21.10' else 18)
        assert lines[0]=='PASS' and len(lines)-1==count,(mc,profile,mode)
        assert 'Fabric Loader '+expected_loader in (p/mode/'console.log').read_text(errors='replace')
        checks[mode]={'status':'PASS','assertions':count}
    proof['profiles'][profile]={'loader':expected_loader,'checks':checks}
printer=results/'minimum/production'/('litematica-printer-1.1.4+'+mc+'.jar')
paths=[printer]
for repo in ('quickshulker','ChainVeinFabric'):
    jars=[p for p in (ROOT/'.ci/minimum-source'/repo/mc/'build/libs').glob('*.jar')
          if not any(x in p.name for x in ('-sources','-dev'))]
    assert len(jars)==1,jars
    paths+=jars
for path in paths:
    with zipfile.ZipFile(path) as z:
        meta=json.loads(z.read('fabric.mod.json'))
        assert not any('/smokeTest/' in n or n.endswith('PrinterSmokeClient.class') for n in z.namelist())
    if path==printer:
        assert meta['version']=='1.1.4+'+mc
        assert meta['depends']['fabricloader']=='>='+lock['loader']
    shutil.copy2(path,out/path.name)
    proof['files'].append({'name':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                           'id':meta['id'],'version':meta['version'],'depends':meta['depends']})
(out/'validation.json').write_text(json.dumps(proof,indent=2),encoding='utf-8')
print('RELEASE_CANDIDATE_PASS',mc)
