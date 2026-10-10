# Release-helper timeout worker handoff: P2 partial

Source commit: `84a4855c633c0d665ae236383a8c660360d54617` (exactly three test files). Targeted 92 passed; full 4740 passed + 3 expected live skips, zero failures/errors; forced buildSrc8 passed; image passed. Formal linked FAILED with OWNED_DESCENDANT_REQUIRES_TERMINATION. Overall P2 is not passed. Original root/host exit0 does not override Job failure.

One authorized diagnostic did not reproduce the termination, and is observation only. Query-04 empty, inner/outer exit0, no termination; extra logging/held handles may affect scheduling. Original cause remains unknown. First preparation refusal, v1/v2 diagnostic preparation, initial pure-assertion fixture failure and outer-entry compile failure are retained. No actual probe was launched by the compile failure; exactly one actual diagnostic probe ran.

The four manifests below bind 87 preparation-refusal files,137 accepted-targeted files,980 engineering-first-failure files,55 diagnostic files respectively. Original manifests and payloads remain untouched. The engineering success-only finish.py was prepared but NOT executed; the failure seal remains passed=false. Controls98 were bound/reused, zero executed this task; the12 shared verification tools remain unchanged. No repeat stability or tool-fix claim.

- `redis-binary-p2-663bf4c554aa4097b20e32407fb257d9-update-targeted-frozen/manifest.json` SHA-256 `976ad62b8ec47794e4971099d897969d09da3a2a582e981be63ecc0f87c16254`
- `redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted-frozen/manifest.json` SHA-256 `ac1742589bebb51099c4ae1b9ccb655dd5046b03b9296b5f9233a3fedc00d50d`
- `redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering-frozen/failed-manifest.json` SHA-256 `a9a55315e119bf66148c10fd8d04e13648bc789767c535fd7bee4eb08bbc1b9c`
- `redis-binary-p2-0547a6b6d387484d94e4aa619ac09cf4-linked-diagnostic-execution-frozen/manifest.json` SHA-256 `018f40537d4c04ea9e43671dfbf303e96ddd5b1559196a60bb46afc81f49db96`

Exact artifact roots (relative to `docs/superpowers/verification/evidence`):

- `redis-binary-p2-0547a6b6d387484d94e4aa619ac09cf4-linked-diagnostic-execution`
- `redis-binary-p2-0547a6b6d387484d94e4aa619ac09cf4-linked-diagnostic-execution-frozen`
- `redis-binary-p2-0ae879a0104f4d00926148f6f0f617e1-linked`
- `redis-binary-p2-0ae879a0104f4d00926148f6f0f617e1-linked-owner`
- `redis-binary-p2-1d9c1c970f1145ecb9f1c5614929b583-linked-diagnostic-prep-v2`
- `redis-binary-p2-2dc8f6fc36c840cb905a6cc2320f1190-linked-diagnostic-execution`
- `redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted`
- `redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted-frozen`
- `redis-binary-p2-2fecc37fd6c9448ebcc8dcbfc151faa5-update-targeted-stage`
- `redis-binary-p2-481fb7af231c4214898c396d5cb7c9a1-linked`
- `redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering`
- `redis-binary-p2-5570968fcd464e57819c408447327b34-update-engineering-frozen`
- `redis-binary-p2-663bf4c554aa4097b20e32407fb257d9-update-targeted`
- `redis-binary-p2-663bf4c554aa4097b20e32407fb257d9-update-targeted-frozen`
- `redis-binary-p2-663bf4c554aa4097b20e32407fb257d9-update-targeted-stage`
- `redis-binary-p2-8516c9b079c74016943b501417ebbb0e-linked-diagnostic-prep`
- `redis-binary-p2-ae0bd8f009c84d85a566d9fa3d354c51-image`
- `redis-binary-p2-ae0bd8f009c84d85a566d9fa3d354c51-image-owner`
- `redis-binary-p2-b9b0a160efe74909b5bb3f40a50299ec-full`
- `redis-binary-p2-b9b0a160efe74909b5bb3f40a50299ec-full-owner`
- `redis-binary-p2-d3ec01c1f8f742459ec9486aa84f977e-buildsrc`
- `redis-binary-p2-d3ec01c1f8f742459ec9486aa84f977e-buildsrc-owner`

Reusable entries: accepted targeted/controller.py + stage.ps1, engineering/controller.py + run-engineering.ps1 + tools/isolated.gradle and the other eleven frozen tools. Engineering *-spec.json records all actual stage/owner roots. Diagnostic outer-controller.py/diagnose.ps1 are diagnostic-only, not formal P3 entries.

Shortest safe reuse for root's independent main P3:
1. Read actual controller and CURRENT/verification guide. Create a fresh redis-binary-p3-UUID package; copy only controller.py, run-engineering.ps1, config.json, inputs.json, source-identities.json, tool-manifest.json, controls-binding.json, targeted-binding.json, expected-update-cases.json and tools. Never copy old receipts/owner markers/runtime or execute old roots.
2. Rebind config.repo to main checkout; require all three source hashes equal the accepted identities and all twelve tool identities equal the frozen version. Set inputs.testedCommit to actual integrated HEAD and re-snapshot using the unchanged evidence_tools.snapshot(repo,paths,testedCommit,optionalAbsent). Use that current snapshot for accepted-targeted-baseline.json; controller will create baseline-inputs.json and compare it. Preserve old targeted/controls bindings explicitly as reused prior evidence; if P3 scope requires fresh targeted, execute a fresh targeted entry and bind its new accepted manifest instead. Do not relabel reused evidence as newly executed.
3. For P3 root naming only, change controller's generated redis-binary-p2-UUID prefixes to redis-binary-p3-UUID; update any final auditor's name predicate likewise. Stage modes/budgets/argument policy must remain unchanged. The controller runs full/buildsrc/image/linked sequentially and stops on first failure; do not auto-retry. Inspect all current constants, exact expected cases and mappings rather than trusting this prose as a replacement for code review.
4. Freeze a new entry-manifest.json with actual copied/adjusted file hashes (exclude the manifest itself); run the new run-engineering.ps1 only after root approves new scope. Preserve actual specs/raw XML/process receipts and all attempts. The prepared finish.py requires success; it must never seal a failed sequence as passed. Only complete formal stages can authorize root's later main push/tag.

No separate preparation generator was executed this task; setup used inline tool code. The above reuses the existing reviewed entry system and requires only local paths/current-baseline/prefix rebinding, not a new verification framework. Runtime images are excluded from Git and bound by inventories/core identities.

Worker relinquishes Gradle execution permission to root. No further diagnostics, Gradle, merge, push or tag by worker. Evidence commit is local only and does not promote P2 partial to passed.
