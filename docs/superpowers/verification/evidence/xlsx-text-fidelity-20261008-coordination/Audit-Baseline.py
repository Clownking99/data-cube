import hashlib, json, pathlib, sys, zipfile
from xml.etree import ElementTree
from openpyxl.utils.escape import unescape
import openpyxl
source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
if destination.exists(): raise RuntimeError("Preserve original audit")
cases = json.loads((source / "cases.json").read_text(encoding="utf-8-sig"))
results = []
for case in cases:
    name = case["id"]
    if not name.replace("-", "").isalnum(): raise RuntimeError("Unexpected synthetic id")
    raw = (source / (name + "-sheet.xml")).read_bytes()
    entry = {"id": name, "published": case["published"], "expectedUtf16": case["inputUtf16Hex"],
             "expectedReject": case["expectReject"], "sheetSha256": hashlib.sha256(raw).hexdigest().upper()}
    # Java NIO preserved a Windows reserved name in the original experiment.
    # Its separately mapped alias can be verified without opening the NUL device.
    package = source / ("case-nul.xlsx" if name == "nul" else case["target"])
    entry["packageBytesChecked"] = package.exists()
    if package.exists():
        with zipfile.ZipFile(package) as archive:
            if archive.read("xl/worksheets/sheet1.xml") != raw: raise RuntimeError("ZIP/XML bytes differ")
        entry["packageSha256"] = hashlib.sha256(package.read_bytes()).hexdigest().upper()
    text = raw.decode("utf-8-sig", errors="strict")
    if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper(): raise RuntimeError("Unsafe XML")
    try:
        document = ElementTree.fromstring(text)
        values = [unescape(node.text or "") for node in document.iter() if node.tag.endswith("}t")]
        hexes = [",".join(value.encode("utf-16-be", errors="surrogatepass").hex().upper()[i:i+4]
                           for i in range(0, len(value.encode("utf-16-be", errors="surrogatepass").hex()), 4))
                 for value in values]
        entry.update({"xmlParsed": True, "decodedUtf16": hexes,
                      "passed": not case["expectReject"] and len(hexes) == 2 and all(value == case["inputUtf16Hex"] for value in hexes)})
    except ElementTree.ParseError:
        entry.update({"xmlParsed": False, "passed": False})
    results.append(entry)
record = {"reader": "stdlib XML (DTD/entity declarations forbidden) + openpyxl.utils.escape.unescape",
          "openpyxlVersion": openpyxl.__version__, "nativeExcelAcceptance": False,
          "cases": len(results), "passed": sum(item["passed"] for item in results),
          "failed": sum(not item["passed"] for item in results), "results": results}
destination.write_text(json.dumps(record, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
print(json.dumps({key: value for key, value in record.items() if key != "results"}))
sys.exit(0 if record["failed"] == 9 and record["passed"] == 1 else 1)
