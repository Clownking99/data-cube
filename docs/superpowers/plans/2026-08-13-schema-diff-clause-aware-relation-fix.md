# Schema Diff Clause-Aware Relation Classification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent `FROM`, `USING`, and `ON` tokens in procedural/value grammar from being mistaken for relation-source introducers while preserving proven relation retargeting and cross-owner convergence.

**Architecture:** Keep the single shared `SqlScopeAwareOwnerRewriter` scanner used by both PostgreSQL and Oracle. Replace global keyword classification with statement/clause-aware predicates: `FROM` must belong to query or `DELETE FROM`, `USING` must belong to `MERGE`/`DELETE` source grammar, and trigger `ON` must be the unique header token before the parsed body; ambiguous grammar throws so readers retain original definitions at LOW confidence and planners stay manual.

**Tech Stack:** Java 21, JUnit 5, Gradle, provider JDBC snapshot fixtures, Schema Diff projector/engine/planner/render pipeline.

## Global Constraints

- Work directly on `main` as authorized; create independent commits only, without amend, push, tag, live database access, or workflow dispatch.
- Do not read, modify, stage, or commit `.testagent/`.
- Use strict RED -> GREEN TDD and retain positives for `INTO`, CTE, `LATERAL`, `DELETE FROM`, `INSERT INTO`, and `MERGE ... USING`.
- If relation grammar cannot be completely proven, throw and let the reader classify the object LOW/manual.

---

### Task 1: Direct clause-classification regression tests

**Files:**
- Modify: `test/com/datacube/provider/postgres/PgSchemaChangeRendererTest.java`
- Modify: `test/com/datacube/provider/oracle/OracleSchemaChangeRendererTest.java`

**Interfaces:**
- Consumes: `PgSchemaChangeRenderer.comparisonDefinition(String, ObjectType, String)` and `OracleSchemaChangeRenderer.comparisonDefinition(String, String)`.
- Produces: Regression fixtures proving procedural chains remain byte-for-byte owner-stable and proven relation sources use comparison placeholders.

- [x] **Step 1: Write failing tests**

  Add PostgreSQL and Oracle routine/trigger definitions containing dynamic `EXECUTE ... USING owner.binding.field`, `EXTRACT(... FROM owner.record.field)`, `JOIN ... USING (owner.column)`, and trigger-body `JOIN ... ON owner.record.field = ...`; assert each procedural chain remains unchanged. In the same fixtures assert query `FROM`/`JOIN`, `DELETE FROM`, `MERGE ... USING`, CTE, `LATERAL`, `INSERT INTO`, and trigger-header `ON` owners retarget.

- [x] **Step 2: Verify RED**

  Run:

  ```powershell
  .\gradlew.bat test --tests com.datacube.provider.postgres.PgSchemaChangeRendererTest --tests com.datacube.provider.oracle.OracleSchemaChangeRendererTest --rerun-tasks --no-build-cache --no-daemon --console=plain
  ```

  Expected: assertions fail because at least one procedural/value owner is replaced by the comparison placeholder.

### Task 2: Reader-to-reread regression tests

**Files:**
- Modify: `test/com/datacube/provider/postgres/PgSchemaSnapshotReaderTest.java`
- Modify: `test/com/datacube/provider/oracle/OracleSchemaSnapshotReaderTest.java`

**Interfaces:**
- Consumes: provider JDBC snapshot fixtures, provider comparison projector, `SchemaDiffEngine`, `SchemaChangePlanner`, and provider renderer.
- Produces: End-to-end proof that reader confidence remains HIGH for supported grammar, rendered SQL changes only relation owners, and a simulated target reread has only EQUIVALENT differences.

- [x] **Step 1: Extend real-reader fixtures**

  Add the same procedural/value grammar and exact target definitions to existing cross-owner reader tests. Assert source objects are HIGH confidence, rendered SQL retains source-named procedural bindings, all relation targets use target owner, and the second projected diff contains no non-EQUIVALENT difference for the fixture object.

- [x] **Step 2: Keep fail-closed coverage**

  Add or retain an incomplete/ambiguous clause fixture and assert reader confidence LOW, MANUAL_ONLY difference, unselected MANUAL plan change, and renderer refusal.

### Task 3: Clause-aware shared scanner

**Files:**
- Modify: `src/com/datacube/provider/SqlScopeAwareOwnerRewriter.java`

**Interfaces:**
- Consumes: token list, parentheses, lexical scopes, provider dialect, and existing relation parsing.
- Produces: exact relation-introducer indices or `IllegalArgumentException` for unsupported/ambiguous grammar.

- [x] **Step 1: Implement minimal classification**

  Replace the global `Set.of("FROM", "JOIN", "UPDATE", "USING")` and definition-wide trigger `ON` checks with local helpers that track statement boundaries and introducer context. Only pass a token to `parseRelation` when its role is proven by the surrounding query/DML/trigger header; skip value grammar, bind clauses, and join-column/predicate grammar.

- [x] **Step 2: Verify GREEN**

  Re-run the four focused renderer/reader suites with `--rerun-tasks --no-build-cache`; expected result is zero failures and zero errors.

- [x] **Step 3: Test efficacy**

  Temporarily restore the old keyword classification, run the new named tests and capture expected failures, then restore the clause-aware implementation and rerun them green.

### Task 4: Full verification and handoff records

**Files:**
- Modify: `.superpowers/sdd/schema-diff-final-fix-report.md`
- Modify: `.superpowers/sdd/progress.md`

**Interfaces:**
- Consumes: fresh test XML, build output, CodeGraph state, Git diff/mode/scans.
- Produces: tenth-round RED/GREEN and verification evidence plus final cumulative review range.

- [x] **Step 1: Run gates**

  Run focused provider suites, Task 1-10 matrix, clean full test+jlink, explicit no-credential live-skip test, image launcher checks, `codegraph sync/status`, `git diff --check`, staged diff check, executable-mode check, XML marker scan, and added-line credential/endpoint/private-key scan.

- [x] **Step 2: Record evidence and commit**

  Prepend the tenth-round report/progress evidence, stage only the implementation/test/report/plan files, verify staged scope excludes `.testagent/`, and create an independent commit without amend/push/tag.
