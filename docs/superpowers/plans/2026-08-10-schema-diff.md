# DataCube 全对象 Schema Diff 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan task-by-task. Every production change must also follow `superpowers:test-driven-development`, and every task must receive an independent code review before the next task starts.

**Goal:** 为 PostgreSQL↔PostgreSQL、Oracle↔Oracle 提供单 Schema 全对象语义对比、稳定有序脚本、破坏性变更逐项确认、目标漂移硬门禁和安全部署工作流。

**Architecture:** 在 `com.datacube.spi.schemadiff` 定义不可变规范模型与 provider 能力契约，在 `com.datacube.schemadiff` 实现无 JDBC 的 diff/planner；PostgreSQL、Oracle provider 各自实现 snapshot reader 与 renderer；service 层以两条虚拟线程并行读取源/目标快照，并通过独占安全 JDBC 会话串行部署；JavaFX 层只编排状态和呈现结果，完整接入现有受管标签生命周期。

**Tech Stack:** JDK 25、Java records/sealed interfaces、JDBC、JavaFX 25、Gradle、JUnit 5、JDK 虚拟线程、现有 `FxTaskRunner` / `JdbcEditorSession` / `ContentTabPane` 生命周期框架。

## Global Constraints

- 直接在 `main` 分阶段提交；每个任务只提交自身范围，不 push，最终统一 push/tag。
- `.testagent/` 属于用户，禁止读取、修改、暂存或提交。
- 每次定位或理解代码先用 CodeGraph；修改后执行 `codegraph sync`。
- 所有实现先取得可解释的 RED，再写最小 GREEN；不得以源码字符串测试代替可运行的行为测试，除非 JavaFX 运行时无法稳定启动且同时有纯 Java seam 测试。
- snapshot、diff、plan、部署日志均不得保存密码、解密凭据或完整 JDBC URL。
- 首期只允许同 `DbType`、一个源 Schema 到一个目标 Schema；源为期望状态，目标为待同步状态。
- snapshot 读取使用两个独立连接和两条虚拟线程并行；单连接内 JDBC 查询串行。
- 自动 rename、跨数据库类型映射、数据对比、权限/角色/表空间、全批次事务回滚不在本计划范围。
- destructive change 默认未选择；只有逐项启用并通过第二次确认后才能进入脚本和部署。
- 部署前必须重新读取目标 fingerprint；不一致时硬阻止，不允许“仍然继续”。
- 部署使用独占安全 JDBC 会话逐项顺序执行；失败即停，并将依赖当前失败项的后续步骤标记为 `SKIPPED_DEPENDENCY`。
- Oracle DDL 可能隐式提交，UI、导出脚本和结果页都不得承诺整体回滚。

---

## Task 1: 建立不可变 Schema Snapshot 规范模型

**Files:**

- Create: `src/com/datacube/spi/schemadiff/ObjectType.java`
- Create: `src/com/datacube/spi/schemadiff/QualifiedName.java`
- Create: `src/com/datacube/spi/schemadiff/ObjectKey.java`
- Create: `src/com/datacube/spi/schemadiff/DefinitionConfidence.java`
- Create: `src/com/datacube/spi/schemadiff/CanonicalDataType.java`
- Create: `src/com/datacube/spi/schemadiff/ColumnDefinition.java`
- Create: `src/com/datacube/spi/schemadiff/ConstraintKind.java`
- Create: `src/com/datacube/spi/schemadiff/ConstraintDefinition.java`
- Create: `src/com/datacube/spi/schemadiff/IndexDefinition.java`
- Create: `src/com/datacube/spi/schemadiff/SchemaObject.java`
- Create: `src/com/datacube/spi/schemadiff/TableDefinition.java`
- Create: `src/com/datacube/spi/schemadiff/SequenceDefinition.java`
- Create: `src/com/datacube/spi/schemadiff/DefinitionObject.java`
- Create: `src/com/datacube/spi/schemadiff/SnapshotCompleteness.java`
- Create: `src/com/datacube/spi/schemadiff/SchemaSnapshot.java`
- Create: `src/com/datacube/spi/schemadiff/SnapshotFingerprint.java`
- Test: `test/com/datacube/spi/schemadiff/SchemaSnapshotModelTest.java`
- Test: `test/com/datacube/spi/schemadiff/SnapshotFingerprintTest.java`

