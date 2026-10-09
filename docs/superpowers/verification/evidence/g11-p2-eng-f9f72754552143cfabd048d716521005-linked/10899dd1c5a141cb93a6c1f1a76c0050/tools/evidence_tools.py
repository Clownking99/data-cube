"""Strict, dependency-free receipts for one admitted verification run."""
import argparse
import hashlib
import json
import os
import re
import sys
import stat as statmod
from pathlib import Path
import xml.etree.ElementTree as ET


class Refusal(ValueError):
    pass


ROOTS = {"src", "test", "buildSrc", "resources", "datacube-brand-assets", "drivers",
         "gradle", ".github", "scripts"}
FILES = {"build.gradle", "settings.gradle", "gradle.properties", "README.md", "gradlew", "gradlew.bat"}
INPUT_DIRS = ("src", "test", "buildSrc", "resources", "datacube-brand-assets/assets",
              "drivers", "gradle", ".github/workflows", "scripts/verification")


def disk_inventory(repo):
    """Walk admitted roots only; never inspect excluded children or follow links."""
    found = set()
    def walk(directory):
        no_links(directory)
        with os.scandir(directory) as entries:
            for entry in entries:
                # Name-only pruning must precede any child metadata access.
                if entry.name.casefold() in {".testagent", ".git"}:
                    continue
                candidate = str(Path(entry.path).relative_to(repo)).replace("\\", "/")
                if candidate in {"buildSrc/build", "buildSrc/.gradle", "scripts/verification/__pycache__"}:
                    continue
                relative = normalize(str(Path(entry.path).relative_to(repo)))
                child = repo / relative
                no_links(child)
                if entry.is_dir(follow_symlinks=False):
                    walk(child)
                elif entry.is_file(follow_symlinks=False):
                    found.add(relative)
                else:
                    raise Refusal("INVALID_INPUT_KIND:" + relative)
    for relative in INPUT_DIRS:
        root = repo / relative
        no_links(root)
        if root.exists():
            walk(root)
    for relative in FILES:
        file = repo / relative
        no_links(file)
        if file.exists():
            if not file.is_file():
                raise Refusal("INVALID_INPUT_KIND:" + relative)
            found.add(relative)
    return found


def object_pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise Refusal("DUPLICATE_JSON_KEY")
        out[key] = value
    return out


def components(value):
    if not isinstance(value, str) or not value or any(ord(c) < 32 for c in value):
        raise Refusal("INVALID_PATH")
    text = value.replace("\\", "/")
    if text.startswith("//"):
        raise Refusal("DEVICE_OR_NETWORK_PATH")
    body = text[2:] if re.match(r"^[A-Za-z]:/", text) else text
    if any(c in body for c in ':*?<>|"'):
        raise Refusal("INVALID_PATH")
    parts = [p for p in body.split("/") if p]
    for part in parts:
        if part in (".", "..") or part.endswith((" ", ".")):
            raise Refusal("INVALID_PATH_COMPONENT")
        if part.casefold() in (".testagent", ".git", ".g10-verify-blobs.ps1"):
            raise Refusal("FORBIDDEN_PATH")
        if re.fullmatch(r"(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?", part):
            raise Refusal("DEVICE_PATH")
    return text


def admitted_path(value):
    components(value)  # This must precede even abspath/stat.
    if not os.path.isabs(value):
        raise Refusal("ABSOLUTE_PATH_REQUIRED")
    return Path(os.path.abspath(value))


def load(path):
    path = admitted_path(path)
    no_links(path)
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=object_pairs)


def normalize(value):
    components(value)
    if not isinstance(value, str) or not value or any(ord(c) < 32 for c in value):
        raise Refusal("INVALID_PATH")
    value = value.replace("\\", "/")
    parts = value.split("/")
    if value.startswith("/") or any(p in ("", ".", "..") for p in parts) or any(c in value for c in ':*?<>|'):
        raise Refusal("INVALID_PATH")
    if any(p.casefold() == ".testagent" for p in parts) or ".g10-verify-blobs.ps1" in value.casefold():
        raise Refusal("FORBIDDEN_PATH")
    if parts[0] not in ROOTS and value not in FILES:
        raise Refusal("UNKNOWN_INPUT")
    if parts[0] == "scripts" and not value.startswith("scripts/verification/"):
        raise Refusal("UNKNOWN_INPUT")
    if parts[0] == ".github" and not value.startswith(".github/workflows/"):
        raise Refusal("UNKNOWN_INPUT")
    if parts[0] == "datacube-brand-assets" and not value.startswith("datacube-brand-assets/assets/"):
        raise Refusal("UNKNOWN_INPUT")
    return value


def no_links(path):
    path = admitted_path(str(path))
    for node in (*reversed(path.parents), path):
        try:
            stat = node.lstat()
        except FileNotFoundError:
            continue
        if statmod.S_ISLNK(stat.st_mode) or getattr(stat, "st_file_attributes", 0) & 0x400:
            raise Refusal("REPARSE_PATH")


