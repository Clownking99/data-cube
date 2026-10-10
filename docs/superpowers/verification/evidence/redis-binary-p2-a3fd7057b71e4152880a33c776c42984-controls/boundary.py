import importlib.util,json,pathlib,sys,traceback
base=pathlib.Path(__file__).absolute().parent
def load(name):
    spec=importlib.util.spec_from_file_location('boundary_'+name.replace('.','_'),base/'tools'/name);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def save(name,data):(base/name).write_bytes((json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode())
def require(value,message):
    if not value:raise RuntimeError('BOUNDARY_REFUSAL:'+message)
outer=load('run-owned.py');config=json.loads((base/'spec.json').read_bytes());repo=pathlib.Path(config['repo']);evidence=base.parent
results=[]
try:
    for name in ('g11-p2-legacy','g11-p3-legacy'):
        require(outer.admitted_run_name(name),'legacy root');results.append(dict(case=name,accepted=True,scope='pure lexical helper'))
    for phase in ('2','3'):
        for suffix in ('','-fixture','-targeted','-full','-buildsrc','-image','-linked','-owner','-package'):
            name='redis-binary-p'+phase+'-'+'a'*32+suffix;require(outer.admitted_run_name(name),'valid named root');results.append(dict(case=name,accepted=True))
    for name in ('redis-other-p2-'+'a'*32,'redis-binary-p2-','redis-binary-p2-'+'a'*31,'redis-binary-p2-'+'A'*32,'redis-binary-p2-'+'a'*32+'-','redis-binary-p2-'+'a'*32+'-'+'x'*65):
        require(not outer.admitted_run_name(name),'bad named root');results.append(dict(case=name,accepted=False))
    uuid='a'*32;valid=evidence/('redis-binary-p2-'+uuid+'-fixture');owner=evidence/(valid.name+'-owner')
    template=dict(repo=str(repo),tools=str(base/'tools'),out=str(owner),stageEvidence=str(valid),mode='fixture',inputSpec=None,jdk=config['jdk'],cache=config['cache'],pwsh=config['pwsh'],python=config['python'],runtimeParent=config['runtimeParent'],imageSourceScope=None,deadlineSeconds=10,fixture='normal',processDeadlineMs=0,settleMs=1500,streamCap=33554432,outerFixture=None)
    cases=[('foreign-out',dict(out=str(evidence.parent/owner.name)),'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3'),('nested-stage',dict(stageEvidence=str(evidence/'nested'/valid.name)),'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3'),('malformed-stage',dict(stageEvidence=str(evidence/'redis-binary-p2-invalid')),'OUTER_EVIDENCE_OUTSIDE_G11_P2_P3'),('tools-extra-level',dict(tools=str(base/'nested'/'tools')),'OUTER_TOOL_ROOT_OUTSIDE_RUN'),('tools-wrong-leaf',dict(tools=str(base/'not-tools')),'OUTER_TOOL_ROOT_OUTSIDE_RUN'),('image-wrong-basename',dict(imageSourceScope=str(valid/('b'*32)/'wrong.json')),'OUTER_IMAGE_SOURCE_OUTSIDE_RUN'),('image-bad-owned',dict(imageSourceScope=str(valid/'wrong-owned'/'scope.json')),'OUTER_IMAGE_SOURCE_OUTSIDE_RUN'),('image-foreign-root',dict(imageSourceScope=str(evidence.parent/valid.name/('b'*32)/'scope.json')),'OUTER_IMAGE_SOURCE_OUTSIDE_RUN')]
    original_load=outer.evidence.load;original_no_links=outer.evidence.no_links;original_file=outer.__file__
    for name,changes,expected in cases:
        spec={**template,**changes};path=base/(name+'-boundary-spec.json');save(path.name,spec)
        visits=[];legal_reads=[];phase=[False]
        def observed_no_links(target):
            if phase[0]:visits.append(str(target))
            return original_no_links(target)
        def observed_load(target):
            value=original_load(target);legal_reads.append(str(target));phase[0]=True;return value
        outer.evidence.no_links=observed_no_links;outer.evidence.load=observed_load
        # Function is the real main. Adjust only __file__ for the wrong-tool-root inputs
        # so the specific package/tools admission is exercised, not an earlier self-path check.
        outer.__file__=str(pathlib.Path(spec['tools'])/'run-owned.py') if name.startswith('tools-') else original_file
        saved=sys.argv;failure=None
        try:
            sys.argv=[original_file,'--spec',str(path)]
            try:outer.main()
            except Exception as error:failure=str(error)
        finally:sys.argv=saved;outer.evidence.load=original_load;outer.evidence.no_links=original_no_links;outer.__file__=original_file
        require(failure==expected,name+' exact refusal '+str(failure));require(not visits,name+' reached target metadata');require(legal_reads==[str(path)],name+' legal actual spec read')
        results.append(dict(case=name,actualRefusal=failure,actualSpec=str(path),legalSpecReads=legal_reads,rejectedPhaseNoLinksVisits=visits,instrumentation='Records and delegates actual no_links; no filesystem check is swallowed. __file__ is the only simulated datum for two wrong-tool-location cases.'))
    save('boundary-python-result.json',dict(schema='redis-binary-python-boundaries/v1',passed=True,cases=results,engineering=False))
except BaseException as error:
    save('boundary-python-result.json',dict(passed=False,cases=results,failure=repr(error)));traceback.print_exc();sys.exit(1)
