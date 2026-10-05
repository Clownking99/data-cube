# Formal launcher external acceptance tools — 2026-10-05

Baseline c4b68c549e923098fb095a1e3dd7bdc1ee548098; product unchanged. Branch codex/formal-launcher-acceptance-20261005. This worker did not start GUI, Gradle, databases, or network requests beyond an exclusively bound loopback rejector. Root owns native acceptance and main integration.

## Design and evidence

Original cfg launches `com.datacube/com.datacube.DataCubeFx`, image version 3.0.0. AppVersion reads packaged resource, not a system-property override; UpdateService therefore performs startup check. UpdateDownloads builds java.net.http HttpClient with default ProxySelector and NEVER redirects. The external Gate is a parent-delegating system classloader with no application class definitions or transformations. JVM constructs it in initPhase3 before launching main. Constructor asserts canonical owned UUID home/run paths and ownership marker before any app configuration can initialize, binds 127.0.0.1 only, and installs a selector returning exactly that HTTP proxy, with no DIRECT/fallback choice. Rejector reads a bounded-time first request line, emits it, and responds 403, never forwarding. No update response/decision is substituted; product handles its real failed update request normally.

The image runtime lacks java.instrument: premain is unsuitable without changing image. Gate jar contains only Gate and its nested selector, all dependencies java.base; `-Xbootclasspath/a` makes it visible before system loader construction. HttpClient probe is a separate classpath jar; constructing HttpClient inside Gate is invalid because SSL provider initialization reenters system loader construction. Gate does not initialize JavaFX or invoke application/UI code.

Retained originals: 001 PS concatenation created one malformed argument (exit1); 002 boot append included HTTP-dependent probe (NoClassDefFoundError); 003 constructor HttpClient reentry (exit1); 004 JAVA_TOOL_OPTIONS java.class.path overwritten by java launcher default (ClassNotFoundException, positive and negative both failed). Final 005: correct home exit0, GATE_READY before PROBE_MAIN, PROXY_SELECT sentinel then actual CONNECT acceptance.invalid:443 and 403; wrong-home exit1 before main. Probe requires a real rejector count and HTTP403, arbitrary IOException is not a pass. No DNS request for sentinel is needed because HTTPS CONNECT targets our explicit proxy. Raw paths, PID, UTC, argv/environment injection, three image hashes, stderr/stdout and exit codes are retained under raw. These prove the mirror runtime and scoped transport, not original exe success yet.

## Commands for root

Run in PowerShell 7 (.NET ProcessStartInfo.ArgumentList is required). Use absolute tool path. Root can compile once; Prepare pins current original DataCube.exe/app/DataCube.cfg/runtime/lib/modules hashes and gate.jar hash.

```powershell
$tool='C:/Users/hetia/.codex/worktrees/metadata-search-cancellation/朝花夕拾/docs/superpowers/verification/evidence/formal-launcher-20261005-worker/FormalLauncher.ps1'
& $tool -Action Compile
$profile = & $tool -Action Prepare -ImageRoot 'D:/Projects/朝花夕拾/build/jpackage/DataCube'
& $tool -Action Probe -Profile $profile
& $tool -Action Probe -Profile $profile -WrongHome
& $tool -Action Launch -Profile $profile
```

Launch must be executed by root with require_escalated in interactive desktop. Observer waits in that shell until actual process exit; run it with an exec yield, then use separate native desktop calls. It prints RUN and PID before waiting. It clears inherited Java/JDK/Gradle/DataCube/live/database environment entries in the child only; USERPROFILE/HOME/APPDATA/LOCALAPPDATA point at the owned profile. It replaces JAVA_TOOL_OPTIONS with exact reviewable JVM options. Does not change system proxy, firewall, launcher cfg, modules or exe. No background helper is required; loopback daemon belongs to app JVM and exits with it. Do not modify memory settings or run install/update actions during this acceptance, because those can edit launcher cfg.

After normal exit, repeat Launch using SAME profile; a fresh run directory is always created. After each exit: `& $tool -Action Finish -Profile $profile -Run '<printed RUN>'`. Finish only freezes hashes, never asserts GUI pass. Explicit emergency `Stop` checks exact profile/run/PID executable and process creation timestamp; creates forced-stop evidence before stopping only that PID. Forced stop is never counted as normal exit. Do not use Stop while a successful native close is possible.

## Native sequence and limits

Root: capture current splash/startup with actual exe PID; verify gate READY, real startup api.github.com CONNECT rejected403 and unchanged three artifacts. Create two offline SQL drafts using native UI and real keyboard input (synthetic SQL/Chinese marker), switch to first draft, close normally through original close guard, confirm exit0 and JVM_SHUTDOWN. Restart SAME profile with original exe, use real recovery entry, inspect both drafts text/order with native screenshots, then close normally. Independently decode only this owned .datacube/sql-drafts profile to corroborate persistence. Observer does not input/fire, reflect business data, invoke guards, inject updates, or alter application main.

Default ProxySelector contains this updater's default HttpClient transport. This is not an OS-level universal socket sandbox: raw sockets, libraries explicitly overriding proxy, or external browser actions are outside this proof; no such paths may be exercised. Installed selector is process-global, and current startup path has no resetting selector. The final formal process's real CONNECT log is required in addition to the same-runtime probe; if no READY or no real reject appears, report unverified isolation and stop safely. No GUI/normal exit/recovery/installation/upgrades/signing/CI/multi-monitor or full release pass claimed by worker.
