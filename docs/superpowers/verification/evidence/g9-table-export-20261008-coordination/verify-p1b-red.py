"""Independent audit of the named, synthetic P1b baseline RED run only."""
from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

worker = Path("C:/Users/hetia/.codex/worktrees/aed5/朝花夕拾")
base = Path(__file__).resolve().parent
run = worker / "docs/superpowers/verification/evidence/g9-pgdump-20261008-worker/p1b-001-baseline-red"
out = base / "p1b-001-red-review"
out.mkdir(exist_ok=False)


def digest(path):
    raw = path.read_bytes()
    return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest().upper()}


def capture(relative, name):
    raw = (run / relative).read_bytes()
    (out / name).write_bytes(raw)
    return json.loads(raw.decode("utf-8-sig"))


manifest = capture("source-at-run/manifest.json", "worker-run-source-manifest.json")
command = capture("command.json", "worker-command.json")
exit_info = capture("exit.json", "worker-exit.json")
sources = []
for entry in manifest:
    rel = Path(entry["path"])
    if rel.is_absolute() or any(p.lower() == ".testagent" or p == ".." for p in rel.parts):
        raise ValueError("Unsafe source path")
    source_root = (run / "source-at-run").resolve()
    source = (source_root / rel).resolve()
    if not source.is_relative_to(source_root):
        raise ValueError("Source escaped frozen root")
    actual = digest(source)
    sources.append({"path": entry["path"], **actual,
                    "matches": actual["bytes"] == entry["bytes"] and actual["sha256"] == entry["sha256"]})
suite_file = run / "xml/TEST-com.datacube.export.PgDumpRunnerBaselineRedTest.xml"
suite = ET.fromstring(suite_file.read_bytes())
counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
cases = suite.findall("testcase")
actual_counts = {"tests": len(cases), "failures": sum(c.find("failure") is not None for c in cases),
                 "errors": sum(c.find("error") is not None for c in cases), "skipped": sum(c.find("skipped") is not None for c in cases)}
raw_files = []
for relative in ("command.json", "exit.json", "stdout.log", "stderr.log", "source-at-run/manifest.json", "xml/TEST-com.datacube.export.PgDumpRunnerBaselineRedTest.xml"):
    raw_files.append({"path": relative, **digest(run / relative)})
baseline_manifest = json.loads((base / "p1a-012-review/worker-frozen-manifest.json").read_text(encoding="utf-8-sig"))
table_path = "src/com/datacube/export/TableExporter.java"
table_binding = next(s["sha256"] for s in sources if s["path"] == table_path) == next(e["sha256"] for e in baseline_manifest if e["path"] == table_path)
receipt = {"utc": datetime.now(timezone.utc).isoformat(), "worker": str(worker), "run": run.name,
           "commandHead": command["head"], "exitCode": exit_info["exitCode"], "counts": counts,
           "actualTestcaseCounts": actual_counts, "frozenSources": sources, "runFiles": raw_files,
           "tableExporterMatchesAcceptedP1a": table_binding,
           "failedCases": [{"name": c.attrib["name"], "message": c.find("failure").attrib["message"]} for c in cases if c.find("failure") is not None],
           "helperReceipt": suite.findtext("system-out"), "helperReceiptMeaning": "Fixture finally forced and observed root exit; not product cleanup proof",
           "argvRedScope": "First format flag assertion failed; later assertions and database literal binding were not proven by this RED",
           "rootRanGradle": False, "result": "Baseline RED audit only; P1b GREEN not accepted"}
(out / "root-p1b-red-verification.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"frozenSources": len(sources), "sourceMatches": all(s["matches"] for s in sources),
                  "runFiles": len(raw_files), **counts, "actualCountsMatch": counts == actual_counts,
                  "tableExporterMatchesP1a": table_binding, "exitCode": exit_info["exitCode"]}, indent=2))
if not all(s["matches"] for s in sources) or counts != actual_counts or not table_binding:
    raise SystemExit(1)
