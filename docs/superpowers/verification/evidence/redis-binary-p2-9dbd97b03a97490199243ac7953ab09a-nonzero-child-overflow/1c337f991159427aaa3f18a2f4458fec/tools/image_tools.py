"""Read-only image inventory and linked-module acceptance, using admitted paths."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import importlib.util

_sibling=Path(__file__).absolute().with_name('evidence_tools.py')
if any(p.rstrip(' .').casefold() in ('.testagent','.git','.g10-verify-blobs.ps1') for p in _sibling.parts):
    raise RuntimeError('FORBIDDEN_TOOL_PATH')
_spec=importlib.util.spec_from_file_location('frozen_evidence_tools',_sibling)
evidence=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(evidence)


REQUIRED = ('RedisDisplayLimits','RedisDisplaySupport','RedisKeySnapshot','RedisTextRetention','RespClient','RedisSessionManager')


def image_path(image, runtime, runtime_parent):
    image=evidence.admitted_path(image);runtime=evidence.admitted_path(runtime);parent=evidence.admitted_path(runtime_parent)
    if runtime.parent!=parent or not re.fullmatch(r'datacube-g11-[0-9a-f]{32}',runtime.name) or image!=runtime/'build/main/jpackage/DataCube':
        raise evidence.Refusal('IMAGE_PATH_OUTSIDE_OWNED_RUNTIME')
    evidence.no_links(image)
    return image


def inventory(image, runtime, runtime_parent):
    image=image_path(image,runtime,runtime_parent);files=[]
    def visit(directory):
        with os.scandir(directory) as entries:
            for entry in entries:
                evidence.components(entry.path)  # No child metadata before lexical admission.
                path=Path(entry.path);evidence.no_links(path)
                if entry.is_dir(follow_symlinks=False):visit(path)
                elif entry.is_file(follow_symlinks=False):
                    digest=hashlib.sha256();length=0
                    with path.open('rb') as stream:
                        for chunk in iter(lambda:stream.read(65536),b''):
                            digest.update(chunk);length+=len(chunk)
                    files.append({'path':path.relative_to(image).as_posix(),'length':length,'sha256':digest.hexdigest()})
                else:raise evidence.Refusal('INVALID_IMAGE_FILE_KIND')
    visit(image)
    required={'DataCube.exe','app/DataCube.cfg','runtime/lib/modules','runtime/bin/java.exe'}
    if not required.issubset({x['path'] for x in files}):raise evidence.Refusal('MISSING_IMAGE_CORE_FILES')
    return {'schema':'image-files/v1','image':str(image),'files':sorted(files,key=lambda x:x['path']),
            'core':[x for x in files if x['path'] in required]}


def audit_classes(index, types, manifest, cfg):
    if types.get('schema')!='test-types/v1' or not types.get('mappings') or types['sourceCount']!=len(types['mappings']) or types['typeCount']!=len(types['mappings']):
        raise evidence.Refusal('INVALID_TEST_TYPE_COVERAGE')
    test_types={x['type'].replace('.','/') for x in types['mappings']}
    if len(test_types)!=len(types['mappings']):raise evidence.Refusal('DUPLICATE_TEST_TYPES')
    classes={line.strip() for line in index.splitlines() if line.strip().endswith('.class')}
    if not classes:raise evidence.Refusal('EMPTY_MODULE_CLASS_INDEX')
    leaks=[]
    for name in sorted(classes):
        base=name[:-6].split('$',1)[0]
        if base in test_types or re.search(r'^(org/junit/|org/mockito/|org/testfx/|acceptance/)',base) or re.search(r'(G\d+Linked.*Probe|MigrationRuntimeDriverProbe|DesktopProbe|ShutdownDesktopProbe)',base):leaks.append(name)
    missing=[name for name in REQUIRED if 'com/datacube/redis/'+name+'.class' not in classes]
    file_leaks=[x['path'] for x in manifest['files'] if re.search(r'(?i)(\.datacube|(^|/)(profiles?|home|temp|test-results|acceptance)(/|$)|Probe|fixture|isolation)',x['path'])]
    option_leaks=bool(re.search(r'(?i)(user\.home|headless|java\.io\.tmpdir|JAVA_TOOL_OPTIONS|acceptance|synthetic|datacube-g11-)',cfg))
    if leaks or missing or file_leaks or option_leaks:
        raise evidence.Refusal('IMAGE_AUDIT_REJECTED:'+json.dumps({'classLeaks':leaks,'missingClasses':missing,'fileLeaks':file_leaks,'optionLeaks':option_leaks}))
    return {'schema':'image-audit/v1','passed':True,'classLeaks':[],'missingClasses':[],'fileLeaks':[],'optionLeaks':False,'testTypeCount':len(test_types),'classCount':len(classes),'core':manifest['core']}


def verify_inventory(baseline,current):
    if baseline!=current:raise evidence.Refusal('IMAGE_IDENTITY_CHANGED')
    return current


def main():
    parser=argparse.ArgumentParser();parser.add_argument('command',choices=('inventory','verify','audit'))
    parser.add_argument('--spec',required=True);parser.add_argument('--owned-root',required=True);parser.add_argument('--out',required=True)
    args=parser.parse_args();owned=evidence.admitted_path(args.owned_root);out=evidence.admitted_path(args.out);spec_path=evidence.admitted_path(args.spec)
    for path in (out,spec_path):
        if not path.is_relative_to(owned) or path==owned:raise evidence.Refusal('IMAGE_RECEIPT_OUTSIDE_OWNED_SCOPE')
        evidence.no_links(path)
    if out.exists():raise evidence.Refusal('OUTPUT_COLLISION')
    try:
        spec=evidence.load(str(spec_path))
        if set(spec)!={'image','runtime','runtimeParent','baseline','types','index'}:raise evidence.Refusal('UNKNOWN_IMAGE_SPEC_FIELD')
        # Validate every referenced receipt path before touching the image.
        refs={}
        for key in ('baseline','types','index'):
            if spec[key] is not None:
                refs[key]=evidence.admitted_path(spec[key])
                if not refs[key].is_relative_to(owned):raise evidence.Refusal('IMAGE_RECEIPT_OUTSIDE_OWNED_SCOPE')
        current=inventory(spec['image'],spec['runtime'],spec['runtimeParent'])
        if args.command in ('verify','audit'):
            baseline=evidence.load(str(refs['baseline']))
            verify_inventory(baseline,current)
        if args.command=='audit':
            for path in refs.values():evidence.no_links(path)
            if refs['index'].stat().st_size>32*1024*1024:raise evidence.Refusal('INDEX_BYTE_LIMIT')
            cfg=image_path(spec['image'],spec['runtime'],spec['runtimeParent'])/'app/DataCube.cfg'
            if cfg.stat().st_size>1024*1024:raise evidence.Refusal('CFG_BYTE_LIMIT')
            result=audit_classes(refs['index'].read_text(encoding='utf-8-sig'),evidence.load(str(refs['types'])),current,cfg.read_text(encoding='utf-8-sig'))
        else:result=current
        code=0
    except (ValueError,OSError,UnicodeError) as error:
        result={'schema':'tool-failure/v1','status':'failed','kind':str(error)};code=2
    with out.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    return code


if __name__=='__main__':sys.exit(main())
