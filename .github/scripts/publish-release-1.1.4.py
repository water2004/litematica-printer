"""Publish a new version; never mutate an earlier release, asset, or tag."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[2]
REPO='water2004/litematica-printer'
TAG='v1.1.4'

def fingerprint(release):
    return {**{k:release.get(k) for k in ('id','tag_name','name','body','draft','prerelease')},
            'assets':sorted([{k:a.get(k) for k in ('id','name','size','digest')}
                             for a in release['assets']],key=lambda a:a['name'])}

def main():
    assert os.environ['GITHUB_REPOSITORY']==REPO
    assert os.environ['GITHUB_REF']=='refs/heads/dory/minimum-dependencies-1.21.x'
    sha=os.environ['GITHUB_SHA']
    run=os.environ['GITHUB_RUN_ID']
    headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],
             'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'}
    base='https://api.github.com/repos/'+REPO
    def api(path,data=None,method='GET',missing=False):
        req=urllib.request.Request(base+path,data=None if data is None else json.dumps(data).encode(),
                                   headers={**headers,'Content-Type':'application/json'},method=method)
        try:
            with urllib.request.urlopen(req,timeout=120) as response:return json.load(response)
        except urllib.error.HTTPError as error:
            if missing and error.code==404:return None
            raise
    snapshot=json.loads((ROOT/'.github/release-1.1.4/preserved-releases.json').read_text())
    output=ROOT/'.ci/release-1.1.4'
    output.mkdir(parents=True,exist_ok=True)
    def verify_preserved():
        for old in snapshot['releases']:
            current=api('/releases/'+str(old['id']))
            assert fingerprint(current)==fingerprint(old),('Earlier release changed',old['tag_name'])
        assert api('/git/ref/tags/v1.1.3')['object']==snapshot['tag']['object'],'Earlier tag changed'
        return {'status':'PASS','releases':len(snapshot['releases']),
                'assets':sum(len(r['assets']) for r in snapshot['releases']),
                'tag':snapshot['tag']['object']}
    before=verify_preserved()
    (output/'preservation-before.json').write_text(json.dumps(before,indent=2))
    versions=json.loads((ROOT/'.github/minimum-dependencies.json').read_text())
    bundle=output/'bundle'
    bundle.mkdir(exist_ok=True)
    printer_files=[]
    proofs=[]
    for mc in versions:
        folder=ROOT/'release-candidates'/('release-1.1.4-'+mc+'-'+sha)
        proof=json.loads((folder/'validation.json').read_text())
        assert (proof['commit'],proof['run'],proof['minecraft'],proof['version'])==(sha,run,mc,'1.1.4')
        assert len(proof['files'])==3
        assert set(proof['profiles'])=={'minimum','current'}
        for profile in proof['profiles'].values():
            assert set(profile['checks'])=={'full','legacy','nochain'}
            assert all(row['status']=='PASS' for row in profile['checks'].values())
        dest=bundle/mc
        dest.mkdir(exist_ok=True)
        for file in proof['files']:
            name=file['name']
            assert Path(name).name==name and name.endswith('.jar')
            path=folder/name
            assert hashlib.sha256(path.read_bytes()).hexdigest()==file['sha256'],name
            with zipfile.ZipFile(path) as z:meta=json.loads(z.read('fabric.mod.json'))
            assert (meta['id'],meta['version'],meta['depends'])==(file['id'],file['version'],file['depends'])
            shutil.copy2(path,dest/name)
            if name=='litematica-printer-1.1.4+'+mc+'.jar':
                assert meta['version']=='1.1.4+'+mc
                assert meta['depends']['fabricloader']=='>='+versions[mc]['loader']
                printer_files.append(path)
        proofs.append(proof)
    assert len(printer_files)==11
    validation=output/'validation-1.1.4.json'
    validation.write_text(json.dumps({'version':'1.1.4','commit':sha,'run':run,
                                     'status':'PASS','clientRuns':66,'versions':proofs},indent=2))
    archive=output/'minimum-dependencies-1.1.4-mc1.21-1.21.10.zip'
    notes=(ROOT/'.github/release-1.1.4/notes.md').read_text(encoding='utf-8')
    notes+='\n1.1.4 本次构建与 66 次兼容性运行：[CI '+run+'](https://github.com/'+REPO+'/actions/runs/'+run+')。源码提交：`'+sha+'`。\n'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        jars=sorted(bundle.glob('*/*.jar'))
        assert len(jars)==33
        for path in jars:z.write(path,path.relative_to(bundle).as_posix())
        z.writestr('README.md',notes)
        z.write(validation,validation.name)
    files=printer_files+[archive,validation]
    checksums=output/'SHA256SUMS.txt'
    checksums.write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in files))
    files.append(checksums)
    expected={p.name:'sha256:'+hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    release=api('/releases/tags/'+TAG,missing=True)
    tag=api('/git/ref/tags/'+TAG,missing=True)
    if release:
        # Only resume our own exact draft; a different/published release is immutable.
        assert release['target_commitish']==sha and release['body']==notes,'Existing 1.1.4 is not this release'
        if not release['draft']:
            assert {a['name']:a.get('digest') for a in release['assets']}==expected
            assert tag and tag['object']['sha']==sha
            verify_preserved()
            print('ALREADY_PUBLISHED_VERIFIED',release['html_url'])
            return
    else:
        assert tag is None,'Existing 1.1.4 tag; refusing to move it'
        release=api('/releases',{'tag_name':TAG,'target_commitish':sha,
            'name':'Litematica Printer 1.1.4 — MC 1.21–1.21.10 最低依赖版',
            'body':notes,'draft':True,'prerelease':False,'make_latest':'false'},method='POST')
    release_id=release['id']
    assert release_id not in {r['id'] for r in snapshot['releases']}
    for path in files:
        assets={a['name']:a for a in api('/releases/'+str(release_id))['assets']}
        assert set(assets)<=set(expected),'Unexpected draft assets; refusing mutation'
        if path.name in assets:
            assert assets[path.name].get('digest')==expected[path.name],'Refusing to overwrite asset'
            continue
        url='https://uploads.github.com/repos/'+REPO+'/releases/'+str(release_id)+'/assets?'+urllib.parse.urlencode({'name':path.name})
        request=urllib.request.Request(url,data=path.read_bytes(),headers={**headers,'Content-Type':'application/octet-stream'},method='POST')
        with urllib.request.urlopen(request,timeout=120) as response:uploaded=json.load(response)
        assert uploaded.get('digest')==expected[path.name]
    assert {a['name']:a.get('digest') for a in api('/releases/'+str(release_id))['assets']}==expected
    verify_preserved()
    # This PATCH is restricted to the newly created release ID, never an old release.
    release=api('/releases/'+str(release_id),{'draft':False,'make_latest':'false'},method='PATCH')
    assert not release['draft'] and release['tag_name']==TAG
    assert api('/git/ref/tags/'+TAG)['object']['sha']==sha
    after=verify_preserved()
    (output/'preservation-after.json').write_text(json.dumps(after,indent=2))
    (output/'published.json').write_text(json.dumps({'status':'PASS','id':release_id,
        'url':release['html_url'],'commit':sha,'run':run,'assets':expected,'preserved':after},indent=2))
    print('PUBLISHED_VERIFIED',release['html_url'])

if __name__=='__main__':main()
