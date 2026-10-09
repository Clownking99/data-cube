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
import xml.etree.ElementTree as ET
import ctypes
from ctypes import wintypes


def fixture(mode, arguments):
    if mode == "argv":
        sys.stdout.write(json.dumps(arguments, ensure_ascii=False))
        return 0
    if mode == "normal":
        os.write(1, b"stdout\n"); os.write(2, b"stderr\n")
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
    if mode == "continuous":
        while True:
            os.write(1, b"tick\n"); time.sleep(.01)
    if mode in ("child-pipe", "nonzero-child-pipe"):
        subprocess.Popen([sys.executable, "-I", "-S", __file__, "--fixture", "sleep"])
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
    with tempfile.TemporaryDirectory(dir=directory, prefix="tools-") as temporary:
        root = Path(temporary); xml = root / "xml"; xml.mkdir()
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
            file.write_text(body, encoding="utf-8"); rejects(name, lambda: module.xml_results(xml))
        file.unlink(); rejects("zero-xml", lambda: module.xml_results(xml))
        repo = root / "repo"; (repo / "src" / "legitimate" / "build").mkdir(parents=True)
        source = repo / "src" / "legitimate" / "build" / "A.java"; source.write_text("class A {}")
        (repo / "test").mkdir(); (repo / "test" / "A.java").write_text("class A {}")
        paths = ["src/legitimate/build/A.java", "test/A.java"]
        before = module.snapshot(str(repo), paths, "a" * 40)
        module.verify(before, module.snapshot(str(repo), [x.replace("/", "\\") for x in paths], "a" * 40))
        cases.append({"name": "windows-backslash-and-build-package", "passed": True})
        source.write_text("class A {int changed;}")
        rejects("input-change", lambda: module.verify(before, module.snapshot(str(repo), paths, "a" * 40)))
        extra = source.with_name("Ignored.java"); extra.write_text("class Ignored {}")
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
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())

    def assign(self, process):
        if not self.api.AssignProcessToJobObject(self.handle, wintypes.HANDLE(int(process._handle))):
            process.kill(); process.wait(timeout=5)
            raise ctypes.WinError(ctypes.get_last_error())

    def terminate(self):
        if not self.api.TerminateJobObject(self.handle, 124):
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self):
        try:
            self.terminate()
        finally:
            self.api.CloseHandle(self.handle)


def process_checks(repo, directory, pwsh, python, jdk, cache):
    directory.mkdir(exist_ok=False)
    results = []
    neighbor = subprocess.Popen([python, "-I", "-S", str(Path(__file__).absolute()), "--fixture", "sleep"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for mode, expected in (("normal", None), ("argv", None), ("nonzero", "NONZERO_EXIT"),
                               ("dual", None), ("continuous", "DEADLINE"), ("overflow", "LOG_LIMIT"),
                               ("child-pipe", "DEADLINE"), ("nonzero-child-pipe", "NONZERO_EXIT")):
            name = "g11-synthetic-" + directory.name + "-" + mode
            evidence = repo / "docs/superpowers/verification/evidence" / name
            argv = [pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(repo / "scripts/verification/run-stage.ps1"),
                    "-Repo", str(repo), "-EvidenceRoot", str(evidence), "-Jdk", jdk, "-Cache", cache,
                    "-Pwsh", pwsh, "-Python", python, "-Mode", "fixture", "-Fixture", mode,
                    "-DeadlineMs", "7000", "-SettleMs", "1500", "-StreamCap", str(32768 if mode == "overflow" else 33554432)]
            owner = OuterOwner(); process = None; failure = None; started = time.monotonic()
            out_path, err_path = directory / (mode + "-outer-stdout.log"), directory / (mode + "-outer-stderr.log")
            try:
                with out_path.open("xb") as out, err_path.open("xb") as err:
                    process = subprocess.Popen(argv, cwd=repo, stdout=out, stderr=err)
                    owner.assign(process)
                    try:
                        code = process.wait(timeout=22)
                    except subprocess.TimeoutExpired:
                        failure = "OUTER_DEADLINE"; owner.terminate()
                        code = process.wait(timeout=5)
            finally:
                owner.close()
            receipts = list(evidence.glob("*/stage-result.json"))
            receipt = json.loads(receipts[0].read_text(encoding="utf-8-sig")) if len(receipts) == 1 else None
            record = {"mode": mode, "argv": argv, "outerExitCode": code, "outerFailure": failure,
                      "elapsedSeconds": time.monotonic()-started, "receipt": receipt,
                      "neighborStillRunning": neighbor.poll() is None}
            results.append(record)
            (directory / (mode + "-check.json")).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
            if failure or receipt is None or receipt["primaryFailure"] != expected or not record["neighborStillRunning"]:
                raise RuntimeError("PROCESS_CHECK_FAILED:" + mode)
            if receipt["ownedSettlement"] != "complete":
                raise RuntimeError("OWNED_EXIT_NOT_OBSERVED:" + mode)
            if mode == "argv":
                actual = json.loads(Path(receipt["stdout"]["path"]).read_text(encoding="utf-8"))
                if actual != ["space value", "中文", 'literal"quote', ""]:
                    raise RuntimeError("ARGV_LITERAL_MISMATCH")
            if mode == "nonzero-child-pipe" and receipt["observedRootExitCode"] != 7:
                raise RuntimeError("FIRST_EXIT_LOST")
    finally:
        if neighbor.poll() is None:
            neighbor.terminate()
        neighbor.wait(timeout=5)
    return {"schema": "process-checks/v1", "passed": True, "cases": results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture")
    parser.add_argument("--check-tools")
    parser.add_argument("--out")
    parser.add_argument("--check-processes")
    parser.add_argument("--repo")
    parser.add_argument("--pwsh")
    parser.add_argument("--python")
    parser.add_argument("--jdk")
    parser.add_argument("--cache")
    args, remaining = parser.parse_known_args()
    if args.fixture:
        return fixture(args.fixture, remaining[1:] if remaining[:1] == ["--"] else remaining)
    if args.check_processes:
        result = process_checks(Path(args.repo), Path(args.check_processes), args.pwsh, args.python, args.jdk, args.cache)
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
