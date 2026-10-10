# Release helper timeout: first-stage review

Base main 3fb6f3ae0b52c22cf02821f4ca1b04f650de82dd; branch codex/release-helper-timeout-20261010. CURRENT and verification-guide read before editing. Original Build and Release run38037623220 / build job114172976047 failed at PortableUpdateHelperTest.run's aggregate20-second wait, 4736completed/1failed/3skipped. Root original log SHA256532e3528356a58ee8b751763fb13779847d0a304d62c5e536c24ccdad787668d confirmed. Original evidence cannot identify startup versus script execution stall. No claim this failure proves cold startup.

Only three test files change: PortableUpdateHelperTest delegates its process run; small UpdateHelperProcess is specific to this synthetic suite; UpdateHelperProcessTest contains seven substantive cases. Production update-helper.ps1 and all original state/image/recovery assertions unchanged. No real installation, DataCube executable launch, service, credentials, profile, network or business file access.

Normal fixture policy:90s from launch attempt through bootstrap acknowledgement, then original20s execution budget,10s physical cleanup budget. OS process.start itself is a synchronous API, so no unconditional hard bound on an indefinitely blocked OS launch is claimed. No timeout retries. Bootstrap ready is necessary for success; actual nonzero code/streams retained. stdin closed. Any live directly owned Process is forcibly terminated and actually waited to observed exit; failure/interruption and cleanup facts recorded. Interrupt status restored by outer finally even for cleanup/diagnostic exceptions. Success/expected nonzero original cases are still checked against original product assertions.

Output reading is bounded to64KiB per stream plus one byte for overflow detection. Overflow is failure with explicit incomplete status and NOT_COMPLETE rather than a misleading full hash. Full raw outputs below limit are preserved as Base64 plus SHA256 in actual XML system-out; overflow retains only bounded prefix and original file length. The custom regression's deliberate overflow is not a successful complete-output receipt.

Seven new regression cases: three deterministic Process/clock models (slow bootstrap exceeds execution budget but passes startup budget; missing ready timeout; exit0 without ready rejected) are explicitly distinguished from actual OS process proofs. Four real Windows PowerShell cases cover nonzero with separate streams, oversize output, stuck actual body with entry marker and controlled execution clock, and actual body interruption. Actual Process object is retained and asserted dead with observed exitValue; no PID relookup used. Stuck test's clock jump is fault injection after actual body entry, not a wall-clock deadline guarantee.

The dedicated stage runner reuses the existing12 frozen tools, Invoke-OwnedProcess, isolated.gradle and offline cache; actual cleanTest/test --rerun-tasks --tests com.datacube.update.*. Shared stage-policy and tools unchanged, no98 controls repeated. Each stage/outer uses new owned runtime/home/temp/build/private Job. Predetermined actual Gradle count1, no stability repeat. First controller preparation failure happened before Gradle because package baseline lay outside receipt owned scope; original refusal RECEIPT_INPUT_OUTSIDE_OWNED_SCOPE retained,87-file manifest976ad62b8ec47794e4971099d897969d09da3a2a582e981be63ecc0f87c16254. Only controller copied baseline into owned scope on fresh UUID; no source/tool change.

Actual targeted result:12suites,92tests,92passed,0skipped,0failures,0errors. Original Portable suite11 cases passed, new helper suite7 cases passed. Actual XML contains18 helper receipts:3 model-only and15 real PowerShell (original11 + new4). All complete-output receipt hashes recomputed from raw Base64; overflow explicitly incomplete. All7 owned stage processes passed strong root/host/EOF proofs, operator actual exit0/private Job observed empty. All880 current engineering inputs match frozen baseline before/after and12 working/package/scope tool identities match. Raw XML, command requests, stdout/stderr/host streams and hashes retained.

Requirement | Evidence
--- | ---
Separate bounded slow bootstrap | controlledSlowBootstrapHasItsOwnBudgetRatherThanConsumingExecutionBudget
Startup timeout and ready-required success | missingBootstrapAcknowledgementTimesOutAndObservesControlledExit; zeroExitWithoutBootstrapAcknowledgementCannotBeReportedAsSuccess
Nonzero exit plus complete streams | nonzeroActualPowerShellRetainsBothRawStreamsAndExitCode
Bounded output and honest incomplete hash | oversizedActualOutputIsBoundedAndNeverGivenACompleteHash
Entered execution timeout physically settles actual Process | stuckExecutionTerminatesActualOwnedPowerShellOnlyAfterBodyEntry
Interruption physically settles before flag restoration | interruptedExecutionRestoresInterruptOnlyAfterActualOwnedProcessSettles
Original update ownership/state/image/recovery contracts | all11 PortableUpdateHelperTest raw XML cases and unchanged assertions
Full update package targeted | exact frozen stage.ps1/controller.py and gradle-update-targeted/request.json; actual exit0/current92-case XML

First-stage only. Full/buildSrc/image/linked not run. No staging/commit/merge/push/tag or agents. Await independent review before complete engineering.
