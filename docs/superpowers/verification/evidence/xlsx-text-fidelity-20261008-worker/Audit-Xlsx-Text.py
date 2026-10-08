"""Read synthetic packages with an independent XML reader and bundled Xstring decoder."""
import hashlib
import json
import pathlib
import sys
import zipfile
from xml.etree import ElementTree
import openpyxl
from openpyxl.utils.escape import unescape

source = pathlib.Path(sys.argv[1]).resolve()
output = pathlib.Path(sys.argv[2]).resolve()
if output.exists():
    raise RuntimeError("Preserve existing audit")
manifest = source / "expected-text.tsv"
results = []
for line in manifest.read_text(encoding="utf-8-sig").splitlines():
    filename, ref, expected_hex = line.split("\t")
    file = (source / filename).resolve()
    if file.parent != source or file.suffix != ".xlsx":
        raise RuntimeError("Unexpected synthetic artifact path")
    with zipfile.ZipFile(file) as archive:
        sheet = archive.read("xl/worksheets/sheet1.xml")
    source_text = sheet.decode("utf-8-sig", errors="strict")
    if "<!DOCTYPE" in source_text.upper() or "<!ENTITY" in source_text.upper():
        raise RuntimeError("DTD and entity declarations are forbidden")
    document = ElementTree.fromstring(source_text)
    cell = next(
        node for node in document.iter() if node.tag.endswith("}c") and node.attrib.get("r") == ref)
    if cell.attrib.get("t") != "inlineStr":
        raise RuntimeError("Expected text cell")
    text = "".join(node.text or "" for node in cell.iter() if node.tag.endswith("}t"))
    decoded = unescape(text)
    actual_hex = decoded.encode("utf-16-be", errors="surrogatepass").hex().upper()
    results.append({"file": filename, "cell": ref, "expectedUtf16": expected_hex,
                    "actualUtf16": actual_hex, "matched": actual_hex == expected_hex,
                    "fileSha256": hashlib.sha256(file.read_bytes()).hexdigest().upper()})
record = {"reader": "stdlib XML with DTD/entity declarations rejected + openpyxl.utils.escape.unescape", "openpyxlVersion": openpyxl.__version__,
          "nativeExcelAcceptance": False, "passed": bool(results) and all(item["matched"] for item in results),
          "cells": len(results), "results": results}
output.write_text(json.dumps(record, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: value for key, value in record.items() if key != "results"}))
if not record["passed"]:
    sys.exit(1)
