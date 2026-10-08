"""Read only named G9 worker evidence; produce an independent local receipt."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--worker", required=True)
parser.add_argument("--run", required=True)
parser.add_argument("--freeze", required=True)
parser.add_argument("--out", required=True)
args = parser.parse_args()
worker = Path(args.worker).resolve()
evidence = worker / "docs/superpowers/verification/evidence/g9-table-export-20261008-worker"
out = Path(args.out).resolve()
out.mkdir(parents=True, exist_ok=False)


def within(root, relative):
    rel = Path(relative)
    if rel.is_absolute() or any(p.lower() == ".testagent" or p == ".." for p in rel.parts):
        raise ValueError("Unsafe evidence path")
    result = (root / rel).resolve()
    if not result.is_relative_to(root.resolve()):
        raise ValueError("Evidence escaped root")
    return result


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def load_capture(path, name):
    raw = path.read_bytes()
    (out / name).write_bytes(raw)
    return json.loads(raw.decode("utf-8-sig"))


def verify_entries(root, entries):
    results = []
    for entry in entries:
        path = within(root, entry["path"])
        result = {"path": entry["path"], "bytes": path.stat().st_size, "sha256": sha(path)}
        result["matches"] = result["bytes"] == entry["bytes"] and result["sha256"] == entry["sha256"].upper()
        results.append(result)
    return results


freeze_root = within(evidence, args.freeze)
run_root = within(evidence, args.run)
frozen = load_capture(freeze_root / "manifest.json", "worker-frozen-manifest.json")
artifacts = load_capture(evidence / "p1a-artifact-manifest.json", "worker-artifact-manifest.json")
run_source = load_capture(run_root / "source-at-run/manifest.json", "worker-run-source-manifest.json")
command = load_capture(run_root / "command.json", "worker-command.json")
exit_info = load_capture(run_root / "exit.json", "worker-exit.json")
freeze_verified = verify_entries(freeze_root, frozen)
artifacts_verified = verify_entries(evidence, artifacts)
run_verified = verify_entries(run_root / "source-at-run", run_source)
frozen_map = {e["path"]: e["sha256"].upper() for e in frozen}
run_binding = [{"path": e["path"], "matchesFrozen": frozen_map.get(e["path"]) == e["sha256"].upper()} for e in run_source]
current_binding = []
for entry in frozen:
    current = within(worker, entry["path"])
    current_binding.append({"path": entry["path"], "currentSha256": sha(current), "matchesFrozen": sha(current) == entry["sha256"].upper()})
suites = []
for xml_path in sorted((run_root / "xml").glob("TEST-*.xml")):
    suite = ET.fromstring(xml_path.read_bytes())
    counts = {key: int(suite.attrib.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    cases = suite.findall("testcase")
    actual = {"tests": len(cases), "failures": sum(c.find("failure") is not None for c in cases),
              "errors": sum(c.find("error") is not None for c in cases), "skipped": sum(c.find("skipped") is not None for c in cases)}
    suites.append({"name": suite.attrib["name"], **counts, "testcaseCountsMatch": actual == counts, "xmlSha256": sha(xml_path)})
totals = {key: sum(s[key] for s in suites) for key in ("tests", "failures", "errors", "skipped")}
stdout = (run_root / "stdout.log").read_text(encoding="utf-8-sig", errors="replace")
test_lines = [line for line in stdout.splitlines() if line.startswith("> Task :test") or "BUILD SUCCESSFUL" in line or "BUILD FAILED" in line]
head = subprocess.run(["git", "-C", str(worker), "rev-parse", "HEAD"], check=True, capture_output=True, text=True).stdout.strip()
receipt = {"utc": datetime.now(timezone.utc).isoformat(), "worker": str(worker), "head": head,
           "run": args.run, "freeze": args.freeze, "commandHeadMatches": command["head"] == head,
           "exitCode": exit_info["exitCode"], "frozenFiles": freeze_verified,
           "artifactFiles": artifacts_verified, "runSourceFiles": run_verified, "runToFrozenBinding": run_binding,
           "currentToFrozenBindingAtAudit": current_binding, "suites": suites, "totals": totals, "testLogLines": test_lines,
           "rootRanGradle": False, "scope": "Independent source and raw evidence review; not full/buildSrc/image or live acceptance"}
(out / "root-p1a-verification.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
summary = {"frozenFiles": len(freeze_verified), "artifacts": len(artifacts_verified), "runSources": len(run_verified),
           "hashMismatches": [e["path"] for e in freeze_verified + artifacts_verified + run_verified if not e["matches"]],
           "runBindingMismatches": [e["path"] for e in run_binding if not e["matchesFrozen"]],
           "currentChangesAfterFreeze": [e["path"] for e in current_binding if not e["matchesFrozen"]],
           "suites": len(suites), **totals, "exitCode": exit_info["exitCode"], "testLogLines": test_lines}
print(json.dumps(summary, ensure_ascii=False, indent=2))
if summary["hashMismatches"] or summary["runBindingMismatches"] or any(not s["testcaseCountsMatch"] for s in suites):
    raise SystemExit(1)