**Step 1: Write the failing model contracts**

Tests must assert:

- every input collection/map is defensively copied and unmodifiable;
- `ObjectKey` ordering is `ObjectType` then `comparisonKey` then `signature`;
- overloaded routines with different signatures are distinct;
- quoted and unquoted provider comparison keys are not recomputed in the core;
- fingerprint is independent of insertion order and `capturedAt`/`connectionId`;
- any property change alters the fingerprint;
- partial completeness includes exact failed object types but never exception messages containing secrets.

Run:

```powershell
.\gradlew.bat test --tests com.datacube.spi.schemadiff.SchemaSnapshotModelTest --tests com.datacube.spi.schemadiff.SnapshotFingerprintTest --rerun-tasks --no-daemon --console=plain
```

Expected RED: compilation fails because `com.datacube.spi.schemadiff` does not exist.

**Step 2: Implement the exact core contracts**

Use these public shapes:

```java
public enum ObjectType {
    TABLE, PRIMARY_KEY, UNIQUE_CONSTRAINT, FOREIGN_KEY, CHECK_CONSTRAINT,
    INDEX, SEQUENCE, VIEW, MATERIALIZED_VIEW, FUNCTION, PROCEDURE,
    TRIGGER, PACKAGE_SPEC, PACKAGE_BODY, TYPE
}

public record QualifiedName(String original, String comparisonKey, boolean quoted)
        implements Comparable<QualifiedName> {}

public record ObjectKey(ObjectType type, QualifiedName name, String signature)
        implements Comparable<ObjectKey> {}

public sealed interface SchemaObject
        permits TableDefinition, SequenceDefinition, DefinitionObject {
    ObjectKey key();
    Set<ObjectKey> dependencies();
}

public record CanonicalDataType(
        String baseType, Long length, Integer precision, Integer scale,
        boolean withTimeZone, int arrayDimensions,
        SortedMap<String, String> providerExtensions) {}

public record ColumnDefinition(
        QualifiedName name, CanonicalDataType dataType, boolean nullable,
        String normalizedDefault, int ordinal, String comment) {}

public record ConstraintDefinition(
        ObjectKey key, ConstraintKind kind, List<QualifiedName> columns,
        ObjectKey referencedTable, List<QualifiedName> referencedColumns,
        String normalizedExpression, String updateAction, String deleteAction,
        boolean providerGeneratedName, Set<ObjectKey> dependencies) {}

public record IndexDefinition(
        ObjectKey key, boolean unique, List<String> normalizedExpressions,
        String normalizedPredicate, boolean providerGeneratedName,
        Set<ObjectKey> dependencies) {}

public record TableDefinition(
        ObjectKey key, List<ColumnDefinition> columns,
        List<ConstraintDefinition> constraints, List<IndexDefinition> indexes,
        Set<ObjectKey> dependencies) implements SchemaObject {}

public record SequenceDefinition(
        ObjectKey key, String startValue, String incrementBy, String minimumValue,
        String maximumValue, boolean cycle, Integer cacheSize,
        Set<ObjectKey> dependencies) implements SchemaObject {}

public record DefinitionObject(
        ObjectKey key, String normalizedDefinition, String originalDefinition,
        Set<ObjectKey> dependencies,
        DefinitionConfidence confidence) implements SchemaObject {}

public record SnapshotCompleteness(
        boolean complete, SortedMap<ObjectType, String> unavailableScopes) {}

public record SchemaSnapshot(
        DbType databaseType, String connectionId, QualifiedName schema,
        Instant capturedAt, SnapshotCompleteness completeness,
        SortedMap<ObjectKey, SchemaObject> objects, String fingerprint) {}
```

`SnapshotFingerprint.compute(...)` must canonicalize every scalar and sorted child collection into UTF-8 and return lowercase SHA-256 hex. It must omit source connection identity and capture time.

