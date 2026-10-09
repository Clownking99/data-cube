"""Read-only tracked-file cost inventory. No builds, user data, or forbidden paths."""
import argparse, collections, datetime, hashlib, json, re, subprocess
from pathlib import Path

ROOTS=('src','test','buildSrc','docs','scripts')
EXCLUDES=(':(exclude).testagent',':(exclude).testagent/**',':(exclude)**/.testagent/**')
def allowed(path):
    return bool(path and not re.match(r'^[A-Za-z]:|^[/\\]',path) and '\\' not in path
                and not any(x in ('..','.testagent') for x in path.split('/'))
                and path.split('/')[0] in ROOTS)
def git(repo,*args,input=None):
    return subprocess.run(['git',*args],cwd=repo,input=input,capture_output=True,check=True).stdout
def collect(repo):
    head=git(repo,'rev-parse','HEAD').decode().strip()
    git(repo,'diff','--quiet','HEAD','--',*ROOTS,*EXCLUDES)
    raw=git(repo,'ls-files','--stage','-z','--',*ROOTS,*EXCLUDES)
    entries=[]
    for part in raw.split(b'\0'):
        if not part:continue
        meta,path=part.decode('utf-8').split('\t',1);mode,oid,stage=meta.split()
        if stage!='0' or not allowed(path):raise ValueError('Unapproved tracked path or merge stage')
        entries.append(dict(path=path,oid=oid,mode=mode))
    ids=sorted({x['oid'] for x in entries})
    sizes={}
    for line in git(repo,'cat-file','--batch-check=%(objectname) %(objecttype) %(objectsize)',input=('\n'.join(ids)+'\n').encode()).decode().splitlines():
        oid,kind,size=line.split();assert kind=='blob';sizes[oid]=int(size)
    for x in entries:x['bytes']=sizes[x['oid']]
    def aggregate(rows,key):
        values=collections.defaultdict(lambda:dict(files=0,bytes=0))
        for x in rows:v=values[key(x)];v['files']+=1;v['bytes']+=x['bytes']
        return [dict(group=k,**v) for k,v in sorted(values.items(),key=lambda kv:(-kv[1]['bytes'],kv[0]))]
    evidence=[x for x in entries if x['path'].startswith('docs/superpowers/verification/evidence/')]
    dup=collections.defaultdict(list)
    for x in evidence:dup[x['oid']].append(x)
    repeated=[dict(oid=k,copies=len(v),bytesEach=sizes[k],repeatedLogicalBytes=(len(v)-1)*sizes[k],samplePaths=[x['path'] for x in v[:3]]) for k,v in dup.items() if len(v)>1]
    scriptRows=[x for x in evidence if Path(x['path']).suffix.lower() in ('.py','.ps1','.gradle')]
    scriptGroups=collections.Counter(x['oid'] for x in scriptRows)
    java=[]
    for x in entries:
        if x['path'].split('/')[0] not in ('src','test','buildSrc') or not x['path'].endswith('.java'):continue
        p=repo/x['path']
        if p.is_symlink():raise ValueError('Source symlink is outside this inventory contract')
        text=p.read_text(encoding='utf-8-sig')
        internal=re.findall(r'^import\s+(?:static\s+)?(com\.datacube\.[\w.*]+);',text,re.M)
        java.append(dict(path=x['path'],lines=len(text.splitlines()),nonblank=sum(bool(s.strip()) for s in text.splitlines()),internalImports=len(internal),referencedPackages=sorted({s.rsplit('.',1)[0] for s in internal})))
    historical=[]
    for name in ('docs/handoffs/2026-09-23-product-maturity-goal-handoff.md','docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md'):
        text=(repo/name).read_text(encoding='utf-8-sig')
        historical.append(dict(path=name,lines=len(text.splitlines()),latestWordOccurrences=text.count('最新'),markdownLinks=len(re.findall(r'\]\(',text))))
    return dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),head=head,scope=list(ROOTS),excluded=list(EXCLUDES),collectorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),trackedFiles=len(entries),logicalTrackedBytes=sum(x['bytes'] for x in entries),uniqueBlobBytes=sum(sizes.values()),inventoryMetadataSha256=hashlib.sha256(raw).hexdigest(),byRoot=aggregate(entries,lambda x:x['path'].split('/')[0]),evidence=dict(files=len(evidence),logicalBytes=sum(x['bytes'] for x in evidence),byStage=aggregate(evidence,lambda x:x['path'].split('/')[4]),byExtension=aggregate(evidence,lambda x:Path(x['path']).suffix.lower() or '[none]'),repeatedGroups=len(repeated),repeatedLogicalBytes=sum(x['repeatedLogicalBytes'] for x in repeated),largestRepeated=sorted(repeated,key=lambda x:-x['repeatedLogicalBytes'])[:12],scriptFiles=len(scriptRows),uniqueScriptBlobs=len(scriptGroups),repeatedScriptCopies=sum(v-1 for v in scriptGroups.values())),java=dict(files=len(java),lines=sum(x['lines'] for x in java),largestProduction=sorted([x for x in java if x['path'].startswith('src/')],key=lambda x:-x['lines'])[:15],largestTests=sorted([x for x in java if x['path'].startswith('test/')],key=lambda x:-x['lines'])[:8]),historicalEntrypoints=historical,notes=['Tracked logical bytes are not .git packed size, disk usage, or reclaimable bytes. Git already deduplicates identical blobs.','Exact script duplicates only; no claim that variants have identical behavior.','Line/import counts are static size signals, not defect or architecture judgments.','No forbidden directory traversal; only explicit Git pathspec metadata and allowed Java/document content.'])
def main():
    p=argparse.ArgumentParser();p.add_argument('--repo',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    if '.testagent' in a.repo.parts or '.testagent' in a.out.parts:raise ValueError('Forbidden path')
    repo=a.repo.resolve();out=a.out.resolve()
    if not out.is_relative_to(repo/'docs') or '.testagent' in out.parts or out.exists():raise ValueError('Fresh output inside repository docs required')
    data=collect(repo);out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes((json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode())
    print(json.dumps(dict(head=data['head'],trackedFiles=data['trackedFiles'],logicalTrackedBytes=data['logicalTrackedBytes'],evidenceFiles=data['evidence']['files'],evidenceLogicalBytes=data['evidence']['logicalBytes'],javaFiles=data['java']['files']),ensure_ascii=False))
if __name__=='__main__':main()
