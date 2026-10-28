"""Verify unpublished minimum-dependency candidates before an append-only release."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,zipfile
ROOT=Path(__file__).resolve().parents[2]
config=json.loads((ROOT/'.github/republish-1.1.3/manifest.json').read_text())
sources=json.loads((ROOT/'.github/republish-1.1.3/sources.json').read_text())
mc='1.21.11'
out=ROOT/'.ci/republish-1.1.3';out.mkdir(parents=True,exist_ok=True)
wrapper=str(ROOT/'gradlew')
def gradle(folder,*args):
    subprocess.run([wrapper,*args,'--no-daemon','--max-workers=2','--console=plain','--stacktrace','-Dorg.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8'],cwd=folder,check=True)
def jar(folder):
    paths=[p for p in (folder/'build/libs').glob('*.jar') if not any(x in p.name for x in ['sources','-dev','smoke','audit'])]
    assert len(paths)==1,paths
    return paths[0]
def fingerprint(path):
    entries={};classes=0
    with zipfile.ZipFile(path) as z:
        for n in sorted(x for x in z.namelist() if not x.endswith('/')):
            raw=z.read(n)
            if n.upper()=='META-INF/MANIFEST.MF':raw='\n'.join(sorted(x for x in raw.decode().replace('\r\n','\n').splitlines() if x)).encode()
            elif n.endswith(('.json','.txt','.accesswidener')):raw=raw.replace(b'\r\n',b'\n')
            entries[n]=hashlib.sha256(raw).hexdigest();classes+=n.endswith('.class')
    return hashlib.sha256('\n'.join(n+'\0'+h for n,h in entries.items()).encode()).hexdigest(),classes
paths={}
for repo,source in sources.items():
    folder=ROOT/'.ci/source-1.21.11'/repo
    subprocess.run(['git','init',str(folder)],check=True)
    subprocess.run(['git','-C',str(folder),'fetch','--depth=1','https://github.com/water2004/'+repo+'.git',source['sha']],check=True)
    subprocess.run(['git','-C',str(folder),'checkout','--detach','FETCH_HEAD'],check=True)
    assert subprocess.check_output(['git','-C',str(folder),'rev-parse','HEAD'],text=True).strip()==source['sha']
    gradle(folder,'remapJar','test','runGameTest')
    paths[repo]=jar(folder)
gradle(ROOT,'-Ptargets='+mc,':core:test',':'+mc+':remapJar',':'+mc+':remapSmokeJar')
paths['litematica-printer']=jar(ROOT/'versions'/mc)
proof={'minecraft':mc,'status':'PASS','source':os.environ['GITHUB_SHA'],'run':os.environ['GITHUB_RUN_ID'],'files':[]}
for row in (r for r in config['files'] if r['minecraft']==mc):
    path=paths[row['repository'].split('/')[1]]
    fp,count=fingerprint(path)
    assert fp==row['fingerprint'] and count==row['classes'],('Local/CI class or resource drift',path.name,fp,row['fingerprint'])
    with zipfile.ZipFile(path) as z:assert json.loads(z.read('fabric.mod.json'))==row['metadata']
    shutil.copy2(path,out/path.name)
    proof['files'].append({'name':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'fingerprint':fp,'classes':count})
inputs=json.loads((ROOT/'.github/mapped-ci-inputs.json').read_text())
def input_row(path):return {'name':path.name,'url':path.as_uri(),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
inputs[mc]={'dependencies':config['versions'][mc]['dependencies'],'printerRelease':input_row(paths['litematica-printer']),'quick':input_row(paths['quickshulker']),'chain':input_row(paths['ChainVeinFabric']),'legacy':config['legacy12111']}
(ROOT/'.github/mapped-ci-inputs.json').write_text(json.dumps(inputs,indent=2))
env=os.environ.copy();env['MAPPED_CI_LOADER']=config['versions'][mc]['loader']
subprocess.run(['xvfb-run','-a',sys.executable,str(ROOT/'.github/scripts/candidate-functional.py'),mc,'functional'],cwd=ROOT,env=env,check=True)
for mode,n in [('full',19),('legacy',19),('nochain',16)]:
    p=ROOT/'.ci/results'/mc/mode
    lines=(p/'printer-smoke-result.txt').read_text().splitlines()
    assert lines[0]=='PASS' and len(lines)==n+1
    assert 'Fabric Loader '+env['MAPPED_CI_LOADER'] in (p/'console.log').read_text(errors='replace')
proof['clientAssertions']=54
(out/'validation.json').write_text(json.dumps(proof,indent=2))
print('MC_1_21_11_MINIMUM_CANDIDATE_PASS',54)