**Step 3: Run focused tests and full regression**

```powershell
.\gradlew.bat test --tests com.datacube.spi.schemadiff.* --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

**Step 4: Commit**

```powershell
git add src/com/datacube/spi/schemadiff test/com/datacube/spi/schemadiff
git commit -m "feat: 添加 Schema Snapshot 规范模型"
```

---

## Task 2: 实现通用语义 Diff Engine

**Files:**

- Create: `src/com/datacube/schemadiff/DifferenceKind.java`
- Create: `src/com/datacube/spi/schemadiff/RiskLevel.java`
- Create: `src/com/datacube/spi/schemadiff/AutomationLevel.java`
- Create: `src/com/datacube/schemadiff/PropertyDifference.java`
- Create: `src/com/datacube/schemadiff/SchemaDifference.java`
- Create: `src/com/datacube/schemadiff/RenameSuggestion.java`
- Create: `src/com/datacube/schemadiff/SchemaDiffResult.java`
- Create: `src/com/datacube/schemadiff/SchemaDiffEngine.java`
- Test: `test/com/datacube/schemadiff/SchemaDiffEngineTest.java`
- Test: `test/com/datacube/schemadiff/SchemaDiffCompletenessTest.java`
- Test: `test/com/datacube/schemadiff/SchemaRenameSuggestionTest.java`

**Step 1: Write RED tests for every difference class**

Cover table columns/types/defaults/nullability, constraints, indexes, sequences, definition objects, semantic generated-name equivalence, low-confidence definitions, missing/extra objects, and source/target partial snapshots. Assert that rename candidates remain one missing plus one extra difference and never become a rename automatically.

Expected result model:

```java
public record PropertyDifference(
        String path, Object sourceValue, Object targetValue, String explanation) {}

public record SchemaDifference(
        DifferenceKind kind, ObjectKey object, SchemaObject source,
        SchemaObject target, List<PropertyDifference> properties,
        RiskLevel risk, AutomationLevel automation,
        Set<ObjectKey> dependencies, String explanation) {}

public record SchemaDiffResult(
        SchemaSnapshot source, SchemaSnapshot target,
        List<SchemaDifference> differences,
        List<RenameSuggestion> renameSuggestions) {}
```

Run focused tests and confirm missing symbols RED.

**Step 2: Implement exact-key matching and structural comparison**

- Reject mismatched `DbType` before comparing.
- Exact-match only by `ObjectKey`.
- Emit results in stable `ObjectKey` order.
- Treat provider-generated constraint/index names as equivalent only when all semantic properties match.
- Any object in an unavailable snapshot scope becomes `UNSUPPORTED` + `MANUAL_ONLY`.
- Low-confidence changed definition becomes `MODIFIED` + `MANUAL_ONLY`.
- `RenameSuggestion` is advisory data only; it never changes `SchemaDifference`.

**Step 3: Verify**

```powershell
.\gradlew.bat test --tests com.datacube.schemadiff.SchemaDiffEngineTest --tests com.datacube.schemadiff.SchemaDiffCompletenessTest --tests com.datacube.schemadiff.SchemaRenameSuggestionTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
```

**Step 4: Commit**

```powershell
git add src/com/datacube/schemadiff test/com/datacube/schemadiff
git commit -m "feat: 实现 Schema 语义差异引擎"
```

---

## Task 3: 建立风险、依赖图和稳定变更计划

**Files:**

- Create: `src/com/datacube/spi/schemadiff/SchemaSnapshotReader.java`
- Create: `src/com/datacube/spi/schemadiff/SchemaChangeRenderer.java`
- Create: `src/com/datacube/spi/schemadiff/SchemaDiffCapability.java`
- Create: `src/com/datacube/spi/schemadiff/ChangeKind.java`
- Create: `src/com/datacube/spi/schemadiff/SchemaChange.java`
- Create: `src/com/datacube/schemadiff/SchemaChangePlan.java`
- Create: `src/com/datacube/spi/schemadiff/RenderedStatement.java`
- Create: `src/com/datacube/spi/schemadiff/RenderContext.java`
- Create: `src/com/datacube/schemadiff/SchemaChangePlanner.java`
- Modify: `src/com/datacube/spi/DatabaseProvider.java`
- Test: `test/com/datacube/schemadiff/SchemaChangePlannerTest.java`
- Test: `test/com/datacube/schemadiff/SchemaDependencyPlannerTest.java`
- Test: `test/com/datacube/spi/SchemaDiffCapabilityContractTest.java`

**Step 1: RED contracts**

Assert:

- safe create/add changes are selected by default;
- every drop, narrowing type change, nullable→not-null, key replacement, and risky programmable replace is unselected `DESTRUCTIVE_OPT_IN`;
- `MANUAL_ONLY` never has executable statements;
- create order follows TYPE→SEQUENCE→TABLE→key/check→INDEX→FK→VIEW→routine/package→TRIGGER;
- drop order is the reverse;
- same-rank nodes use `ObjectKey` order;
- unresolved cycles become manual and do not leak into executable output;
- disabling one change removes or marks its dependents;
- providers without the new capability still compile and return `Optional.empty()`.

**Step 2: Implement contracts**

```java
@FunctionalInterface
public interface SchemaSnapshotReader {
    SchemaSnapshot read(String connectionId, QualifiedName schema,
                        SqlExecutionControl control) throws SQLException;
}