def snapshot(repo, paths, commit, optional_absent=None):
    repo = admitted_path(repo)
    if not isinstance(paths,list):
        raise Refusal("INVALID_INPUT_LIST")
    for value in paths:
        normalize(value)
    no_links(repo)
    if not isinstance(paths, list) or not repo.is_dir() or not isinstance(commit, str) or not re.fullmatch(r"[0-9a-fA-F]{40}", commit):
        raise Refusal("INVALID_BINDING")
    seen, entries = set(), []
    for value in paths:
        relative = normalize(value)
        if relative.casefold() in seen:
            raise Refusal("DUPLICATE_INPUT")
        seen.add(relative.casefold())
        file = repo / relative
        no_links(file)
        if not file.is_file():
            raise Refusal("MISSING_INPUT:" + relative)
        digest = hashlib.sha256()
        length = 0
        with file.open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                length += len(chunk)
                digest.update(chunk)
        entries.append({"path": relative, "length": length, "sha256": digest.hexdigest()})
    if not entries:
        raise Refusal("EMPTY_INPUTS")
    actual = disk_inventory(repo)
    if actual != {entry["path"] for entry in entries}:
        raise Refusal("INPUT_SET_MISMATCH:" + json.dumps({"unknown": sorted(actual - {entry["path"] for entry in entries}), "missing": sorted({entry["path"] for entry in entries} - actual)}))
    optional_absent = [] if optional_absent is None else optional_absent
    if not isinstance(optional_absent, list) or len(set(optional_absent)) != len(optional_absent):
        raise Refusal("INVALID_OPTIONAL_INPUTS")
    for value in optional_absent:
        if normalize(value) not in FILES or value in actual:
            raise Refusal("OPTIONAL_INPUT_APPEARED")
    return {"schema": "input-binding/v1", "testedCommit": commit.lower(),
            "optionalAbsent": sorted(optional_absent), "files": sorted(entries, key=lambda x: x["path"])}


def verify(before, after):
    def inventory(binding):
        if not isinstance(binding, dict) or set(binding) != {"schema", "testedCommit", "files", "optionalAbsent"} or not isinstance(binding.get("testedCommit"), str) or not re.fullmatch(r"[0-9a-fA-F]{40}", binding["testedCommit"]):
            raise Refusal("INVALID_BINDING")
        if binding.get("schema") != "input-binding/v1":
            raise Refusal("INVALID_BINDING_SCHEMA")
        absent=binding['optionalAbsent']
        if not isinstance(absent,list) or any(not isinstance(x,str) or normalize(x) not in FILES for x in absent) or len({x.casefold() for x in absent})!=len(absent):
            raise Refusal('INVALID_OPTIONAL_INPUTS')
        entries = binding.get("files")
        if not isinstance(entries, list) or not entries:
            raise Refusal("EMPTY_INPUTS")
        result = {}
        for item in entries:
            if not isinstance(item, dict) or set(item) != {"path", "length", "sha256"}:
                raise Refusal("UNKNOWN_INPUT_FIELD")
            path = normalize(item["path"])
            if path.casefold() in result:
                raise Refusal("DUPLICATE_INPUT")
            if type(item["length"]) is not int or item["length"] < 0 or not isinstance(item["sha256"],str) or not re.fullmatch(r"[0-9a-fA-F]{64}", item["sha256"]):
                raise Refusal("INVALID_INPUT_IDENTITY")
            result[path.casefold()] = (item["length"], item["sha256"].lower())
        return result
    left, right = inventory(before), inventory(after)
    if before.get("testedCommit") != after.get("testedCommit") or before["optionalAbsent"] != after["optionalAbsent"] or left != right:
        raise Refusal("INPUT_CHANGED")
    return {"schema": "input-verification/v1", "verified": True, "count": len(left)}


def types(binding):
    verify(binding, binding)
    mappings = [{"source": normalize(x["path"]),
                 "type": normalize(x["path"])[5:-5].replace('/', '.')} for x in binding["files"]
                if normalize(x["path"]).startswith("test/") and normalize(x["path"]).endswith(".java")]
    if not mappings or len({x["type"].casefold() for x in mappings}) != len(mappings):
        raise Refusal("INVALID_TEST_INVENTORY")
    return {"schema": "test-types/v1", "sourceCount": len(mappings), "typeCount": len(mappings), "mappings": mappings}


