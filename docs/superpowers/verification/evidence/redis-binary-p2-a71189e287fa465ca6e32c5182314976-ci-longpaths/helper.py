import pathlib,json,os,hashlib,subprocess,importlib.util,time,traceback,sys
base=pathlib.Path(__file__).absolute().parent;config=json.loads((base/'spec.json').read_bytes());repo=pathlib.Path(config['repo']);runtime=pathlib.Path(config['runtime'])
def save(name,v):(base/name).write_bytes(json.dumps(v,indent=2).encode())
def require(v,why):
    if not v:raise RuntimeError('LONGPATH_REFUSAL:'+why)
sp=importlib.util.spec_from_file_location('owned_helper',repo/'scripts/verification/check-core.py');helper=importlib.util.module_from_spec(sp);sp.loader.exec_module(helper)
require(runtime.parent==pathlib.Path('C:/Users/hetia/AppData/Local/Temp') and runtime.name.startswith('redis-binary-ci-'),'runtime ownership')
require(not runtime.exists(),'runtime collision');runtime.mkdir();work=runtime/'repo';empty=runtime/'empty-git-config';empty.write_bytes(b'')
env={k:os.environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in os.environ};env.update(HOME=str(runtime),USERPROFILE=str(runtime),TEMP=str(runtime),TMP=str(runtime),GIT_CONFIG_NOSYSTEM='1',GIT_CONFIG_GLOBAL=str(empty),GIT_CONFIG_SYSTEM=str(empty),GIT_AUTHOR_NAME='Synthetic fixture',GIT_AUTHOR_EMAIL='fixture@invalid',GIT_COMMITTER_NAME='Synthetic fixture',GIT_COMMITTER_EMAIL='fixture@invalid',GIT_TERMINAL_PROMPT='0')
records=[]
def git(name,args,data=None,longpaths=None,expected=0):
    current=dict(env)
    if longpaths is not None:current.update(GIT_CONFIG_COUNT='1',GIT_CONFIG_KEY_0='core.longpaths',GIT_CONFIG_VALUE_0=longpaths)
    argv=[config['git'],'-c','gc.auto=0']+args;owner=helper.OuterOwner();p=None;started=time.monotonic();out=b'';err=b'';code=None;fault=None
    save(name+'-command.json',dict(argv=argv,cwd=str(runtime),environment=current,deadlineSeconds=20,stdinSha256=None if data is None else hashlib.sha256(data).hexdigest()))
    try:
        p=subprocess.Popen(argv,cwd=runtime,env=current,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE);owner.assign(p)
        try:out,err=p.communicate(data,timeout=20);code=p.returncode
        except subprocess.TimeoutExpired:fault='DEADLINE'
    finally:
        settlement=owner.close()
        if p is not None and p.poll() is None:p.wait(timeout=5)
    (base/(name+'.stdout')).write_bytes(out);(base/(name+'.stderr')).write_bytes(err)
    record=dict(name=name,actualExit=code,failure=fault,settlement=settlement,elapsedSeconds=time.monotonic()-started,stdout=dict(length=len(out),sha256=hashlib.sha256(out).hexdigest()),stderr=dict(length=len(err),sha256=hashlib.sha256(err).hexdigest()));records.append(record);save('progress.json',records)
    require(not fault and settlement['actualJobQueryObservedEmpty'],'owned execution')
    require(code==expected if expected is not None else code!=0,'actual exit:'+name)
    return out,err
try:
    git('git-version',['--version'])
    git('init',['init','--initial-branch=fixture',str(work)])
    payload=b'Synthetic long path checkout fixture\x00\r\noriginal UTF-8 bytes: '+bytes.fromhex('e4b8ade69687')+b'\n'
    (base/'expected-file.bin').write_bytes(payload)
    blob=git('hash-object',['-C',str(work),'hash-object','-w','--stdin'],payload)[0].decode().strip()
    relative='/'.join(['segment-'+str(i)+'-'+'x'*30 for i in range(7)]+['original.bin']);target=work/pathlib.Path(relative)
    require(len(str(target))>300 and all(len(p)<255 for p in target.parts),'long fixture path')
    git('update-index',['-C',str(work),'update-index','--add','--cacheinfo','100644,'+blob+','+relative])
    tree=git('write-tree',['-C',str(work),'write-tree'])[0].decode().strip()
    commit=git('commit-tree',['-C',str(work),'commit-tree',tree,'-m','Synthetic long-path object'])[0].decode().strip()
    command=['-C',str(work),'checkout','--force','--detach',commit]
    out,err=git('checkout-false',command,longpaths='false',expected=None)
    require(b'Filename too long' in err,'negative exact long-path failure')
    extended=pathlib.Path('\\\\?\\'+str(target))
    require(not extended.exists(),'negative unexpectedly wrote long file')
    git('checkout-true',command,longpaths='true')
    actual=extended.read_bytes();require(actual==payload,'actual original bytes')
    got=git('cat-file',['-C',str(work),'cat-file','blob',blob])[0];require(got==actual,'same Git object bytes')
    save('result.json',dict(passed=True,baseCommit=config['baseCommit'],runtimeExcluded=str(runtime),sameRepository=str(work),sameCommit=commit,sameTree=tree,sameBlob=blob,sameCheckoutArgv=command,path=relative,absolutePathLength=len(str(target)),actualFileLength=len(actual),actualFileSha256=hashlib.sha256(actual).hexdigest(),negative='Filename too long',positiveActualExit=0,globalOrMachineConfigChanged=False,records=records))
    print('Synthetic negative/positive same-object checkout and original bytes passed',flush=True)
except BaseException as error:
    save('result.json',dict(passed=False,failure=repr(error),records=records));traceback.print_exc();sys.exit(1)
