"""Collect pinned tested artifacts; reject every class/resource drift before publishing."""
from pathlib import Path
import concurrent.futures,hashlib,io,json,os,urllib.request,zipfile
ROOT=Path(__file__).resolve().parents[2]
CONFIG=json.loads((ROOT/'.github/republish-1.1.3/publish-config.json').read_text())
MANIFEST=json.loads((ROOT/'.github/republish-1.1.3/manifest.json').read_text())
for row in MANIFEST['files']:row['runtimeFingerprint']=CONFIG['runtimeIdentities'][row['name']]
REPO='water2004/litematica-printer'
TOKEN=os.environ['GH_TOKEN']
OUT=ROOT/'.ci/minimum-release-files';OUT.mkdir(parents=True,exist_ok=True)
def get(path,binary=False):
    req=urllib.request.Request('https://api.github.com/repos/'+REPO+'/'+path,headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json','User-Agent':'Dory-tested-minimum-artifacts'})
    with urllib.request.urlopen(req,timeout=180) as r:raw=r.read()
    return raw if binary else json.loads(raw)
def archive(artifact,run,source):
    meta=get('actions/artifacts/'+str(artifact))
    assert not meta['expired'] and meta['workflow_run']['id']==run and meta['workflow_run']['head_sha']==source,meta
    raw=get('actions/artifacts/'+str(artifact)+'/zip',True)
    if meta.get('digest'):assert meta['digest']=='sha256:'+hashlib.sha256(raw).hexdigest()
    return zipfile.ZipFile(io.BytesIO(raw))
def fp(raw,ignore_build_metadata=False):
    entries={};classes=0
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        meta=json.loads(z.read('fabric.mod.json'))
        assert not any(n.endswith('PrinterSmokeClient.class') for n in z.namelist())
        for n in sorted(x for x in z.namelist() if not x.endswith('/')):
            data=z.read(n)
            if n.upper()=='META-INF/MANIFEST.MF':
                build_keys=('Fabric-Gradle-Version:','Fabric-Loom-Version:','Fabric-Tiny-Remapper-Version:','Fabric-Loader-Version:','Fabric-Mixin-Version:','Fabric-Mixin-Compile-Extensions-Version:')
                data='\n'.join(sorted(x for x in data.decode().replace('\r\n','\n').splitlines() if x and not (ignore_build_metadata and x.startswith(build_keys)))).encode()
            elif n.endswith(('.json','.txt','.accesswidener')):data=data.replace(b'\r\n',b'\n')
            entries[n]=hashlib.sha256(data).hexdigest();classes+=n.endswith('.class')
    return meta,hashlib.sha256('\n'.join(n+'\0'+h for n,h in entries.items()).encode()).hexdigest(),classes
def matches(raw,row):
    meta,fingerprint,count=fp(raw,True)
    return meta==row['metadata'] and fingerprint==row['runtimeFingerprint'] and count==row['classes']
def restamp(raw,mc):
    # Restore the requested 1.1.3 labels only. Every output entry is then checked
    # against the preserved, locally tested 1.1.3 candidate, including all classes.
    out=io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw)) as src,zipfile.ZipFile(out,'w') as dst:
        for info in src.infolist():
            data=src.read(info.filename)
            if info.filename in ('fabric.mod.json','META-INF/MANIFEST.MF'):
                data=data.replace(('1.1.5+'+mc).encode(),('1.1.3+'+mc).encode())
            dst.writestr(info,data)
    return out.getvalue()
for run,source in [(40072764075,'2ed963a057e88a0a3481353bd9bd5f77980d4c0b'),(40090454633,'80b2d225f56038e0d9a40f07c151fc965ab8b108'),(CONFIG['candidateRun'],CONFIG['candidateSource'])]:
    value=get('actions/runs/'+str(run))
    assert value['conclusion']=='success' and value['head_sha']==source,('Pinned test run did not pass',run)
def collect_version(mc):
    rows=[r for r in MANIFEST['files'] if r['minecraft']==mc]
    proof=[];fallback=None
    if mc=='1.21.11':z=archive(CONFIG['candidateArtifact'],CONFIG['candidateRun'],CONFIG['candidateSource'])
    else:z=archive(rows[0]['artifact'],40072764075,'2ed963a057e88a0a3481353bd9bd5f77980d4c0b')
    for row in rows:
        names=[n for n in z.namelist() if n.endswith('/'+row['name']) or n==row['name']]
        names.sort(key=lambda n:('minimum/production/' not in n,'minimum-source/' not in n,n))
        selected=None
        for name in names:
            raw=z.read(name)
            if matches(raw,row):selected=(raw,name,False,row['artifact'] if mc!='1.21.11' else CONFIG['candidateArtifact']);break
        if selected is None and mc!='1.21.11':
            if fallback is None:fallback=archive(CONFIG['restoredArtifacts'][mc],40090454633,'80b2d225f56038e0d9a40f07c151fc965ab8b108')
            for name in fallback.namelist():
                canonical=name.split('/')[-1].replace('litematica-printer-1.1.5+','litematica-printer-1.1.3+')
                if canonical!=row['name']:continue
                raw=fallback.read(name);stamped=row['repository']==REPO
                if stamped:raw=restamp(raw,mc)
                if matches(raw,row):selected=(raw,name,stamped,CONFIG['restoredArtifacts'][mc]);break
        assert selected is not None,('No pinned tested artifact matches every preserved class/resource',mc,row['name'])
        raw,name,stamped,artifact=selected
        path=OUT/mc/row['name'];path.parent.mkdir(exist_ok=True);path.write_bytes(raw)
        proof.append({**{k:row[k] for k in ['minecraft','repository','release','name','metadata','fingerprint','runtimeFingerprint','classes']},'sha256':hashlib.sha256(raw).hexdigest(),'localTestedArchiveSha256':row['sha256'],'sourceArtifact':artifact,'sourceEntry':name,'versionLabelsRestoredOnly':stamped,'buildProvenanceDiffers':fp(raw)[1]!=row['fingerprint']})
    z.close()
    if fallback is not None:fallback.close()
    print('VERIFIED_ALL_ENTRIES',mc,len(proof),flush=True)
    return proof
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    results=list(pool.map(collect_version,MANIFEST['versions']))
files=[r for group in results for r in group]
assert len(files)==36
proof={'status':'PASS','versions':MANIFEST['versions'],'files':files,'candidateRun':CONFIG['candidateRun'],'candidateSource':CONFIG['candidateSource'],'reusedRun':40072764075,'restoredOriginalBehaviorRun':40090454633,'scope':MANIFEST['scope']}
(OUT/'publication-manifest.json').write_text(json.dumps(proof,indent=2))
print('ALL_36_PRODUCTION_CLASS_AND_RESOURCE_GATES_PASS',flush=True)