def xml_results(directory, file_cap=16*1024*1024, total_cap=128*1024*1024):
    directory = admitted_path(str(directory))
    if any(p.casefold() == ".testagent" for p in directory.parts):
        raise Refusal("FORBIDDEN_PATH")
    no_links(directory)
    files = sorted(directory.glob("TEST-*.xml"))
    if not files or len(files) > 2000:
        raise Refusal("INVALID_XML_FILE_COUNT")
    result = {"schema": "test-results/v1", "suites": 0, "tests": 0, "failures": 0,
              "errors": 0, "skipped": 0, "cases": [], "duplicateDisplayNames": [], "files": []}
    seen_suites, total = set(), 0
    for file in files:
        no_links(file)
        size = file.stat().st_size
        total += size
        if size > file_cap or total > total_cap:
            raise Refusal("XML_BYTE_LIMIT")
        data = file.read_bytes()
        if len(data) != size:
            raise Refusal("XML_CHANGED_DURING_READ")
        text = data.decode("utf-8-sig")
        if re.search(r"<!\s*(DOCTYPE|ENTITY)\b", text, re.I):
            raise Refusal("XML_ENTITY_DECLARATION")
        suite = ET.fromstring(text)
        if suite.tag != "testsuite" or not suite.get("name"):
            raise Refusal("INVALID_XML_SCHEMA")
        if any(child.tag not in {"properties", "testcase", "system-out", "system-err"} for child in suite):
            raise Refusal("INVALID_XML_SCHEMA")
        if suite.get("name") in seen_suites:
            raise Refusal("DUPLICATE_SUITE")
        seen_suites.add(suite.get("name"))
        counts = {}
        for field in ("tests", "failures", "errors", "skipped"):
            value = suite.get(field, "")
            if not re.fullmatch(r"[0-9]+", value):
                raise Refusal("INVALID_XML_COUNT")
            counts[field] = int(value)
        cases = suite.findall("testcase")
        observed = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
        names = {}
        for ordinal, case in enumerate(cases):
            if not case.get("name") or not case.get("classname"):
                raise Refusal("MISSING_CASE_IDENTITY")
            marks = [case.findall(x) for x in ("failure", "error", "skipped")]
            if sum(map(len, marks)) > 1:
                raise Refusal("CONTRADICTORY_CASE")
            for field, nodes in zip(("failures", "errors", "skipped"), marks):
                observed[field] += len(nodes)
            name = (case.get("classname"), case.get("name"))
            if name in names:
                result["duplicateDisplayNames"].append({"file": file.name, "class": name[0], "name": name[1], "ordinals": [names[name], ordinal]})
            else:
                names[name] = ordinal
            status = "failure" if marks[0] else "error" if marks[1] else "skipped" if marks[2] else "passed"
            node = next((x[0] for x in marks if x), None)
            result["cases"].append({"file": file.name, "ordinal": ordinal, "suite": suite.get("name"),
                                    "class": name[0], "name": name[1], "status": status,
                                    "reason": ET.tostring(node, encoding="unicode") if node is not None else None})
            if len(result["cases"]) > 100000:
                raise Refusal("XML_CASE_LIMIT")
        if counts != observed:
            raise Refusal("XML_COUNT_MISMATCH")
        for key in observed:
            result[key] += observed[key]
        result["suites"] += 1
        result["files"].append({"path": file.name, "length": size, "sha256": hashlib.sha256(data).hexdigest()})
    if result["tests"] == 0:
        raise Refusal("ZERO_TEST_CASES")
    result["passed"] = result["tests"]-result["failures"]-result["errors"]-result["skipped"]
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("snapshot", "verify", "types", "xml"))
    parser.add_argument("--spec")
    parser.add_argument("--before")
    parser.add_argument("--after")
    parser.add_argument("--inputs")
    parser.add_argument("--out", required=True)
    parser.add_argument("--owned-root", required=True)
    args = parser.parse_args()
    # Admit every caller-supplied path before the first filesystem access.
    out = admitted_path(args.out)
    owned = admitted_path(args.owned_root)
    for value in (args.spec, args.before, args.after, args.inputs):
        if value is not None:
            input_path=admitted_path(value)
            if os.path.commonpath((str(owned),str(input_path))) != str(owned):
                raise Refusal("RECEIPT_INPUT_OUTSIDE_OWNED_SCOPE")
    if os.path.commonpath((str(owned), str(out))) != str(owned) or out == owned:
        raise Refusal("OUTPUT_OUTSIDE_OWNED_SCOPE")
    no_links(out)
    if any(p.casefold() == ".testagent" for p in out.parts) or out.exists():
        raise Refusal("OUTPUT_COLLISION_OR_FORBIDDEN")
    try:
        if args.command == "snapshot":
            spec = load(args.spec)
            if set(spec) != {"repo", "paths", "testedCommit", "optionalAbsent"}:
                raise Refusal("UNKNOWN_SPEC_FIELD")
            result = snapshot(spec["repo"], spec["paths"], spec["testedCommit"], spec["optionalAbsent"])
        elif args.command == "verify":
            result = verify(load(args.before), load(args.after))
        elif args.command == "types":
            result = types(load(args.inputs))
        else:
            spec = load(args.spec)
            if set(spec) != {"directory", "ownedRoot"}:
                raise Refusal("UNKNOWN_SPEC_FIELD")
            root, directory = admitted_path(spec["ownedRoot"]), admitted_path(spec["directory"])
            if root != owned or os.path.commonpath((str(root), str(directory))) != str(root):
                raise Refusal("XML_OUTSIDE_OWNED_SCOPE")
            result = xml_results(directory)
        with out.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        return 0
    except (Refusal, ValueError, OSError, ET.ParseError, UnicodeError) as error:
        with out.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump({"schema": "tool-failure/v1", "status": "failed", "kind": str(error)}, stream, ensure_ascii=False)
        return 2


if __name__ == "__main__":
    sys.exit(main())
