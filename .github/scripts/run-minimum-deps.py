"""Compile every production component and exercise minimum and current dependencies."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import time
ROOT=Path(__file__).resolve().parents[2]
mc=sys.argv[1]
minimum=json.loads((ROOT/'.github/minimum-dependencies.json').read_text())[mc]
original=json.loads((ROOT/'.github/mapped-ci-inputs.json').read_text())
wrapper=str(Path(os.environ.get('MAPPED_CI_GRADLE', ROOT/'gradlew')).resolve())
env=os.environ.copy()
build_logs=ROOT/'.ci/minimum-build-logs'/mc
build_logs.mkdir(parents=True,exist_ok=True)
def gradle(folder,*tasks):
    log=build_logs/(folder.parent.name+'-'+str(len(list(build_logs.glob('*.log'))))+'.log')
    command=[wrapper,*tasks,'--no-daemon','--max-workers=2','--console=plain','--stacktrace','-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8']
    with log.open('w',encoding='utf-8') as output:
        result=subprocess.run(command,cwd=folder,env=env,stdout=output,stderr=subprocess.STDOUT)
    print(log.read_text(encoding='utf-8',errors='replace'),flush=True)
    result.check_returncode()
# Preserve the original inventory gate before replacing runtime companion inputs.
inventory={}
for key in ('printerRelease','quick','chain'):
    row=original[mc][key]
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(row['url'],headers={'User-Agent':'Litematica-production-CI'}),timeout=120) as response:
                digest=hashlib.sha256(response.read()).hexdigest()
            assert digest==row['sha256'],(key,row['name'],'published artifact hash mismatch')
            break
        except Exception:
            if attempt==3:raise
            time.sleep(2*(attempt+1))
    inventory[key]={'name':row['name'],'sha256':digest}
(build_logs/'published-inventory.json').write_text(json.dumps(inventory,indent=2),encoding='utf-8')
companions={}
for repo,sha in minimum['companions'].items():
    folder=ROOT/'.ci/minimum-source'/repo/mc
    folder.mkdir(parents=True,exist_ok=True)
    subprocess.run(['git','init',str(folder)],check=True)
    subprocess.run(['git','-C',str(folder),'fetch','--depth=1','https://github.com/water2004/'+repo+'.git',sha],check=True)
    subprocess.run(['git','-C',str(folder),'checkout','--detach','FETCH_HEAD'],check=True)
    overlay=ROOT/'.github/minimum-overlays'/mc/repo
    for file in overlay.rglob('*'):
        if file.is_file():
            destination=folder/file.relative_to(overlay);destination.parent.mkdir(exist_ok=True,parents=True);shutil.copy2(file,destination)
    gradle(folder,'remapJar','test','runGameTest')
    jars=[p for p in (folder/'build/libs').glob('*.jar') if not any(x in p.name for x in ('sources','-dev'))]
    assert len(jars)==1,jars
    jar=jars[0]
    companions['quick' if repo=='quickshulker' else 'chain']={'name':jar.name,'url':jar.as_uri(),'sha256':hashlib.sha256(jar.read_bytes()).hexdigest()}
gradle(ROOT,'-Ptargets='+mc,':core:test',':'+mc+':remapJar',':'+mc+':remapSmokeJar')
failures=[]
for profile in ('minimum','current'):
    inputs=json.loads(json.dumps(original))
    inputs[mc].update(companions)
    if profile=='minimum':inputs[mc]['dependencies']=minimum['dependencies']
    (ROOT/'.github/mapped-ci-inputs.json').write_text(json.dumps(inputs,indent=2),encoding='utf-8')
    if profile=='current':
        env['MAPPED_CI_LOADER']='0.19.3'
    else:
        env['MAPPED_CI_LOADER']=minimum['loader']
    result=subprocess.run([sys.executable,str(ROOT/'.github/scripts/run-mapped-ci.py'),mc,'functional'],cwd=ROOT,env=env)
    source=ROOT/'.ci/results'/mc
    expected_loader=env['MAPPED_CI_LOADER']
    for mode in ('full','legacy','nochain'):
        log=source/mode/'console.log'
        console=log.read_text(encoding='utf-8',errors='replace') if log.exists() else ''
        if ('Fabric Loader '+expected_loader) not in console:
            failures.append({'profile':profile,'mode':mode,'error':'actual loader mismatch','expectedLoader':expected_loader})
    if result.returncode:failures.append({'profile':profile,'exit':result.returncode})
    destination=ROOT/'.ci/minimum-results'/mc/profile
    destination.parent.mkdir(exist_ok=True,parents=True)
    shutil.move(source,destination)
    (destination/'dependency-profile.json').write_text(json.dumps({'profile':profile,'expectedLoader':expected_loader,'inputs':inputs[mc]},indent=2),encoding='utf-8')
    # Retain the exact production build exercised with this dependency profile.
    jars=[p for p in (ROOT/'versions'/mc/'build/libs').glob('*.jar')
          if not any(x in p.name for x in ('-sources','-dev','-smoke-','-audit-'))]
    assert len(jars)==1,jars
    production=destination/'production'
    production.mkdir()
    shutil.copy2(jars[0],production/jars[0].name)
(ROOT/'.ci/minimum-results'/mc/'summary.json').write_text(json.dumps({'status':'FAIL' if failures else 'PASS','failures':failures},indent=2),encoding='utf-8')
if failures:
    print('MINIMUM_AND_CURRENT_FAIL',mc,json.dumps(failures),flush=True)
    raise SystemExit(1)
print('MINIMUM_AND_CURRENT_PASS',mc,flush=True)
