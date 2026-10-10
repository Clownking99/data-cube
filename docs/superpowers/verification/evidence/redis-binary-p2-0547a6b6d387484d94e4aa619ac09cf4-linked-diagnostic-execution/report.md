# Single diagnostic outcome — not formal acceptance

Exactly one actual redis-linked command executed. Original engineering linked failure remains failed and all 980 frozen files rehashed unchanged. No Gradle, other probe, shared-tool edits, retry of actual probe, or threshold change.

Outer: actual exit 0, 3.578s, streams EOF, no overflow/error, independent Job before/after empty and actualJobQueryObservedEmpty true. Inner diagnostic: passed, Java root 11540 exit 0, host 33192 exit 0, no termination request, ownedSettlement complete. 105 Job snapshots retained, no cap overflow, two held identities, zero termination events.

Decision query-04: before tick 8551780191411, after tick 8551780191496; members=[], identities=[], hostExited=true. No termination branch taken. query-03 and query-06 also empty.

Observed order (Stopwatch frequency 10000000): Java root start 2026-10-10T09:30:33.8065786Z; root exit event tick 8551778427004, host-relative elapsed1309ms. Host receipt elapsed1322ms. Last hostExited=false sample: query-01 ticks8551779568575..8551779568778, members[33192], held exitObserved=false. Next query-01 ticks8551779725131..8551779725528 returned members[33192], but subsequent held-handle read observed exit0 and hostExited=true. First grace query-02 ticks8551780178812..8551780178954 returned empty. Root therefore exited before host exit was observed. Exact host OS exit timestamp was not recorded; query ticks bracket the queries, not subsequent handle-property reads, so they are not exact host-exit bounds.

This diagnostic observes a PID returned by a Job query and then an exited held handle. Query and property reads are sequential and may straddle exit. It does not establish which member triggered the original failure, nor prove that failure was false. Extra observation and held handles may change scheduling. No stability or formal linked acceptance claim.

Preparation v1 retained; v2 restored failureTick initialization and invocation-local overflow state. Initial pure assertion fixture error preserved and corrected before acceptance. First outer entry failed Python compilation before any controller statements/child launch (nonlocal without function binding); original retained, corrected fresh entry compiled before launch. It did not execute probe and does not count as an actual linked attempt.

Actual outer-controller.py reuses the frozen run-owned.py Pump and OuterOwner, 150s deadline, 1MiB per outer stream, exact original bounded ownership body adapted to top-level variables. v2 wrapper binds original argv/environment/image/compiled probe and uses a fresh evidence cwd. All 51 preparation inputs plus outer entry/tool bindings unchanged before/after. Original stdout247bytes and stderr184bytes hashes match the failed run. Source commit84a4855c is separate from this diagnostic and does not imply P2 acceptance.
