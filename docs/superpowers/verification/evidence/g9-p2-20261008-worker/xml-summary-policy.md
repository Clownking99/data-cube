# XML summary receipt policy

The authoritative test counts are `actual-xml-summary.json`, produced by
`Summarize-Xml.py` after the Gradle child completes. This parser checks each
suite's reported test/failure/error/skipped counts against the actual elements.
The launcher's original `test-summary.json` used PowerShell truthiness for
`skipped`; empty `<skipped/>` nodes are false there. It therefore reports zero
skips for the full run. That preliminary convenience summary is retained
unchanged and is not used as acceptance evidence. The fresh full XML and the
independent summary report three skipped live tests, with exact IDs/reasons.

An early summary attempt while the full Gradle child was still running rejected
an empty XML folder (`assert result['suites'] > 0`) and wrote no summary. It is
not a test result. The successful independent summary was created after child
exit 0 and 345 fresh XML files were copied.

The first sandboxed process-status read could not access CIM. A subsequent
explicit read-only process-status call with approved escalation succeeded.
No process or product source was changed by those checks.
