# 2026-10-05 Formal launcher worker preparation

Status: external tooling preparation complete; formal launcher/native acceptance pending root execution. Baseline c4b68c549e923098fb095a1e3dd7bdc1ee548098, dedicated branch codex/formal-launcher-acceptance-20261005, product unchanged.

Scope: original DataCube.exe and unchanged cfg/modules, actual DataCubeFx splash/startup, offline native SQL drafts, normal guarded exit and same-profile recovery. Root exclusively owns desktop and final result. Worker only builds standalone docs tools with JDK25 and executes no-GUI probes through original packaged runtime; no Gradle, live database, external requests, history/profile access, publishing or other threads.

Deliverables: [tool README](evidence/formal-launcher-20261005-worker/README.md), [PowerShell](evidence/formal-launcher-20261005-worker/FormalLauncher.ps1), [Gate](evidence/formal-launcher-20261005-worker/Gate.java), [Probe](evidence/formal-launcher-20261005-worker/Probe.java), frozen gate/probe jars and numbered raw originals/manifest. `classes/` is generated locally and intentionally ignored; packaged jars are reviewed deliverables, not product artifacts.

Preparation evidence: javac/jar completed; final original-runtime positive probe exit0 with constructor gate readiness before probe main and actual loopback HTTP CONNECT403, owned wrong-home negative exit1 before main. All earlier failures retained, detailed README. Three mirror artifact SHA values remain unchanged throughout probes. No unit/Gradle tests invented or reused as fresh evidence. Initial branch write failed due sandbox permission, scoped escalation then succeeded; this did not alter product.

Root review confirmed frozen Gate/Probe/jars/PowerShell before GUI launch; gate SHA256 7FFFE93364AB2DAD5F01BDB46EE46AF3396C4E7A29B026FA53081FB5633DBBEA. Root will independently prepare a new profile against main image, replay positive/negative probes, run actual original launcher and record actual startup CONNECT rejection. Worker does not infer native pass from probe.

Remaining: root native splash/startup, two true-input offline SQL drafts with Chinese marker, close, restart/recovery text/order and final normal exit, independent actual owned workspace decoding, unchanged image artifact hashes, main integration. Installation/upgrade/signing/CI/other databases/OS scaling remain outside this bounded case. Do not update old acceptance evidence or claim full M8/release completion.
