"""Owned synthetic helpers and evidence-tool checks; no product services."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
import ctypes
from ctypes import wintypes
import tempfile


def fixture(mode, arguments):
    if mode == 'exit-tail':
        gate=Path(arguments[0]);code=int(arguments[1])
        if code not in (0,7) or any(p.rstrip(' .').casefold() in ('.testagent','.git') for p in gate.parts):raise ValueError('INVALID_TAIL_FIXTURE')
        end=time.monotonic()+15
        while not gate.is_file():
            if time.monotonic()>=end:raise RuntimeError('TAIL_GATE_DEADLINE')
            time.sleep(.01)
        os.write(1,b'tail boundary stdout\n');os.write(2,b'tail boundary stderr\n')
        return code
    if mode == "argv":
        sys.stdout.write(json.dumps(arguments, ensure_ascii=False))
        return 0
    if mode == "normal":
        os.write(1, b"stdout\n"); os.write(2, b"stderr\n")
        return 0
    if mode in ('cap-exact','cap-plus-one'):
        os.write(1,b'x'*(32768+(mode=='cap-plus-one')))
        return 0
    if mode == "nonzero":
        os.write(2, b"synthetic compiler failure\n")
        return 7
    if mode == "dual":
        for _ in range(100):
            os.write(1, b"o" * 16384); os.write(2, b"e" * 16384)
        return 0
    if mode == "overflow":
        while True:
            os.write(1, b"o" * 16384); os.write(2, b"e" * 16384)
    if mode=='delayed-overflow':
        time.sleep(1)
        return fixture('overflow',[])
    if mode == "continuous":
        while True:
            os.write(1, b"tick\n"); time.sleep(.01)
    if mode in ("child-pipe", "nonzero-child-pipe", "detached-child",'nonzero-child-overflow'):
        options={'stdout':subprocess.DEVNULL,'stderr':subprocess.DEVNULL} if mode=='detached-child' else {}
        child='delayed-overflow' if mode=='nonzero-child-overflow' else 'sleep'
        subprocess.Popen([sys.executable, "-I", "-S", __file__, "--fixture", child],**options)
        return 7 if mode.startswith("nonzero") else 0
    if mode == "sleep":
        time.sleep(120)
        return 0
    raise ValueError("UNKNOWN_FIXTURE")


def tool_checks(directory):
    spec = importlib.util.spec_from_file_location("evidence", Path(__file__).with_name("evidence_tools.py"))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    cases = []
    def rejects(name, call):
        try:
            call()
        except (module.Refusal, ValueError, OSError, ET.ParseError) as error:
            cases.append({"name": name, "passed": True, "failure": str(error)}); return
        raise RuntimeError("MISSING_REFUSAL:" + name)
    for value in [r"C:\never\.testagent\x", r"C:\never\.testagent \x", r"C:\never\.testagent.\x",
                  r"C:\never\NUL.txt", r"C:\never\x:stream", r"C:\never\..\x", r"\\?\C:\never"]:
        rejects("lexical:" + value, lambda value=value: module.admitted_path(value))
    original_lstat=Path.lstat
    def forbidden_stat(*args,**kwargs):
        raise RuntimeError('FILESYSTEM_ACCESS_BEFORE_LEXICAL_REFUSAL')
    try:
        Path.lstat=forbidden_stat
        rejects('forbidden-with-zero-stat-calls',lambda: module.no_links(Path(r'C:\never\.testagent\x')))
        rejects('forbidden-input-before-repo-stat',lambda: module.snapshot(r'C:\never',['test/.testagent/x.java'],'a'*40))
    finally:
        Path.lstat=original_lstat
    root = Path(directory) / ("tool-fixtures-" + str(time.time_ns()))
    root.mkdir(exist_ok=False)
    if True:
        xml = root / "duplicate-name"; xml.mkdir()
        file = xml / "TEST-a.xml"
        file.write_text('<testsuite name="a" tests="2" failures="0" errors="0" skipped="1"><testcase classname="C" name="same"/><testcase classname="C" name="same"><skipped/></testcase></testsuite>', encoding="utf-8")
        result = module.xml_results(xml)
        if result["tests"] != 2 or result["passed"] != 1 or result["skipped"] != 1 or [x["ordinal"] for x in result["cases"]] != [0, 1] or not result["duplicateDisplayNames"]:
            raise RuntimeError("DUPLICATE_DISPLAY_NAME_IDENTITY")
        cases.append({"name": "duplicate-name-mixed-status", "passed": True, "receipt": result})
        for name, body in {
            "contradictory": '<testsuite name="a" tests="1" failures="1" errors="0" skipped="1"><testcase classname="C" name="x"><failure/><skipped/></testcase></testsuite>',
            "bad-count": '<testsuite name="a" tests="2" failures="0" errors="0" skipped="0"><testcase classname="C" name="x"/></testsuite>',
            "zero-cases": '<testsuite name="a" tests="0" failures="0" errors="0" skipped="0"/>',
            "damaged": '<broken',
            "entity": '<!DOCTYPE testsuite [<!ENTITY x "text">]><testsuite/>',
        }.items():
            case_xml=root/name;case_xml.mkdir()
            (case_xml/'TEST-a.xml').write_text(body, encoding="utf-8"); rejects(name, lambda: module.xml_results(case_xml))
        empty=root/'zero-xml';empty.mkdir();rejects("zero-xml", lambda: module.xml_results(empty))
        repo = root / "repo"; (repo / "src" / "legitimate" / "build").mkdir(parents=True)
        source = repo / "src" / "legitimate" / "build" / "A.java"; source.write_text("class A {}")
        (repo / "test").mkdir(); (repo / "test" / "A.java").write_text("class A {}")
        paths = ["src/legitimate/build/A.java", "test/A.java"]
        before = module.snapshot(str(repo), paths, "a" * 40)
        (root/'inputs-before.json').write_text(json.dumps(before,indent=2),encoding='utf-8')
        (root/'source-before.txt').write_text(source.read_text(),encoding='utf-8')
        module.verify(before, module.snapshot(str(repo), [x.replace("/", "\\") for x in paths], "a" * 40))
        cases.append({"name": "windows-backslash-and-build-package", "passed": True})
        source.write_text("class A {int changed;}")
        (root/'inputs-after.json').write_text(json.dumps(module.snapshot(str(repo),paths,'a'*40),indent=2),encoding='utf-8')
        (root/'source-after.txt').write_text(source.read_text(),encoding='utf-8')
        rejects("input-change", lambda: module.verify(before, module.snapshot(str(repo), paths, "a" * 40)))
        extra = source.with_name("Ignored.java"); extra.write_text("class Ignored {}")
        (root/'unknown-source.txt').write_text(extra.read_text(),encoding='utf-8')
        # The inventory does not consult git or ignore files.
        rejects("unknown-untracked-ignored-build-package", lambda: module.snapshot(str(repo), paths, "a" * 40))
        extra.unlink(); source.unlink(); rejects("missing-input", lambda: module.snapshot(str(repo), paths, "a" * 40))
        rejects("duplicate-input", lambda: module.snapshot(str(repo), ["test/A.java", "test\\A.java"], "a" * 40))
    return {"schema": "core-checks/v1", "passed": True, "cases": cases}


class OuterOwner:
    """Independent actual Windows job handle; never discovers neighboring PIDs."""
    def __init__(self):
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.api.CreateJobObjectW.restype = wintypes.HANDLE
        for name in ("AssignProcessToJobObject", "TerminateJobObject", "CloseHandle"):
            function = getattr(self.api, name); function.restype = wintypes.BOOL
        self.api.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.api.TerminateJobObject.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.api.QueryInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        self.api.QueryInformationJobObject.restype = wintypes.BOOL
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        class Basic(ctypes.Structure):
            _fields_=[('processTime',ctypes.c_longlong),('jobTime',ctypes.c_longlong),('flags',wintypes.DWORD),('minimum',ctypes.c_size_t),('maximum',ctypes.c_size_t),('active',wintypes.DWORD),('affinity',ctypes.c_size_t),('priority',wintypes.DWORD),('scheduling',wintypes.DWORD)]
        class Io(ctypes.Structure):
            _fields_=[(name,ctypes.c_ulonglong) for name in ('a','b','c','d','e','f')]
        class Limits(ctypes.Structure):
            _fields_=[('basic',Basic),('io',Io),('processMemory',ctypes.c_size_t),('jobMemory',ctypes.c_size_t),('peakProcess',ctypes.c_size_t),('peakJob',ctypes.c_size_t)]
        limits=Limits();limits.basic.flags=0x2000
        self.api.SetInformationJobObject.argtypes=[wintypes.HANDLE,ctypes.c_int,ctypes.c_void_p,wintypes.DWORD]
        self.api.SetInformationJobObject.restype=wintypes.BOOL
        if not self.api.SetInformationJobObject(self.handle,9,ctypes.byref(limits),ctypes.sizeof(limits)):
            error=ctypes.get_last_error();self.api.CloseHandle(self.handle);raise ctypes.WinError(error)

    def assign(self, process):
        if not self.api.AssignProcessToJobObject(self.handle, wintypes.HANDLE(int(process._handle))):
            process.kill(); process.wait(timeout=5)
            raise ctypes.WinError(ctypes.get_last_error())

    def terminate(self):
        if not self.api.TerminateJobObject(self.handle, 124):
            raise ctypes.WinError(ctypes.get_last_error())

    def members(self):
        buffer = ctypes.create_string_buffer(65536); needed = wintypes.DWORD()
        if not self.api.QueryInformationJobObject(self.handle, 3, buffer, len(buffer), ctypes.byref(needed)):
            raise ctypes.WinError(ctypes.get_last_error())
        count = ctypes.c_uint.from_buffer(buffer, 4).value
        if count > (len(buffer)-8)//ctypes.sizeof(ctypes.c_void_p):
            raise RuntimeError('OUTER_MEMBER_LIMIT')
        return [ctypes.c_size_t.from_buffer(buffer, 8+i*ctypes.sizeof(ctypes.c_void_p)).value for i in range(count)]

    def close(self, deadline=None):
        try:
            before = self.members()
            self.terminate()
            deadline=time.monotonic()+5 if deadline is None else deadline
            while self.members() and time.monotonic()<deadline:
                time.sleep(.01)
            after=self.members()
            if after:
                raise RuntimeError('OUTER_SETTLEMENT_INCOMPLETE')
            return {'beforeTermination':before,'afterTermination':after,'actualJobQueryObservedEmpty':True}
        finally:
            self.api.CloseHandle(self.handle)


def process_checks(repo, directory, pwsh, python, jdk, cache, matrix=None):
    repo=repo.absolute();directory=directory.absolute()
    directory.mkdir(exist_ok=False)
    results = []
    environment={k:os.environ[k] for k in ('SystemRoot','WINDIR','PATH','COMSPEC','PATHEXT') if k in os.environ}
    environment.update(HOME=str(directory),USERPROFILE=str(directory),TEMP=str(directory),TMP=str(directory))
    neighbor = subprocess.Popen([python, "-I", "-S",'-B', str(Path(__file__).absolute()), "--fixture", "sleep"],env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    outer_spec=importlib.util.spec_from_file_location('matrix_outer',Path(__file__).absolute().with_name('run-owned.py'))
    outer_module=importlib.util.module_from_spec(outer_spec);outer_spec.loader.exec_module(outer_module)
    try:
        if matrix is None:
            matrix=(("normal", None), ("argv", None), ("nonzero", "NONZERO_EXIT"),
                               ("dual", None), ("continuous", "DEADLINE"), ("overflow", "LOG_LIMIT"),
                               ("child-pipe", "DEADLINE"), ("nonzero-child-pipe", "NONZERO_EXIT"),
                               ('detached-child','OWNED_DESCENDANT_REQUIRES_TERMINATION'),
                               ('start-failure','PARENT_FAILURE:'),('assign-failure','PARENT_FAILURE:'),
                               ('unobserved-settlement','OWNED_SETTLEMENT_INCOMPLETE'),
                               ('cap-exact',None),('cap-plus-one','LOG_LIMIT'),('cancel','CANCELLED'),('tool-change','TOOL_IDENTITY_CHANGED'),
                               ('compile-zero-xml','NONZERO_EXIT'),('compile-stat-failure','NONZERO_EXIT'),('nonzero-child-overflow','NONZERO_EXIT'),
                               ('skip-live',None),('skip-native','UNAPPROVED_SKIP:'))
        for mode, expected in matrix:
            name = "g11-p2-synthetic-" + uuid.uuid4().hex + "-" + mode
            evidence = repo / "docs/superpowers/verification/evidence" / name
            out_path=repo/'docs/superpowers/verification/evidence'/(name+'-owner')
            spec={'repo':str(repo),'tools':str(Path(__file__).absolute().parent),'out':str(out_path),'stageEvidence':str(evidence),'mode':'fixture','inputSpec':None,'jdk':jdk,'cache':cache,'pwsh':pwsh,'python':python,'runtimeParent':tempfile.gettempdir(),'imageSourceScope':None,'deadlineSeconds':22,'fixture':mode,'processDeadlineMs':7000,'settleMs':1500,'streamCap':32768 if mode in ('overflow','cap-exact','cap-plus-one','nonzero-child-overflow') else 33554432,'outerFixture':None}
            spec_path=directory/(mode+'-owner-spec.json');spec_path.write_text(json.dumps(spec,indent=2),encoding='utf-8')
            argv=[python,'-I','-S','-B',str(Path(__file__).absolute().with_name('run-owned.py')),'--spec',str(spec_path)]
            started=time.monotonic()
            original_args=sys.argv
            try:
                sys.argv=[argv[4],'--spec',str(spec_path)];code=outer_module.main()
            finally:sys.argv=original_args
            outer=json.loads((out_path/'result.json').read_text(encoding='utf-8'))
            failure=outer['firstFailure']
            if expected is not None and failure=='NONZERO_EXIT' and code!=0:failure=None # Only an expected inner negative may explain outer NONZERO_EXIT.
            outer_settlement=outer['settlement']
            receipts = list(evidence.glob("*/stage-result.json"))
            receipt = json.loads(receipts[0].read_text(encoding="utf-8-sig")) if len(receipts) == 1 else None
            actual_command=json.loads((out_path/'command.json').read_text(encoding='utf-8'))
            record = {"mode": mode, "argv": actual_command['argv'], 'outerInvocation':'in-process frozen run-owned.main; caller must use -I -S -B', "outerExitCode": code, "outerFailure": failure,
                      "elapsedSeconds": time.monotonic()-started, "receipt": receipt,
                      "neighborStillRunning": neighbor.poll() is None, 'outerSettlement':outer_settlement}
            results.append(record)
            (directory / (mode + "-check.json")).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            expected_ok=receipt is not None and (receipt['primaryFailure']==expected or (expected is not None and expected.endswith(':') and str(receipt['primaryFailure']).startswith(expected)))
            if expected_ok:
                expected_ok=(receipt['status']=='passed' and code==0) if expected is None else (receipt['status']=='failed' and code!=0)
            if failure or not expected_ok or not record["neighborStillRunning"]:
                raise RuntimeError("PROCESS_CHECK_FAILED:" + mode)
            if expected is None:
                # Composite skip-policy receipts contain the actual process rather than root fields at the top level.
                successful_process=receipt if receipt['schema']=='process/v1' else receipt.get('processReceipt')
                if not isinstance(successful_process,dict) or successful_process.get('schema')!='process/v1' or successful_process.get('status')!='passed':raise RuntimeError('SUCCESSFUL_PROCESS_RECEIPT_MISSING:'+mode)
                proof=successful_process.get('rootExitProof')
                if successful_process.get('rootExited') is not True or successful_process.get('rootExitCode')!=0 or successful_process.get('observedRootExitCode')!=0 or not isinstance(proof,dict) or proof.get('exitCode')!=0 or successful_process.get('ownedSettlement')!='complete':raise RuntimeError('SUCCESSFUL_ROOT_EXIT_PROOF_MISSING:'+mode)
            if mode.startswith('exit-'):
                if receipt['observationAdapterFault']!=mode:raise RuntimeError('EXIT_FAULT_NOT_ACTUALLY_APPLIED')
                if mode in ('exit-tail-zero','exit-tail-seven','exit-late-identity','exit-no-capture-zero','exit-no-capture-seven'):
                    proof=receipt['rootExitProof'];wanted=7 if mode.endswith('seven') else 0
                    if proof is None or receipt['rootExitCode']!=wanted or not receipt['rootExited'] or proof['exitCode']!=wanted or not isinstance(proof['startTimeUtc'],str):raise RuntimeError('ROOT_EXIT_PROOF_MISSING')
                    if mode in ('exit-late-identity','exit-no-capture-zero','exit-no-capture-seven') and (proof['parentCaptured'] or receipt['capturedDescendants']):raise RuntimeError('MISSED_CAPTURE_CONTROL_NOT_APPLIED')
                    if mode in ('exit-tail-zero','exit-tail-seven','exit-late-identity') and not receipt['hostReceipt']['tailBoundaryForced']:raise RuntimeError('TAIL_BOUNDARY_NOT_FORCED')
                else:
                    if not receipt['rootExitEvidenceError'] or receipt['status']=='passed' or receipt['rootExitProof'] is not None:raise RuntimeError('INVALID_EXIT_EVIDENCE_ACCEPTED')
                    if mode=='exit-missing-seven' and (receipt['rootExitCode']!=7 or not any(x.startswith('ROOT_EXIT_EVIDENCE:') for x in receipt['secondaryFailures'])):raise RuntimeError('ROOT7_EVIDENCE_FIRST_CAUSE_LOST')
                    if mode=='exit-cancel-missing' and not receipt['cancelled']:raise RuntimeError('CANCELLATION_NOT_ACTUAL')
            if mode not in ('unobserved-settlement','start-failure') and receipt["ownedSettlement"] != "complete":
                raise RuntimeError("OWNED_EXIT_NOT_OBSERVED:" + mode)
            if mode == "argv":
                actual = json.loads(Path(receipt["stdout"]["path"]).read_text(encoding="utf-8"))
                if actual != ["space value", "中文", 'literal"quote', ""]:
                    raise RuntimeError("ARGV_LITERAL_MISMATCH")
            if mode == "nonzero-child-pipe" and receipt["observedRootExitCode"] != 7:
                raise RuntimeError("FIRST_EXIT_LOST")
            if mode == 'continuous':
                data=Path(receipt['stdout']['path']).read_bytes()
                if not data.startswith(b'tick\n') or not receipt['stdout']['partial']:
                    raise RuntimeError('CONTINUOUS_PREFIX_LOST')
            if mode == 'nonzero' and (receipt['stdout']['partial'] or receipt['stderr']['partial']):
                raise RuntimeError('COMPLETE_NONZERO_LOG_MARKED_PARTIAL')
            if mode in ('cap-exact','cap-plus-one') and receipt['stdout']['length']!=32768:
                raise RuntimeError('CAP_BOUNDARY_LENGTH')
            if mode == 'cancel' and (not receipt['cancelled'] or not Path(receipt['stdout']['path']).read_bytes().startswith(b'tick\n')):
                raise RuntimeError('CANCELLATION_NOT_PROVEN')
            if mode=='nonzero-child-overflow' and (receipt['observedRootExitCode']!=7 or 'LOG_LIMIT' not in receipt['secondaryFailures']):
                raise RuntimeError('EARLY_NONZERO_ORDER_LOST')
            if mode in ('overflow','cap-plus-one','nonzero-child-overflow','cancel') and 'DEADLINE' in receipt['secondaryFailures']:
                raise RuntimeError('UNREACHED_DEADLINE_REPORTED')
            if mode.startswith('compile-') and (receipt['processReceipt']['observedRootExitCode']!=7 or not receipt['secondaryFailures']):
                raise RuntimeError('STAGE_PRIMARY_FAILURE_LOST')
    finally:
        if neighbor.poll() is None:
            neighbor.terminate()
        neighbor_exit=neighbor.wait(timeout=5)
        (directory/'neighbor-exit.json').write_text(json.dumps({'pid':neighbor.pid,'actualWaitExitCode':neighbor_exit}),encoding='utf-8')
    return {"schema": "process-checks/v1", "passed": True, "cases": results}


def root_exit_checks(repo,directory,pwsh,python,jdk,cache):
    matrix=[('exit-tail-zero',None),('exit-tail-seven','NONZERO_EXIT'),('exit-late-identity',None),('exit-no-capture-zero',None),('exit-no-capture-seven','NONZERO_EXIT')]
    matrix.extend((name,'ROOT_EXIT_EVIDENCE:') for name in ('exit-missing-event','exit-corrupt-event','exit-wrong-pid','exit-wrong-time','exit-wrong-code','exit-missing-identity','exit-corrupt-identity','exit-summary-mismatch','exit-date-coercion','exit-root-is-host'))
    matrix.extend([('exit-missing-seven','NONZERO_EXIT'),('exit-cancel-missing','CANCELLED'),('exit-budget-missing','DEADLINE')])
    return process_checks(repo,directory,pwsh,python,jdk,cache,matrix)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture")
    parser.add_argument("--wait-gate")
    parser.add_argument("--check-tools")
    parser.add_argument("--out")
    parser.add_argument("--check-processes")
    parser.add_argument("--check-root-exits")
    parser.add_argument("--repo")
    parser.add_argument("--pwsh")
    parser.add_argument("--python")
    parser.add_argument("--jdk")
    parser.add_argument("--cache")
    args, remaining = parser.parse_known_args()
    if args.fixture:
        if args.wait_gate:
            gate=Path(args.wait_gate)
            if any(p.rstrip(' .').casefold() in ('.testagent','.git') for p in gate.parts):raise RuntimeError('FORBIDDEN_GATE')
            end=time.monotonic()+15
            while not gate.is_file():
                if time.monotonic()>=end:raise RuntimeError('GATE_DEADLINE')
                time.sleep(.01)
        return fixture(args.fixture, remaining[1:] if remaining[:1] == ["--"] else remaining)
    if args.check_processes or args.check_root_exits:
        checker=root_exit_checks if args.check_root_exits else process_checks
        result = checker(Path(args.repo), Path(args.check_root_exits or args.check_processes), args.pwsh, args.python, args.jdk, args.cache)
        with Path(args.out).open("x", encoding="utf-8") as output:
            json.dump(result, output, ensure_ascii=False, indent=2)
        return 0
    if not args.check_tools or not args.out:
        parser.error("explicit owned directory and fresh output required")
    result = tool_checks(Path(args.check_tools))
    with Path(args.out).open("x", encoding="utf-8") as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    return 0


if __name__ == "__main__":
    sys.exit(main())