public interface SchemaChangeRenderer {
    List<RenderedStatement> render(SchemaChange change, RenderContext context);
}

public interface SchemaDiffCapability {
    SchemaSnapshotReader snapshotReader(Connection connection);
    SchemaChangeRenderer changeRenderer();
    Set<ObjectType> supportedObjectTypes();
}
```

Add to `DatabaseProvider`:

```java
default Optional<SchemaDiffCapability> schemaDiffCapability() {
    return Optional.empty();
}
```

Planner public contract:

```java
public SchemaChangePlan plan(SchemaDiffResult result);
public SchemaChangePlan select(SchemaChangePlan plan,
                               Set<String> selectedChangeIds);
```

Change IDs must derive from change kind + object key + canonical property path, never list position or current time.

**Step 3: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.schemadiff.SchemaChangePlannerTest --tests com.datacube.schemadiff.SchemaDependencyPlannerTest --tests com.datacube.spi.SchemaDiffCapabilityContractTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/spi src/com/datacube/schemadiff test/com/datacube/spi test/com/datacube/schemadiff
git commit -m "feat: 添加 Schema 变更规划契约"
```

---

## Task 4: 实现 PostgreSQL 全对象 Snapshot Reader

**Files:**

- Create: `src/com/datacube/provider/postgres/PgSchemaIdentifierNormalizer.java`
- Create: `src/com/datacube/provider/postgres/PgSchemaDefinitionNormalizer.java`
- Create: `src/com/datacube/provider/postgres/PgSchemaSnapshotReader.java`
- Modify: `src/com/datacube/provider/postgres/PgMetadataReader.java`
- Modify: `src/com/datacube/provider/postgres/PgDdlGenerator.java`
- Test: `test/com/datacube/provider/postgres/PgSchemaIdentifierNormalizerTest.java`
- Test: `test/com/datacube/provider/postgres/PgSchemaSnapshotReaderTest.java`
- Test: `test/com/datacube/provider/postgres/PgSchemaSnapshotCancellationTest.java`

**Step 1: Build JDBC-proxy RED fixtures**

Use deterministic proxy result sets for:

- tables/columns including arrays, numeric precision/scale, timestamptz and quoted identifiers;
- PK, unique, FK actions, checks, expression/partial indexes;
- sequences;
- views and materialized views;
- overloaded functions and procedures using identity argument signatures;
- triggers via `pg_get_triggerdef`;
- enum/composite/domain types with definitions and dependencies.

Tests must prove every `PreparedStatement` receives `SqlExecutionOptions.queryTimeoutSeconds()`, is activated through `SqlExecutionControl`, releases its activation in `finally`, and cancel reaches the currently blocking metadata statement.

