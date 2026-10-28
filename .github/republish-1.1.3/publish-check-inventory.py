"""Fail on missing public MC1.21 files, wrong digests, incomplete tables or changed author assets."""
from pathlib import Path
import json,os,urllib.request
ROOT=Path(__file__).resolve().parents[2]
CONFIG=json.loads((ROOT/'.github/republish-1.1.3/publish-inventory.json').read_text())
token=os.environ.get('GH_TOKEN')
def get(repo,path):
    headers={'Accept':'application/vnd.github+json','User-Agent':'Dory-public-inventory-gate'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request('https://api.github.com/repos/'+repo+'/'+path,headers=headers)
    with urllib.request.urlopen(request,timeout=120) as r:return json.load(r)
assert len(CONFIG['files'])==36 and len(CONFIG['versions'])==12
releases={}
for row in CONFIG['files']:
    key=(row['repository'],row['release'])
    if key not in releases:releases[key]=get(row['repository'],'releases/'+str(row['release']))
    release=releases[key];assert not release['draft']
    found=[a for a in release['assets'] if a['name']==row['name']]
    assert len(found)==1 and found[0].get('digest')=='sha256:'+row['sha256'],('Missing or wrong public artifact',row['name'])
for expected in CONFIG['originalReleases']:
    current=releases[(expected['repository'],expected['id'])]
    assert all(current[k]==expected[k] for k in ['tag_name','name','draft','prerelease','target_commitish'])
    actual={a['id']:(a['name'],a.get('digest')) for a in current['assets']}
    assert all(actual.get(a['id'])==(a['name'],a.get('digest')) for a in expected['assets'])
    assert current['body'].startswith(expected['body'] or '')
    table=[line for line in current['body'].splitlines() if line.startswith('| 1.21')]
    assert len(table)==12
    parsed={line.split('|')[1].strip():[cell.strip() for cell in line.split('|')[2:]] for line in table}
    assert set(parsed)==set(CONFIG['versions'])
    for mc,v in CONFIG['versions'].items():
        assert parsed[mc][:4]==[v['loader'],v['versions']['fabric-api'],v['versions']['malilib'],v['versions']['litematica']]
for repo,refs in CONFIG['protectedRefs'].items():
    for name,sha in refs.items():assert get(repo,'git/ref/heads/'+name)['object']['sha']==sha,('Protected branch changed',repo,name)
out=ROOT/'.ci/public-inventory';out.mkdir(exist_ok=True,parents=True)
result={'status':'PASS','minecraftVersions':12,'publicJars':36,'dependencyTables':4,'tableRows':48,'preservedOriginalTargetAssets':sum(len(r['assets']) for r in CONFIG['originalReleases']),'protectedRefs':CONFIG['protectedRefs']}
(out/'verification.json').write_text(json.dumps(result,indent=2))
print('PUBLIC_MINIMUM_INVENTORY_PASS',json.dumps(result),flush=True)
