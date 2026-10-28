"""Append only verified minimum-compatible JARs to the user's named existing releases."""
from pathlib import Path
import hashlib,json,os,urllib.parse,urllib.request,zipfile
ROOT=Path.cwd()
CONFIG=json.loads((ROOT/'.github/republish-1.1.3/publish-settings.json').read_text())
REPO=os.environ['GITHUB_REPOSITORY'];TOKEN=os.environ['GH_TOKEN']
assert REPO==CONFIG['repository'] and os.environ['GITHUB_REF'].startswith('refs/heads/dory/')
FILES=ROOT/'minimum-release-files'
MANIFEST=json.loads((FILES/'publication-manifest.json').read_text())
assert MANIFEST['status']=='PASS' and len(MANIFEST['files'])==36 and len(MANIFEST['versions'])==12
rows=[r for r in MANIFEST['files'] if r['repository']==REPO]
assert len(rows)==12 and {r['release'] for r in rows}==set(map(int,CONFIG['releaseTags']))
def api(path,method='GET',data=None):
    request=urllib.request.Request('https://api.github.com/repos/'+REPO+'/'+path,data=json.dumps(data).encode() if data is not None else None,method=method,headers={'Authorization':'Bearer '+TOKEN,'Accept':'application/vnd.github+json','Content-Type':'application/json','User-Agent':'Dory-append-verified-minimum'})
    with urllib.request.urlopen(request,timeout=120) as response:return json.load(response)
def refs():
    return {name:api('git/ref/heads/'+name)['object']['sha'] for name in CONFIG['protectedRefs']}
assert refs()==CONFIG['protectedRefs'],'Protected branch changed before publication; inspect concurrent changes'
for row in rows:
    path=FILES/row['minecraft']/row['name']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256']
    with zipfile.ZipFile(path) as z:assert json.loads(z.read('fabric.mod.json'))==row['metadata']
before={str(rid):api('releases/'+str(rid)) for rid in sorted({r['release'] for r in rows})}
for rid,release in before.items():
    assert release['tag_name']==CONFIG['releaseTags'][rid] and not release['draft']
    for row in (r for r in rows if str(r['release'])==rid):
        matches=[a for a in release['assets'] if a['name']==row['name']]
        assert not matches or (len(matches)==1 and matches[0].get('digest')=='sha256:'+row['sha256']),('Existing canonical asset differs; never overwrite',row['name'])
uploaded=[]
for row in rows:
    release=api('releases/'+str(row['release']))
    matches=[a for a in release['assets'] if a['name']==row['name']]
    if matches:asset=matches[0]
    else:
        raw=(FILES/row['minecraft']/row['name']).read_bytes()
        url='https://uploads.github.com/repos/'+REPO+'/releases/'+str(row['release'])+'/assets?name='+urllib.parse.quote(row['name'])
        request=urllib.request.Request(url,data=raw,method='POST',headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/java-archive','User-Agent':'Dory-append-verified-minimum'})
        with urllib.request.urlopen(request,timeout=180) as response:asset=json.load(response)
    assert asset.get('digest')=='sha256:'+row['sha256']
    with urllib.request.urlopen(urllib.request.Request(asset['browser_download_url'],headers={'User-Agent':'Dory-public-byte-verification'}),timeout=120) as response:raw=response.read()
    assert hashlib.sha256(raw).hexdigest()==row['sha256'],'Public asset hash mismatch'
    uploaded.append({**row,'assetId':asset['id'],'url':asset['browser_download_url']})
    print('PUBLIC_SHA256_PASS',row['name'],flush=True)
lines=['## Minecraft 1.21 系列：最低稳定依赖','',
       '以下文件覆盖 1.21 至 1.21.11，共 12 个游戏版本。打印机统一为 1.1.3。Java 21。',
       '表格列出三个模组组合的最低稳定依赖。QuickShulker 单独使用不要求 MaLiLib 或 Litematica。','',
       '| Minecraft | 最低 Fabric Loader | 最低 Fabric API | MaLiLib | Litematica | 下载 |',
       '| --- | --- | --- | --- | --- | --- |']
for mc,lock in MANIFEST['versions'].items():
    row=next(r for r in uploaded if r['minecraft']==mc)
    v=lock['versions']
    lines.append('| '+ ' | '.join([mc,lock['loader'],v['fabric-api'],v['malilib'],v['litematica'],'['+row['name']+']('+row['url']+')'])+' |')
lines+=['','验证：既有 11 个版本复用此前测试证据；每个文件的全部类文件与运行资源必须匹配本地测试版本。',
        '1.21.11 另外通过单元测试、服务端 GameTest，以及三种真实客户端组合的 54 项检查。',
        '[1.21.11 固定提交 CI](https://github.com/water2004/litematica-printer/actions/runs/'+str(MANIFEST['candidateRun'])+')。']
if REPO.endswith('/litematica-printer'):
    lines+=['','兼容性验证范围为最低依赖与功能组合。1.21.7 和 1.21.9 的既有丢包错块问题仍保留；本次文件不包含实验性预测改动。']
start='<!-- dory-minimum-1.21-family -->';end='<!-- /dory-minimum-1.21-family -->'
section=start+'\n'+'\n'.join(lines)+'\n'+end
notes={}
for rid in before:
    release=api('releases/'+rid);body=release.get('body') or ''
    if start in body:
        assert body.count(start)==1 and body.count(end)==1
        a=body.index(start);b=body.index(end,a)+len(end)
        newbody=body[:a]+section+body[b:]
    else:newbody=body+('\n\n' if body else '')+section+'\n'
    api('releases/'+rid,'PATCH',{'body':newbody})
    after=api('releases/'+rid)
    assert after['body']==newbody
    actual={a['id']:(a['name'],a.get('digest')) for a in after['assets']}
    assert all(actual.get(a['id'])==(a['name'],a.get('digest')) for a in before[rid]['assets']),'An existing author asset changed'
    assert all(after[k]==before[rid][k] for k in ['tag_name','name','draft','prerelease','target_commitish'])
    notes[rid]={'preservedAssets':len(before[rid]['assets']),'tableRows':12,'url':after['html_url']}
assert refs()==CONFIG['protectedRefs'],'Protected branch changed during publication'
out=ROOT/'.ci/release-publication';out.mkdir(exist_ok=True,parents=True)
(out/'verification.json').write_text(json.dumps({'status':'PASS','repository':REPO,'files':uploaded,'releases':notes,'protectedRefs':refs()},indent=2))
print('APPEND_ONLY_PUBLICATION_PASS',REPO,len(uploaded),flush=True)