**Step 2: Implement serial reads on one connection**

- Read `pg_catalog` with schema parameters; never concatenate user schema into SQL.
- Distinguish quoted identifiers from PostgreSQL-folded identifiers when producing `comparisonKey`.
- Use `pg_get_*def` only for definition-bearing objects and retain original definition in memory only.
- Extract dependency OIDs and map them to `ObjectKey`; unresolved dependencies remain explicit diagnostics in completeness.
- Ordinary unavailable optional metadata produces partial completeness; cancellation and timeout propagate as terminal outcomes rather than partial success.
- Extend existing `PgMetadataReader`/`PgDdlGenerator` only for reusable trigger/type/materialized-view queries; do not route snapshot comparison through UI model DTOs.

**Step 3: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.provider.postgres.PgSchemaIdentifierNormalizerTest --tests com.datacube.provider.postgres.PgSchemaSnapshotReaderTest --tests com.datacube.provider.postgres.PgSchemaSnapshotCancellationTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/provider/postgres test/com/datacube/provider/postgres
git commit -m "feat: 读取 PostgreSQL Schema 全对象快照"
```

---

## Task 5: 实现 PostgreSQL 变更渲染并注册能力

**Files:**

- Create: `src/com/datacube/provider/postgres/PgSchemaChangeRenderer.java`
- Create: `src/com/datacube/provider/postgres/PgSchemaDiffCapability.java`
- Modify: `src/com/datacube/provider/postgres/PostgresProvider.java`
- Test: `test/com/datacube/provider/postgres/PgSchemaChangeRendererTest.java`
- Test: `test/com/datacube/provider/postgres/PgSchemaDiffCapabilityTest.java`

**Step 1: RED render matrix**

Cover safe create/add, alter, replace and opt-in drop for every PostgreSQL-supported object. Assert correct quoting, function identity arguments for overloaded drops, `CREATE OR REPLACE` only where PostgreSQL supports it, and statement terminators that remain valid when exported as one script.

**Step 2: Implement renderer**

- Render from structured change/source definition, not from display text.
- Return `RenderedStatement(changeId, sql, destructive, dependencyIds, warning)`.
- Refuse `MANUAL_ONLY`, unresolved cycle, incomplete snapshot, and unselected destructive changes.
- Do not emit guessed rename SQL.
- `PostgresProvider.schemaDiffCapability()` returns one immutable capability whose reader is connection-bound and renderer is stateless.

**Step 3: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.provider.postgres.PgSchemaChangeRendererTest --tests com.datacube.provider.postgres.PgSchemaDiffCapabilityTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/provider/postgres test/com/datacube/provider/postgres
git commit -m "feat: 生成 PostgreSQL Schema 变更脚本"
```

---

## Task 6: 实现 Oracle 全对象 Snapshot Reader

**Files:**

- Create: `src/com/datacube/provider/oracle/OracleSchemaIdentifierNormalizer.java`
- Create: `src/com/datacube/provider/oracle/OracleSchemaDefinitionNormalizer.java`
- Create: `src/com/datacube/provider/oracle/OracleSchemaSnapshotReader.java`
- Modify: `src/com/datacube/provider/oracle/OracleMetadataReader.java`
- Modify: `src/com/datacube/provider/oracle/OracleDdlGenerator.java`
- Test: `test/com/datacube/provider/oracle/OracleSchemaIdentifierNormalizerTest.java`
- Test: `test/com/datacube/provider/oracle/OracleSchemaSnapshotReaderTest.java`
- Test: `test/com/datacube/provider/oracle/OracleSchemaSnapshotCancellationTest.java`

**Step 1: JDBC-proxy RED fixtures**

Cover tables/columns, NUMBER/character/timestamp/interval/object types, PK/unique/FK/check, indexes, sequences, views/materialized views, standalone functions/procedures, triggers, package spec/body and type spec/body. Include quoted mixed-case identifiers and overloaded routines through `ALL_ARGUMENTS` signatures.

**Step 2: Implement reader**

