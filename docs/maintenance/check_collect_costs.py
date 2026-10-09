"""Synthetic fixture for inventory semantics, without traversing forbidden paths."""
import argparse, datetime, importlib.util, json, subprocess, sys, tempfile, uuid
from pathlib import Path
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('costs',Path(__file__).with_name('collect_costs.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--out',type=Path,required=True);args=parser.parse_args()
    repo=Path(__file__).resolve().parents[2]
    if '.testagent' in args.out.parts:raise ValueError('Forbidden path')
    out=args.out.resolve()
    if not out.is_relative_to(repo/'docs') or out.exists():raise ValueError('Fresh docs receipt required')
    fixture=Path(tempfile.gettempdir())/('datacube-maintenance-fixture-'+uuid.uuid4().hex);fixture.mkdir()
    files={'src/A.java':'package a;\nclass A {}\n','test/ATest.java':'package a;\nclass ATest {}\n','buildSrc/B.java':'class B {}\n','docs/handoffs/2026-09-23-product-maturity-goal-handoff.md':'最新\n最新\n','docs/superpowers/plans/2026-09-23-product-maturity-roadmap.md':'# plan\n','docs/superpowers/verification/evidence/a/run.ps1':'exit 0\n','docs/superpowers/verification/evidence/b/run.ps1':'exit 0\n'}
    for name,text in files.items():
        p=fixture/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(text.encode())
    def git(*argv):return subprocess.run(['git',*argv],cwd=fixture,capture_output=True,check=True)
    git('init','-q');git('add','--',*files)
    git('-c','user.name=DataCube synthetic fixture','-c','user.email=synthetic@example.invalid','commit','-q','-m','fixture')
    data=module.collect(fixture)
    assert data['trackedFiles']==7 and data['java']['files']==3 and data['java']['lines']==5
    assert data['evidence']['files']==2 and data['evidence']['logicalBytes']==14 and data['evidence']['repeatedLogicalBytes']==7
    assert data['evidence']['scriptFiles']==2 and data['evidence']['uniqueScriptBlobs']==1
    assert data['historicalEntrypoints'][0]['latestWordOccurrences']==2
    rejected=['.testagent/x','docs/.testagent/x','src/../secrets','C:/src/a','/src/a','src\\x','unknown/a']
    assert all(not module.allowed(s) for s in rejected)
    (fixture/'src/A.java').write_bytes(b'class changed {}\n')
    try:module.collect(fixture)
    except subprocess.CalledProcessError:pass
    else:raise AssertionError('Dirty tracked inputs accepted')
    result=dict(utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),fixture=str(fixture),passed=True,trackedFiles=7,javaFiles=3,javaLines=5,exactScriptDuplicateCopies=1,dirtyTrackedInputsRejected=True,rejectedPathStrings=rejected,forbiddenPathsCreatedOrEnumerated=False)
    out.parent.mkdir(parents=True,exist_ok=True);out.write_bytes((json.dumps(result,ensure_ascii=False,indent=2)+'\n').encode());print('COLLECTOR_FIXTURE_PASSED')
if __name__=='__main__':main()