- Parameterize owner/schema in all `ALL_*` queries.
- Use `DBMS_METADATA.GET_DDL` for definition-bearing objects, normalize only line endings/trailing delimiter/provider-known storage noise, and mark uncertain normalization `LOW`.
- Package spec/body and type spec/body are separate `ObjectKey`s with explicit dependencies.
- Apply execution control/timeout/activation to every metadata and DBMS_METADATA statement.
- Do not call `Connection#setSchema`; one dedicated connection is serially queried for the requested owner.
- Propagate cancellation/timeout terminally and record ordinary permission gaps as partial completeness.

**Step 3: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.provider.oracle.OracleSchemaIdentifierNormalizerTest --tests com.datacube.provider.oracle.OracleSchemaSnapshotReaderTest --tests com.datacube.provider.oracle.OracleSchemaSnapshotCancellationTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/provider/oracle test/com/datacube/provider/oracle
git commit -m "feat: 读取 Oracle Schema 全对象快照"
```

---

## Task 7: 实现 Oracle 变更渲染并注册能力

**Files:**

- Create: `src/com/datacube/provider/oracle/OracleSchemaChangeRenderer.java`
- Create: `src/com/datacube/provider/oracle/OracleSchemaDiffCapability.java`
- Modify: `src/com/datacube/provider/oracle/OracleProvider.java`
- Test: `test/com/datacube/provider/oracle/OracleSchemaChangeRendererTest.java`
- Test: `test/com/datacube/provider/oracle/OracleSchemaDiffCapabilityTest.java`

**Step 1: RED render matrix**

Cover all Oracle-supported objects, `/` delimiters for PL/SQL, quoted identifiers, package/type spec before body, trigger dependencies, and safe refusal of changes that would require a guessed table rebuild.

**Step 2: Implement renderer**

- Use structured DDL for table/column/constraint/index/sequence changes.
- Use original DBMS_METADATA definition for replaceable definition objects after stripping environment-specific owner only through the tested normalizer.
- Mark every destructive or implicit-commit statement with an explicit warning.
- Never emit `BEGIN/COMMIT` wrappers suggesting atomic DDL rollback.
- Wire `OracleProvider.schemaDiffCapability()`.

**Step 3: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.provider.oracle.OracleSchemaChangeRendererTest --tests com.datacube.provider.oracle.OracleSchemaDiffCapabilityTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/provider/oracle test/com/datacube/provider/oracle
git commit -m "feat: 生成 Oracle Schema 变更脚本"
```

---

## Task 8: 编排并行快照、目标漂移和安全部署

**Files:**

- Create: `src/com/datacube/service/SchemaDiffRequest.java`
- Create: `src/com/datacube/service/SchemaDiffService.java`
- Create: `src/com/datacube/service/SchemaDeploymentState.java`
- Create: `src/com/datacube/service/SchemaDeploymentStepResult.java`
- Create: `src/com/datacube/service/SchemaDeploymentResult.java`
- Create: `src/com/datacube/service/SchemaDeploymentService.java`
- Create: `src/com/datacube/service/SchemaDeploymentControl.java`
- Modify: `src/com/datacube/service/ConnectionManager.java`
- Test: `test/com/datacube/service/SchemaDiffServiceTest.java`
- Test: `test/com/datacube/service/SchemaDiffConcurrencyTest.java`
- Test: `test/com/datacube/service/SchemaDeploymentServiceTest.java`
- Test: `test/com/datacube/service/SchemaDeploymentDriftTest.java`
- Test: `test/com/datacube/service/SchemaDeploymentCancellationTest.java`

**Step 1: RED orchestration tests**

Assert:

- different-type or Redis endpoints are rejected before opening connections;
- source and target readers overlap on two virtual threads, but each reader sees only its own connection;
- both connections are dedicated, read-only/best-effort read-only, and closed exactly once on success/failure/cancel;
- target fingerprint is reread immediately before deployment;
- drift prevents the first SQL execution;
- statements execute sequentially through one target `JdbcEditorSession`;
- first failure stops new execution and marks transitive dependents skipped;
- cancellation reaches the current statement and does not start the next;
- strict session close failure produces a terminal partial failure, never success;
- no result/error string contains credentials or full JDBC URL.

**Step 2: Add immutable dedicated-open overload**

Add to `ConnectionManager`:

```java
public Connection openDedicated(ConnConfig configSnapshot) throws SQLException;
```

The method must use the supplied immutable snapshot for provider choice, decrypted connection properties and safety configuration, mirroring `openEditorSession(ConnConfig)` without consulting mutable current config after admission.

**Step 3: Implement service workflow**

```java
public CompletionStage<SchemaDiffResult> compare(
        SchemaDiffRequest request, SchemaDeploymentControl control);

public CompletionStage<SchemaDeploymentResult> deploy(
        SchemaDiffRequest request, SchemaSnapshot expectedTarget,
        List<RenderedStatement> statements,
        SchemaDeploymentControl control);
```

- Use `Executors.newVirtualThreadPerTaskExecutor()` only through an owned/closeable task scope.
- Snapshot both `ConnConfig`s before launching workers.
- The deploy service reopens the target, rereads fingerprint, then creates/uses an independent safe editor session for sequential statements.
- Destructive deployment requires `request.destructiveConfirmationToken()` matching a plan digest; a plain boolean is insufficient and stale confirmation cannot apply to a changed selection.

**Step 4: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.service.SchemaDiffServiceTest --tests com.datacube.service.SchemaDiffConcurrencyTest --tests com.datacube.service.SchemaDeploymentServiceTest --tests com.datacube.service.SchemaDeploymentDriftTest --tests com.datacube.service.SchemaDeploymentCancellationTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/service test/com/datacube/service
git commit -m "feat: 添加 Schema Diff 安全部署服务"
```

---

## Task 9: 集成 JavaFX Schema Diff 工作流与受管生命周期

**Files:**

- Create: `src/com/datacube/fx/SchemaDiffPane.java`
- Create: `src/com/datacube/fx/SchemaDiffViewModel.java`
- Create: `src/com/datacube/fx/SchemaDiffSelectionModel.java`
- Create: `src/com/datacube/fx/SchemaDiffDialogs.java`
- Modify: `src/com/datacube/fx/ConnectionTreePane.java`
- Modify: `src/com/datacube/fx/AppShell.java`
- Test: `test/com/datacube/fx/SchemaDiffViewModelTest.java`
- Test: `test/com/datacube/fx/SchemaDiffSelectionModelTest.java`
- Test: `test/com/datacube/fx/SchemaDiffPaneContractTest.java`
- Test: `test/com/datacube/fx/SchemaDiffLifecycleTest.java`
- Test: `test/com/datacube/fx/ConnectionTreeSchemaDiffContractTest.java`

**Step 1: RED pure-state and lifecycle tests**

Test states `IDLE`, `LOADING`, `READY`, `DEPLOYING`, `CANCELLING`, `COMPLETED`, `FAILED`, `DRIFTED`; deterministic filtering/grouping; destructive opt-in; selection digest invalidation; rename suggestion display without executable selection; deployment confirmation text; Oracle implicit-commit warning; and cancellation/close ownership.

Lifecycle contracts must prove:

- pane construction uses `ConstructionOwner` and binds blocking cleanup before installation;
- JDBC work is never executed on JavaFX Application Thread;
- compare/cancel/deploy use owned virtual-thread scopes;
- tab close cancels, awaits owned work and strictly closes sessions before FX finalizer;
- mandatory application shutdown performs no dialog interaction and settles through the existing mandatory guard;
- FX finalizer only detaches listeners and updates nodes.

**Step 2: Implement pane and tree entrypoint**

UI layout:

- top: source connection/schema and target connection/schema selectors plus Compare;
- left/center: grouped difference tree/table with risk, automation and selection;
- right/bottom: property comparison, source/target definition, ordered SQL preview and diagnostics;
- actions: export selected script, deploy selected changes, cancel current work.

Add to `ConnectionTreePane.Actions`:

```java
void openSchemaDiff(ConnConfig source, String sourceSchema);
```

Add “Schema 对比...” to relational CONNECTION and SCHEMA context menus; Redis never shows it. `AppShell.TreeActions` opens one managed background-cleanup tab via the existing reservation factory and supplies `SchemaDiffPane::requestClose`, mandatory close, strict cleanup and FX finalizer.

**Step 3: Confirmation rules**

- First confirmation summarizes target connection/schema and selected safe changes.
- If any destructive opt-in exists, show a second confirmation requiring typed target schema comparison key.
- A changed selection invalidates confirmation token.
- Drift, incomplete snapshot, manual-only selection, or unsupported provider disables Deploy.
- Errors shown to users are fixed/structured and never concatenate SQL, credentials or raw JDBC exception text.

**Step 4: Verify and commit**

```powershell
.\gradlew.bat test --tests com.datacube.fx.SchemaDiffViewModelTest --tests com.datacube.fx.SchemaDiffSelectionModelTest --tests com.datacube.fx.SchemaDiffPaneContractTest --tests com.datacube.fx.SchemaDiffLifecycleTest --tests com.datacube.fx.ConnectionTreeSchemaDiffContractTest --rerun-tasks --no-daemon --console=plain
.\gradlew.bat test --rerun-tasks --no-daemon --console=plain
git add src/com/datacube/fx test/com/datacube/fx
git commit -m "feat: 添加 Schema Diff 可视化工作流"
```

---

## Task 10: 文档、真实非生产 smoke 与发布门禁

**Files:**

- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-10-schema-diff-design.md`
- Modify: `docs/superpowers/plans/2026-08-10-schema-diff.md`
- Create: `test/com/datacube/schemadiff/SchemaDiffLiveIntegrationTest.java`
- Create: `.github/workflows/schema-diff-integration.yml`

**Step 1: Add opt-in live smoke**

The test must be skipped unless all provider-specific environment variables are present and an explicit `DATACUBE_SCHEMA_DIFF_TEST_ALLOW_WRITE=true` gate is set. It creates uniquely prefixed disposable schemas/objects only in the supplied non-production database, compares, deploys safe changes, verifies convergence, then drops only the exact created schemas in `finally`.

Never use saved application connections or infer permission from localhost. Do not run Oracle/PostgreSQL live smoke until the user supplies explicit disposable non-production endpoints.

**Step 2: Document product behavior**

README must explain supported matrices/object types, source→target semantics, destructive opt-in, drift blocking, Oracle implicit commits, rename suggestions, export/deploy flow and current exclusions. Mark the design “已批准并实施” only after all implementation and verification steps pass.

**Step 3: Run the complete release gate**

```powershell
.\gradlew.bat clean test jlink --warning-mode fail --rerun-tasks --no-daemon --console=plain
Test-Path build/image/bin/DataCube.bat
Select-String -Path build/image/bin/DataCube.bat -Pattern 'UseG1GC','-Xms16m','-Xmx256m'
codegraph sync
codegraph status
git diff --check
git status --short
```

Expected:

- all tests pass with only explicitly documented environment-dependent skips;
- linked Windows image exists and retains G1/16MB/256MB launcher options;
- CodeGraph index is current;
- worktree contains only intended tracked changes plus untouched `.testagent/`;
- no credential or live endpoint appears in tracked files or test reports.

**Step 4: Independent cumulative review**

Review the complete commit range from this plan's baseline through Task 10. Any Critical/Important/Minor finding must receive its own RED→GREEN follow-up commit and fresh cumulative review.

**Step 5: Commit and hand off**

```powershell
git add README.md docs/superpowers/specs/2026-08-10-schema-diff-design.md docs/superpowers/plans/2026-08-10-schema-diff.md test/com/datacube/schemadiff/SchemaDiffLiveIntegrationTest.java .github/workflows/schema-diff-integration.yml
git commit -m "docs: 完成 Schema Diff 发布说明"
```

Do not push or tag until the cumulative reviewer reports Ready and the user authorizes release publication.
