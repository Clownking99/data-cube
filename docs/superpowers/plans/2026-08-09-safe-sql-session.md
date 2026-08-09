# DataCube v3.1 Safe SQL Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** Give every SQL editor tab an isolated JDBC session with visible transactions, query cancellation and timeout, read-only/production protection, and safe asynchronous tab closing.

**Architecture:** Preserve ConnectionManager.acquire for existing shared consumers and add a dedicated-connection path used only by JdbcEditorSession. Keep connection safety settings and SQL risk analysis as pure model code, make Oracle/PostgreSQL runners expose their active Statement through SqlExecutionControl, and let SqlEditorPane orchestrate UI confirmation while all blocking JDBC work stays on managed JDK 25 virtual threads.

**Tech Stack:** JDK 25, JavaFX 25, JDBC, RichTextFX, JUnit 5, Gradle 9.2, jlink, CodeGraph.

## Global Constraints

- Work directly on main; do not create a feature branch.
- Do not add, modify, stage, or commit .testagent/.
- Windows is the primary release platform while cross-platform execution is retained.
- Blocking SQL execution, commit, rollback, and cancellation use the shared JDK 25 virtual-thread FxTaskRunner.
- JavaFX controls are accessed only on the JavaFX Application Thread.
- Do not add a connection pool, reactive database stack, third-party SQL parser, or new database driver.
- Existing Oracle, PostgreSQL, Redis, migration, export, history, completion, and result rendering behavior must remain available.
- Existing connection JSON loads without migration; Redis serialization remains unchanged.
- Plain passwords, __plainPassword, connection URLs containing secrets, and full ciphertext never enter logs, tests, documentation, or persisted props.
- Follow strict red-green-refactor: every production behavior starts with a focused failing test.

---

## File Structure

New focused units:

- src/com/datacube/spi/model/ConnectionEnvironment.java — development/test/production identity and display metadata.
- src/com/datacube/spi/model/ConnectionSafetyOptions.java — validated safety settings stored in ConnConfig.props.
- src/com/datacube/sqleditor/SqlSafetyAnalyzer.java — statement classification and lexical risk detection.
- src/com/datacube/sqleditor/SqlSafetyPolicy.java — environment/read-only decision matrix.
- src/com/datacube/spi/SqlExecutionControl.java — active Statement registration, timeout capability, and cancellation.
- src/com/datacube/spi/SqlExecutionOptions.java — immutable per-run options passed through SqlRunner.
- src/com/datacube/service/JdbcEditorSession.java — dedicated connection ownership and transaction state machine.
- src/com/datacube/fx/AsyncTabCloseGuard.java — asynchronous close approval contract.
- src/com/datacube/fx/AsyncCloseGate.java — duplicate-close suppression independent of JavaFX controls.

Existing units changed:

- ConnectionStore and ConnectionDialog persist/edit the safety whitelist.
- QueryResult, SqlRunner, OracleSqlRunner, and PgSqlRunner expose cancellation/timeout outcomes.
- ConnectionManager opens uncached dedicated connections and constructs editor sessions.
- ContentTabPane applies asynchronous close guards while retaining exactly-once disposal.
- SqlEditorPane owns one JdbcEditorSession and renders its state.
- AppShell binds SQL tabs and wires their close guard.
- README documents the user-visible behavior.

---

### Task 1: Connection safety settings and backward-compatible persistence

**Files:**

- Create: src/com/datacube/spi/model/ConnectionEnvironment.java
- Create: src/com/datacube/spi/model/ConnectionSafetyOptions.java
- Modify: src/com/datacube/config/ConnectionStore.java:144-172
- Modify: src/com/datacube/fx/ConnectionDialog.java:42-176
- Test: test/com/datacube/config/ConnectionSafetyOptionsTest.java
- Test: test/com/datacube/config/ConnectionStoreTest.java

**Interfaces:**

- Produces: ConnectionEnvironment.parse(String), ConnectionSafetyOptions.from(ConnConfig), ConnectionSafetyOptions.applyTo(ConnConfig), and ConnectionSafetyOptions.toPersistentProps().
- Persistence keys are exactly environment, readOnly, and queryTimeoutSeconds.
- Defaults are DEVELOPMENT, false, and 60 seconds; valid timeout range is 0 through 3600.

- [ ] **Step 1: Write failing model and persistence tests**

Create ConnectionSafetyOptionsTest with these complete contract tests:

~~~java
package com.datacube.config;

import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConnectionSafetyOptionsTest {
    @Test
    void missingPropertiesUseSafeDefaults() {
        ConnectionSafetyOptions options = ConnectionSafetyOptions.from(config(Map.of()));
        assertEquals(ConnectionEnvironment.DEVELOPMENT, options.environment());
        assertFalse(options.readOnly());
        assertEquals(60, options.queryTimeoutSeconds());
    }

    @Test
    void invalidPropertiesFallBackWithoutPreservingTransientSecrets() {
        ConnConfig config = config(Map.of(
                "environment", "unknown",
                "readOnly", "not-a-boolean",
                "queryTimeoutSeconds", "9000",
                "__plainPassword", "must-not-persist"));

        ConnectionSafetyOptions options = ConnectionSafetyOptions.from(config);

        assertEquals(ConnectionEnvironment.DEVELOPMENT, options.environment());
        assertFalse(options.readOnly());
        assertEquals(60, options.queryTimeoutSeconds());
        assertEquals(Map.of(
                "environment", "DEVELOPMENT",
                "readOnly", "false",
                "queryTimeoutSeconds", "60"), options.toPersistentProps());
    }

    @Test
    void applyMergesOnlyValidatedSafetyValues() {
        ConnConfig original = config(Map.of("driverFlag", "keep"));
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, true, 0);

        ConnConfig updated = options.applyTo(original);

        assertEquals("keep", updated.props().get("driverFlag"));
        assertEquals("PRODUCTION", updated.props().get("environment"));
        assertEquals("true", updated.props().get("readOnly"));
        assertEquals("0", updated.props().get("queryTimeoutSeconds"));
    }

    private static ConnConfig config(Map<String, String> props) {
        return new ConnConfig("id", "name", DbType.POSTGRESQL, "localhost", 5432,
                "db", "user", "encrypted", props);
    }
}
~~~

Add this test to ConnectionStoreTest:

~~~java
@Test
void persistsOnlyRelationalSafetyPropertiesAndKeepsRedisShape() throws Exception {
    Path file = tempDir.resolve("connections.json");
    ConnectionStore store = new ConnectionStore(file);
    ConnConfig postgres = new ConnConfig("pg", "pg", DbType.POSTGRESQL, "localhost", 5432,
            "db", "user", "encrypted", Map.of(
                    "environment", "PRODUCTION",
                    "readOnly", "true",
                    "queryTimeoutSeconds", "15",
                    "__plainPassword", "secret",
                    "driverFlag", "not-persistent"));
    ConnConfig redis = new ConnConfig("redis", "redis", DbType.REDIS, "localhost", 6379,
            "0", "", "encrypted", Map.of("environment", "PRODUCTION"));

    store.saveAll(List.of(postgres, redis));

    String json = Files.readString(file);
    List<ConnConfig> loaded = store.loadAll();
    assertEquals(Map.of(
            "environment", "PRODUCTION",
            "readOnly", "true",
            "queryTimeoutSeconds", "15"), loaded.get(0).props());
    assertEquals(Map.of(), loaded.get(1).props());
    assertFalse(json.contains("__plainPassword"));
    assertFalse(json.contains("driverFlag"));
}
~~~

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.config.ConnectionSafetyOptionsTest --tests com.datacube.config.ConnectionStoreTest --no-daemon --console=plain
~~~

Expected: compile failure because ConnectionEnvironment and ConnectionSafetyOptions do not exist.

- [ ] **Step 3: Add the validated safety model**

Create ConnectionEnvironment:

~~~java
package com.datacube.spi.model;

import java.util.Locale;

public enum ConnectionEnvironment {
    DEVELOPMENT("开发"),
    TEST("测试"),
    PRODUCTION("生产");

    private final String label;

    ConnectionEnvironment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ConnectionEnvironment parse(String value) {
        if (value == null || value.isBlank()) return DEVELOPMENT;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            return DEVELOPMENT;
        }
    }
}
~~~

Create ConnectionSafetyOptions:

~~~java
package com.datacube.spi.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public record ConnectionSafetyOptions(
        ConnectionEnvironment environment,
        boolean readOnly,
        int queryTimeoutSeconds) {

    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 60;
    public static final int MAX_QUERY_TIMEOUT_SECONDS = 3600;

    public ConnectionSafetyOptions {
        environment = environment == null ? ConnectionEnvironment.DEVELOPMENT : environment;
        if (queryTimeoutSeconds < 0 || queryTimeoutSeconds > MAX_QUERY_TIMEOUT_SECONDS) {
            queryTimeoutSeconds = DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }

    public static ConnectionSafetyOptions from(ConnConfig config) {
        Map<String, String> props = config == null ? Map.of() : config.props();
        ConnectionEnvironment environment = ConnectionEnvironment.parse(props.get("environment"));
        boolean readOnly = Boolean.parseBoolean(props.getOrDefault("readOnly", "false"));
        int timeout = parseTimeout(props.get("queryTimeoutSeconds"));
        return new ConnectionSafetyOptions(environment, readOnly, timeout);
    }

    public Map<String, String> toPersistentProps() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("environment", environment.name());
        props.put("readOnly", Boolean.toString(readOnly));
        props.put("queryTimeoutSeconds", Integer.toString(queryTimeoutSeconds));
        return Map.copyOf(props);
    }

    public ConnConfig applyTo(ConnConfig config) {
        Map<String, String> props = new HashMap<>(config.props());
        props.putAll(toPersistentProps());
        return new ConnConfig(config.id(), config.name(), config.type(), config.host(), config.port(),
                config.database(), config.username(), config.encryptedPassword(), props);
    }

    private static int parseTimeout(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_QUERY_TIMEOUT_SECONDS;
        try {
            int timeout = Integer.parseInt(raw.trim());
            return timeout >= 0 && timeout <= MAX_QUERY_TIMEOUT_SECONDS
                    ? timeout : DEFAULT_QUERY_TIMEOUT_SECONDS;
        } catch (NumberFormatException invalid) {
            return DEFAULT_QUERY_TIMEOUT_SECONDS;
        }
    }
}
~~~

- [ ] **Step 4: Persist only the whitelist**

In ConnectionStore.toJson, append the three safety fields only when type is not REDIS:

~~~java
sb.append("\"encryptedPassword\":").append(quote(c.encryptedPassword()));
if (c.type() != DbType.REDIS) {
    ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(c);
    sb.append(',').append("\"environment\":").append(quote(safety.environment().name()));
    sb.append(',').append("\"readOnly\":").append(safety.readOnly());
    sb.append(',').append("\"queryTimeoutSeconds\":").append(safety.queryTimeoutSeconds());
}
sb.append('}');
~~~

In ConnectionStore.fromMap, construct the whitelisted props before ConnConfig:

~~~java
DbType dbType = DbType.valueOf(m.getOrDefault("type", DbType.POSTGRESQL.name()));
Map<String, String> props = dbType == DbType.REDIS ? Map.of() : Map.of(
        "environment", ConnectionEnvironment.parse(m.get("environment")).name(),
        "readOnly", Boolean.toString(Boolean.parseBoolean(m.getOrDefault("readOnly", "false"))),
        "queryTimeoutSeconds", Integer.toString(new ConnectionSafetyOptions(
                ConnectionEnvironment.parse(m.get("environment")),
                Boolean.parseBoolean(m.getOrDefault("readOnly", "false")),
                parseIntOrDefault(m.get("queryTimeoutSeconds"),
                        ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS)).queryTimeoutSeconds()));
return new ConnConfig(require(m, "id"), m.getOrDefault("name", ""), dbType,
        m.getOrDefault("host", ""), parseInt(m.get("port")),
        m.getOrDefault("database", ""), m.getOrDefault("username", ""),
        m.getOrDefault("encryptedPassword", ""), props);
~~~

Add a package-private parseIntOrDefault helper that enforces 0 through 3600:

~~~java
private static int parseIntOrDefault(String value, int fallback) {
    try {
        int parsed = value == null ? fallback : Integer.parseInt(value.trim());
        return parsed >= 0 && parsed <= ConnectionSafetyOptions.MAX_QUERY_TIMEOUT_SECONDS
                ? parsed : fallback;
    } catch (NumberFormatException invalid) {
        return fallback;
    }
}
~~~

Before building relational props, log invalid raw values without logging credentials:

~~~java
private static void warnInvalidSafetyValues(Map<String, String> values) {
    String environment = values.get("environment");
    if (environment != null && java.util.Arrays.stream(ConnectionEnvironment.values())
            .noneMatch(candidate -> candidate.name().equalsIgnoreCase(environment.trim()))) {
        LOG.warning("连接安全环境值无效，已回退到 DEVELOPMENT");
    }
    String readOnly = values.get("readOnly");
    if (readOnly != null && !readOnly.equalsIgnoreCase("true")
            && !readOnly.equalsIgnoreCase("false")) {
        LOG.warning("连接只读值无效，已回退到 false");
    }
    String timeout = values.get("queryTimeoutSeconds");
    if (timeout != null && parseIntOrDefault(
            timeout, ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS)
            == ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS
            && !timeout.trim().equals(Integer.toString(
                    ConnectionSafetyOptions.DEFAULT_QUERY_TIMEOUT_SECONDS))) {
        LOG.warning("连接查询超时值无效，已回退到 60 秒");
    }
}
~~~

Call this helper only for relational entries. Messages identify the field but never include the raw value, connection URL, username, password, or ciphertext.

- [ ] **Step 5: Add relational-only fields to ConnectionDialog**

Add a ComboBox<ConnectionEnvironment>, CheckBox, and timeout TextField. Bind their managed/visible properties to type != REDIS, initialize from ConnectionSafetyOptions.from(existing), validate timeout with the same 0..3600 range, and replace the final Map.of() with:

~~~java
Map<String, String> props = type == DbType.REDIS ? Map.of() : new ConnectionSafetyOptions(
        environmentBox.getValue(),
        readOnlyCheck.isSelected(),
        timeoutSeconds).toPersistentProps();
return new ConnConfig(id, name, type, host, port, db, user, enc, props);
~~~

The updated build method signature is:

~~~java
private static ConnConfig build(
        ConnConfig existing,
        CredentialCipher cipher,
        DbType type,
        TextField nameField,
        TextField hostField,
        TextField portField,
        TextField dbField,
        TextField userField,
        PasswordField passField,
        ComboBox<ConnectionEnvironment> environmentBox,
        CheckBox readOnlyCheck,
        TextField timeoutField)
~~~

Both test-connection and save call sites pass the same three new controls.

- [ ] **Step 6: Verify GREEN and commit**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.config.ConnectionSafetyOptionsTest --tests com.datacube.config.ConnectionStoreTest --no-daemon --console=plain
git diff --check
git add src/com/datacube/spi/model/ConnectionEnvironment.java src/com/datacube/spi/model/ConnectionSafetyOptions.java src/com/datacube/config/ConnectionStore.java src/com/datacube/fx/ConnectionDialog.java test/com/datacube/config/ConnectionSafetyOptionsTest.java test/com/datacube/config/ConnectionStoreTest.java
git commit -m "feat: 添加连接安全配置"
~~~

Expected: focused tests pass and the commit contains no .testagent path.

---

### Task 2: SQL lexical risk analyzer and policy matrix

**Files:**

- Create: src/com/datacube/sqleditor/SqlSafetyAnalyzer.java
- Create: src/com/datacube/sqleditor/SqlSafetyPolicy.java
- Test: test/com/datacube/sqleditor/SqlSafetyAnalyzerTest.java
- Test: test/com/datacube/sqleditor/SqlSafetyPolicyTest.java

**Interfaces:**

- Produces: SqlSafetyAnalyzer.analyze(String, boolean) returning ScriptAnalysis.
- Produces: SqlSafetyPolicy.decide(ScriptAnalysis, ConnectionSafetyOptions) returning Decision.
- Statement kinds are READ, WRITE, DDL, TRANSACTION_CONTROL, UNKNOWN.
- Risks are MISSING_WHERE, DESTRUCTIVE_DDL, UNKNOWN_STATEMENT, SESSION_STATE_CONFLICT.

- [ ] **Step 1: Write analyzer tests**

Create tests that assert exact classification and top-level WHERE behavior:

~~~java
package com.datacube.sqleditor;

import org.junit.jupiter.api.Test;

import static com.datacube.sqleditor.SqlSafetyAnalyzer.Risk.*;
import static com.datacube.sqleditor.SqlSafetyAnalyzer.StatementKind.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyAnalyzerTest {
    @Test
    void detectsMissingTopLevelWhereWithoutBeingFooledBySubqueryOrLiteral() {
        var unsafe = SqlSafetyAnalyzer.analyze(
                "update account set state='where' where_note=(select note from audit where id=1)", false);
        assertEquals(WRITE, unsafe.statements().getFirst().kind());
        assertTrue(unsafe.statements().getFirst().risks().contains(MISSING_WHERE));

        var safe = SqlSafetyAnalyzer.analyze(
                "delete from account where id in (select id from audit where state='x')", false);
        assertFalse(safe.statements().getFirst().risks().contains(MISSING_WHERE));
    }

    @Test
    void handlesCommentsDollarQuotesOracleQuotesAndCtes() {
        String pg = """
                /* delete from hidden */ with x as (
                  select $$ update t set x=1 $$ as body
                ) delete from target where id in (select 1 from x)
                """;
        assertEquals(WRITE, SqlSafetyAnalyzer.analyze(pg, false).statements().getFirst().kind());
        assertFalse(SqlSafetyAnalyzer.analyze(pg, false).statements().getFirst().risks()
                .contains(MISSING_WHERE));

        String oracle = "select q'[drop table hidden]' from dual";
        assertEquals(READ, SqlSafetyAnalyzer.analyze(oracle, true).statements().getFirst().kind());
    }

    @Test
    void classifiesExplainAnalyzeAndSessionStateConflicts() {
        assertEquals(READ, SqlSafetyAnalyzer.analyze(
                "explain select * from t", false).statements().getFirst().kind());
        assertEquals(WRITE, SqlSafetyAnalyzer.analyze(
                "explain analyze delete from t where id=1", false).statements().getFirst().kind());
        assertTrue(SqlSafetyAnalyzer.analyze("begin", false).statements().getFirst().risks()
                .contains(SESSION_STATE_CONFLICT));
        assertFalse(SqlSafetyAnalyzer.analyze("commit", false).statements().getFirst().risks()
                .contains(SESSION_STATE_CONFLICT));
    }

    @Test
    void analyzesEveryStatementBeforeExecution() {
        var analysis = SqlSafetyAnalyzer.analyze(
                "select 1; update t set x=1; drop table t", false);
        assertEquals(3, analysis.statements().size());
        assertEquals(READ, analysis.statements().get(0).kind());
        assertTrue(analysis.statements().get(1).risks().contains(MISSING_WHERE));
        assertTrue(analysis.statements().get(2).risks().contains(DESTRUCTIVE_DDL));
    }
}
~~~

- [ ] **Step 2: Run analyzer tests and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlSafetyAnalyzerTest --no-daemon --console=plain
~~~

Expected: compile failure because SqlSafetyAnalyzer does not exist.

- [ ] **Step 3: Implement the analyzer**

Create these public result types inside SqlSafetyAnalyzer:

~~~java
public enum StatementKind { READ, WRITE, DDL, TRANSACTION_CONTROL, UNKNOWN }
public enum Risk { MISSING_WHERE, DESTRUCTIVE_DDL, UNKNOWN_STATEMENT, SESSION_STATE_CONFLICT }
public record StatementAnalysis(
        int index, String sql, String firstKeyword, StatementKind kind, Set<Risk> risks) {
    public StatementAnalysis {
        risks = Set.copyOf(risks);
    }
}
public record ScriptAnalysis(List<StatementAnalysis> statements) {
    public ScriptAnalysis {
        statements = List.copyOf(statements);
    }
}
~~~

Implement analyze by calling SqlScriptSplitter.split(script, oracleMode), lexing each statement into top-level uppercase words, and applying these exact rules:

~~~java
public static ScriptAnalysis analyze(String script, boolean oracleMode) {
    List<String> statements = SqlScriptSplitter.split(script, oracleMode);
    List<StatementAnalysis> analyses = new ArrayList<>(statements.size());
    for (int i = 0; i < statements.size(); i++) {
        analyses.add(analyzeStatement(i + 1, statements.get(i)));
    }
    return new ScriptAnalysis(analyses);
}

private static StatementAnalysis analyzeStatement(int index, String sql) {
    List<Token> tokens = topLevelTokens(sql);
    String first = tokens.isEmpty() ? "" : tokens.getFirst().word();
    String effective = effectiveKeyword(tokens);
    StatementKind kind = classify(effective, tokens);
    EnumSet<Risk> risks = EnumSet.noneOf(Risk.class);
    if (kind == StatementKind.UNKNOWN) risks.add(Risk.UNKNOWN_STATEMENT);
    if (("UPDATE".equals(effective) || "DELETE".equals(effective))
            && tokens.stream().noneMatch(token -> "WHERE".equals(token.word()))) {
        risks.add(Risk.MISSING_WHERE);
    }
    if ("DROP".equals(effective) || "TRUNCATE".equals(effective)) {
        risks.add(Risk.DESTRUCTIVE_DDL);
    }
    if (Set.of("BEGIN", "START", "SET", "SAVEPOINT", "RELEASE").contains(effective)
            && kind == StatementKind.TRANSACTION_CONTROL) {
        risks.add(Risk.SESSION_STATE_CONFLICT);
    }
    return new StatementAnalysis(index, sql, first, kind, risks);
}
~~~

The lexer uses a State enum with NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, LINE_COMMENT, BLOCK_COMMENT, DOLLAR_QUOTE, and ORACLE_Q_QUOTE. It records a word only while parentheses depth is zero. The WITH handler skips balanced CTE bodies and returns the first top-level SELECT, INSERT, UPDATE, DELETE, or MERGE after the CTE list. PL/SQL BEGIN/DECLARE and CALL/DO/EXEC/EXECUTE classify as WRITE unless they are the standalone transaction tokens listed above.

- [ ] **Step 4: Write policy tests**

Create SqlSafetyPolicyTest:

~~~java
package com.datacube.sqleditor;

import com.datacube.spi.model.ConnectionEnvironment;
import com.datacube.spi.model.ConnectionSafetyOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlSafetyPolicyTest {
    @Test
    void readOnlyBlocksWritesDdlAndUnknownStatements() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, true, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("insert into t values (1)", false), options).blocked());
        assertFalse(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("select * from t", false), options).blocked());
    }

    @Test
    void productionRequiresConfirmationForEveryNonReadStatement() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.PRODUCTION, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("update t set x=1 where id=1", false), options)
                .confirmationRequired());
        assertFalse(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("select 1", false), options).confirmationRequired());
    }

    @Test
    void dangerousStatementsRequireConfirmationInEveryEnvironment() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("delete from t", false), options).confirmationRequired());
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("drop table t", false), options).confirmationRequired());
    }

    @Test
    void sessionStateConflictsAreAlwaysBlocked() {
        ConnectionSafetyOptions options =
                new ConnectionSafetyOptions(ConnectionEnvironment.DEVELOPMENT, false, 60);
        assertTrue(SqlSafetyPolicy.decide(
                SqlSafetyAnalyzer.analyze("start transaction", false), options).blocked());
    }
}
~~~

- [ ] **Step 5: Implement the policy and verify GREEN**

Use this immutable decision:

~~~java
public record Decision(
        boolean blocked,
        boolean confirmationRequired,
        List<SqlSafetyAnalyzer.StatementAnalysis> relevantStatements,
        String message) {
    public Decision {
        relevantStatements = List.copyOf(relevantStatements);
    }
}
~~~

The decide method walks every statement, blocks SESSION_STATE_CONFLICT first, blocks non-READ/non-COMMIT/non-ROLLBACK for read-only connections, requests confirmation for MISSING_WHERE or DESTRUCTIVE_DDL in every environment, and requests confirmation for WRITE, DDL, or UNKNOWN in PRODUCTION. It returns all statements responsible for the strongest decision.

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.sqleditor.SqlSafetyAnalyzerTest --tests com.datacube.sqleditor.SqlSafetyPolicyTest --no-daemon --console=plain
git diff --check
git add src/com/datacube/sqleditor/SqlSafetyAnalyzer.java src/com/datacube/sqleditor/SqlSafetyPolicy.java test/com/datacube/sqleditor/SqlSafetyAnalyzerTest.java test/com/datacube/sqleditor/SqlSafetyPolicyTest.java
git commit -m "feat: 添加 SQL 风险分析"
~~~

Expected: all analyzer and policy tests pass.

---

### Task 3: Statement execution control and provider contracts

**Files:**

- Create: src/com/datacube/spi/SqlExecutionControl.java
- Create: src/com/datacube/spi/SqlExecutionOptions.java
- Modify: src/com/datacube/spi/SqlRunner.java:15-47
- Modify: src/com/datacube/spi/model/QueryResult.java:21-68
- Modify: src/com/datacube/provider/postgres/PgSqlRunner.java
- Modify: src/com/datacube/provider/oracle/OracleSqlRunner.java
- Test: test/com/datacube/spi/SqlExecutionControlTest.java
- Test: test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java
- Test: test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java

**Interfaces:**

- Produces: SqlExecutionOptions(int maxRows, int queryTimeoutSeconds, SqlExecutionControl control).
- Produces: SqlExecutionControl.activate(Statement, int), release(Statement), cancel(), cancellationRequested(), and timeoutSupported().
- QueryResult adds FailureKind SQL_ERROR, CANCELLED, TIMEOUT, plus error, cancelled, and timeout factories.
- Existing SqlRunner int-maxRows overloads remain as compatibility defaults.

- [ ] **Step 1: Write failing execution-control tests**

Use a dynamic proxy Statement and assert timeout, active ownership, cancellation, and release:

~~~java
package com.datacube.spi;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlExecutionControlTest {
    @Test
    void appliesTimeoutCancelsActiveStatementAndReleasesIt() throws Exception {
        AtomicInteger timeout = new AtomicInteger(-1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Statement statement = (Statement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) {
                        timeout.set((Integer) args[0]);
                        return null;
                    }
                    if (method.getName().equals("cancel")) {
                        cancelled.set(true);
                        return null;
                    }
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        SqlExecutionControl control = new SqlExecutionControl();

        control.activate(statement, 25);
        assertEquals(25, timeout.get());
        assertTrue(control.cancel());
        control.release(statement);

        assertTrue(cancelled.get());
        assertFalse(control.hasActiveStatement());
        assertTrue(control.cancellationRequested());
    }
}
~~~

- [ ] **Step 2: Run the focused test and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.spi.SqlExecutionControlTest --no-daemon --console=plain
~~~

Expected: compile failure because SqlExecutionControl does not exist.

- [ ] **Step 3: Implement SqlExecutionControl and options**

Create SqlExecutionControl with one AtomicReference<Statement>, one AtomicBoolean cancellationRequested, and a volatile timeoutSupported flag. activate rejects a second active statement, applies setQueryTimeout when seconds > 0, treats SQLFeatureNotSupportedException as timeoutSupported=false, and propagates every other SQLException. cancel sets cancellationRequested before reading the active reference and returns false when no Statement is active.

Create SqlExecutionOptions:

~~~java
package com.datacube.spi;

import java.util.Objects;

public record SqlExecutionOptions(
        int maxRows,
        int queryTimeoutSeconds,
        SqlExecutionControl control) {

    public SqlExecutionOptions {
        if (maxRows < 0) maxRows = 0;
        if (queryTimeoutSeconds < 0) queryTimeoutSeconds = 0;
        control = Objects.requireNonNull(control, "control");
    }

    public static SqlExecutionOptions defaults(int maxRows) {
        return new SqlExecutionOptions(maxRows, 0, new SqlExecutionControl());
    }
}
~~~

- [ ] **Step 4: Add typed failure outcomes**

Extend QueryResult without changing Kind:

~~~java
public enum FailureKind { SQL_ERROR, CANCELLED, TIMEOUT }
public final FailureKind failureKind;

public static QueryResult error(String errorMessage, long elapsedMillis) {
    return failure(FailureKind.SQL_ERROR, errorMessage, elapsedMillis);
}

public static QueryResult cancelled(String errorMessage, long elapsedMillis) {
    return failure(FailureKind.CANCELLED, errorMessage, elapsedMillis);
}

public static QueryResult timeout(String errorMessage, long elapsedMillis) {
    return failure(FailureKind.TIMEOUT, errorMessage, elapsedMillis);
}

private static QueryResult failure(FailureKind kind, String message, long elapsedMillis) {
    return new QueryResult(Kind.ERROR, null, null, null, -1, elapsedMillis, message, kind);
}
~~~

Query and update factories pass null failureKind. withColumnComments preserves failureKind.

- [ ] **Step 5: Evolve SqlRunner compatibly**

Define option-based abstract methods and keep old signatures as defaults:

~~~java
QueryResult execute(Connection conn, String sql, String schema, SqlExecutionOptions options);

default QueryResult execute(Connection conn, String sql, String schema, int maxRows) {
    return execute(conn, sql, schema, SqlExecutionOptions.defaults(maxRows));
}

List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                  SqlExecutionOptions options, ScriptErrorPolicy policy);

default List<ScriptOutcome> executeScript(Connection conn, String script, String schema,
                                          int maxRows, ScriptErrorPolicy policy) {
    return executeScript(conn, script, schema, SqlExecutionOptions.defaults(maxRows), policy);
}

QueryResult explain(Connection conn, String sql, String schema, boolean analyze,
                    SqlExecutionOptions options);

default QueryResult explain(Connection conn, String sql, String schema, boolean analyze) {
    return explain(conn, sql, schema, analyze, SqlExecutionOptions.defaults(0));
}
~~~

- [ ] **Step 6: Update both providers and add contract tests**

Every Statement creation path uses:

~~~java
try (Statement statement = conn.createStatement()) {
    options.control().activate(statement, options.queryTimeoutSeconds());
    try {
        boolean hasResult = statement.execute(sql);
        long elapsed = System.currentTimeMillis() - startedAt;
        if (hasResult) {
            try (ResultSet resultSet = statement.getResultSet()) {
                return QueryResult.fromResultSet(resultSet, elapsed, options.maxRows());
            }
        }
        return QueryResult.update(elapsed, statement.getUpdateCount());
    } finally {
        options.control().release(statement);
    }
}
~~~

PostgreSQL retains PgColumnComments after reading the ResultSet; Oracle retains strip, OracleColumnComments, PL/SQL handling, and both DBMS_XPLAN paths around this registered Statement pattern. Each provider catches SQLTimeoutException before SQLException and returns QueryResult.timeout. If control.cancellationRequested is true when a SQLException is caught, return QueryResult.cancelled. Provider tests use Connection and Statement proxies to assert timeout is applied to schema, user SQL, and explain paths and that no active Statement remains after success or failure.

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.spi.SqlExecutionControlTest --tests "*SqlRunnerExecutionControlTest" --no-daemon --console=plain
git diff --check
git add src/com/datacube/spi/SqlExecutionControl.java src/com/datacube/spi/SqlExecutionOptions.java src/com/datacube/spi/SqlRunner.java src/com/datacube/spi/model/QueryResult.java src/com/datacube/provider/postgres/PgSqlRunner.java src/com/datacube/provider/oracle/OracleSqlRunner.java test/com/datacube/spi/SqlExecutionControlTest.java test/com/datacube/provider/postgres/PgSqlRunnerExecutionControlTest.java test/com/datacube/provider/oracle/OracleSqlRunnerExecutionControlTest.java
git commit -m "feat: 添加 SQL 执行取消与超时控制"
~~~

Expected: execution-control and provider tests pass, and existing SqlRunner callers compile through compatibility overloads.

---

### Task 4: Dedicated JdbcEditorSession and transaction state machine

**Files:**

- Create: src/com/datacube/service/JdbcEditorSession.java
- Modify: src/com/datacube/service/ConnectionManager.java:78-96
- Test: test/com/datacube/service/JdbcEditorSessionTest.java
- Test: test/com/datacube/service/ConnectionManagerDedicatedSessionTest.java

**Interfaces:**

- ConnectionManager.openDedicated(String) returns an uncached caller-owned Connection.
- ConnectionManager.openEditorSession(String) returns a new JdbcEditorSession on every call.
- JdbcEditorSession exposes TransactionMode, TransactionState, ConnectionState, CancelOutcome, and immutable Snapshot.
- JdbcEditorSession.executeScript(String, String, int, ScriptErrorPolicy, boolean) returns ExecutionBatch.
- JdbcEditorSession.explain(String, String, boolean) returns QueryResult.
- Blocking methods are executeScript, explain, setTransactionMode, commit, rollback, cancel, reconnect, and close.

- [ ] **Step 1: Write failing dedicated-session tests**

The session test uses Connection and SqlRunner stubs and asserts these state transitions:

~~~java
@Test
void manualExecutionCommitAndRollbackUpdateSnapshot() throws Exception {
    JdbcStub jdbc = new JdbcStub();
    StubRunner runner = new StubRunner(QueryResult.update(1, 1));
    JdbcEditorSession session = new JdbcEditorSession(
            "conn", new ConnectionSafetyOptions(ConnectionEnvironment.TEST, false, 30),
            jdbc::open, runner);

    session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);
    session.executeScript("update t set x=1 where id=1", null, 100, null, false);
    assertEquals(JdbcEditorSession.TransactionState.ACTIVE,
            session.snapshot().transactionState());

    session.commit();
    assertEquals(1, jdbc.commits.get());
    assertEquals(JdbcEditorSession.TransactionState.IDLE,
            session.snapshot().transactionState());

    session.executeScript("select 1", null, 100, null, false);
    session.rollback();
    assertEquals(1, jdbc.rollbacks.get());
}

@Test
void manualScriptStopsAtFirstErrorAndCloseRollsBack() throws Exception {
    JdbcStub jdbc = new JdbcStub();
    StubRunner runner = new StubRunner(QueryResult.error("boom", 1));
    JdbcEditorSession session = new JdbcEditorSession(
            "conn", ConnectionSafetyOptions.from(config()), jdbc::open, runner);
    session.setTransactionMode(JdbcEditorSession.TransactionMode.MANUAL);

    session.executeScript("bad; select 1", null, 100,
            (index, sql, message) -> ScriptErrorPolicy.Decision.CONTINUE_ALL, false);
    session.close();

    assertNull(runner.lastPolicy);
    assertEquals(1, jdbc.rollbacks.get());
    assertEquals(1, jdbc.closes.get());
}

private static ConnConfig config() {
    return new ConnConfig("conn", "test", DbType.POSTGRESQL, "localhost", 5432,
            "db", "user", "encrypted", Map.of());
}

private static final class StubRunner implements SqlRunner {
    private final QueryResult result;
    private ScriptErrorPolicy lastPolicy;

    private StubRunner(QueryResult result) {
        this.result = result;
    }

    @Override
    public QueryResult execute(
            Connection connection, String sql, String schema, SqlExecutionOptions options) {
        return result;
    }

    @Override
    public List<ScriptOutcome> executeScript(
            Connection connection,
            String script,
            String schema,
            SqlExecutionOptions options,
            ScriptErrorPolicy policy) {
        lastPolicy = policy;
        return List.of(new ScriptOutcome(1, script, result));
    }

    @Override
    public QueryResult explain(
            Connection connection,
            String sql,
            String schema,
            boolean analyze,
            SqlExecutionOptions options) {
        return result;
    }
}

private static final class JdbcStub {
    private final AtomicInteger commits = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private boolean autoCommit = true;
    private boolean closed;

    private Connection open() {
        closed = false;
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "setAutoCommit" -> {
                            autoCommit = (Boolean) args[0];
                            yield null;
                        }
                        case "getAutoCommit" -> autoCommit;
                        case "setReadOnly" -> null;
                        case "commit" -> {
                            commits.incrementAndGet();
                            yield null;
                        }
                        case "rollback" -> {
                            rollbacks.incrementAndGet();
                            yield null;
                        }
                        case "close" -> {
                            if (!closed) closes.incrementAndGet();
                            closed = true;
                            yield null;
                        }
                        case "isClosed" -> closed;
                        case "isValid" -> !closed;
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }
}

private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) return null;
    if (type == boolean.class) return false;
    if (type == byte.class) return (byte) 0;
    if (type == short.class) return (short) 0;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    if (type == float.class) return 0F;
    if (type == double.class) return 0D;
    if (type == char.class) return '\0';
    return null;
}
~~~

ConnectionManagerDedicatedSessionTest constructs ConnectionManager with the package-private providerResolver constructor defined in Step 5, opens two editor sessions, executes one statement in each, and asserts two distinct proxy connections were opened. It separately calls acquire twice and asserts the shared path opens only one additional connection.

- [ ] **Step 2: Run session tests and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.service.ConnectionManagerDedicatedSessionTest --no-daemon --console=plain
~~~

Expected: compile failure because JdbcEditorSession and the dedicated manager methods do not exist.

- [ ] **Step 3: Implement the session public model**

Use these exact enums and snapshot:

~~~java
public enum ConnectionState { DISCONNECTED, CONNECTED, BROKEN, CLOSED }
public enum TransactionMode { AUTO_COMMIT, MANUAL }
public enum TransactionState { IDLE, ACTIVE, ERROR_PENDING }
public enum CancelOutcome { CANCELLED, CONNECTION_CLOSED, NOTHING_RUNNING }

public record ExecutionBatch(List<ScriptOutcome> outcomes, long elapsedMillis) {
    public ExecutionBatch {
        outcomes = List.copyOf(outcomes);
    }
}

public record Snapshot(
        String connectionId,
        ConnectionState connectionState,
        TransactionMode transactionMode,
        TransactionState transactionState,
        boolean running,
        boolean cancelling,
        boolean timeoutSupported,
        ConnectionSafetyOptions safety) {
    public boolean hasPendingTransaction() {
        return transactionState != TransactionState.IDLE;
    }
}
~~~

The package-private constructor is:

~~~java
JdbcEditorSession(
        String connectionId,
        ConnectionSafetyOptions safety,
        ConnectionOpener opener,
        SqlRunner runner)
~~~

where ConnectionOpener is a nested functional interface whose open method throws SQLException. Use AtomicBoolean running so cancel never waits for the single-flight lock. Connection creation calls setReadOnly and setAutoCommit according to current mode.

The public operation signatures are:

~~~java
public ExecutionBatch executeScript(
        String script,
        String schema,
        int maxRows,
        ScriptErrorPolicy policy,
        boolean oracleMode)
public QueryResult explain(String sql, String schema, boolean analyze)
public void setTransactionMode(TransactionMode mode) throws SQLException
public void commit() throws SQLException
public void rollback() throws SQLException
public CancelOutcome cancel()
public void reconnect() throws SQLException
public Snapshot snapshot()
public void close()
~~~

- [ ] **Step 4: Implement execution and transaction rules**

executeScript creates a fresh SqlExecutionControl per operation and passes:

~~~java
long startedAt = System.currentTimeMillis();
SqlExecutionOptions options =
        new SqlExecutionOptions(maxRows, safety.queryTimeoutSeconds(), control);
ScriptErrorPolicy effectivePolicy =
        transactionMode == TransactionMode.MANUAL ? null : policy;
List<ScriptOutcome> outcomes =
        runner.executeScript(connection(), script, schema, options, effectivePolicy);
long elapsedMillis = System.currentTimeMillis() - startedAt;
return new ExecutionBatch(outcomes, elapsedMillis);
~~~

After manual execution, set ACTIVE when there is no error and ERROR_PENDING when any outcome is ERROR. If the script consists only of COMMIT or ROLLBACK, call the corresponding session method and leave IDLE. setTransactionMode refuses AUTO_COMMIT while pending by throwing IllegalStateException; the UI must commit or roll back first.

commit and rollback require MANUAL, call JDBC, and set IDLE only after success. cancel calls control.cancel; if it returns false or throws SQLException, close only the dedicated connection and set BROKEN. close is idempotent, requests cancellation, rolls back pending manual work best-effort, closes the connection, and sets CLOSED.

- [ ] **Step 5: Add ConnectionManager dedicated factories**

Add a provider resolver test seam and route all existing provider lookups through it:

~~~java
private final java.util.function.Function<DbType, DatabaseProvider> providerResolver;

public ConnectionManager(CredentialCipher cipher) {
    this(cipher, ProviderRegistry::forType);
}

ConnectionManager(
        CredentialCipher cipher,
        java.util.function.Function<DbType, DatabaseProvider> providerResolver) {
    this.cipher = cipher;
    this.redis = new RedisSessionManager(cipher);
    this.providerResolver = Objects.requireNonNull(providerResolver, "providerResolver");
}
~~~

Add the dedicated factories:

~~~java
public Connection openDedicated(String connId) throws SQLException {
    ConnConfig cfg = requireConfig(connId);
    if (cfg.type() == DbType.REDIS) {
        throw new IllegalStateException("Redis 连接不能创建 JDBC 编辑器会话");
    }
    return providerResolver.apply(cfg.type()).connectionFactory().open(withPlainPassword(cfg));
}

public JdbcEditorSession openEditorSession(String connId) {
    ConnConfig cfg = requireConfig(connId);
    DatabaseProvider provider = provider(connId);
    return new JdbcEditorSession(connId, ConnectionSafetyOptions.from(cfg),
            () -> openDedicated(connId), provider.sqlRunner());
}
~~~

Do not add dedicated connections to live; their lifecycle belongs to JdbcEditorSession.

- [ ] **Step 6: Verify GREEN and commit**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.service.ConnectionManagerDedicatedSessionTest --tests com.datacube.spi.SqlExecutionControlTest --no-daemon --console=plain
git diff --check
git add src/com/datacube/service/JdbcEditorSession.java src/com/datacube/service/ConnectionManager.java test/com/datacube/service/JdbcEditorSessionTest.java test/com/datacube/service/ConnectionManagerDedicatedSessionTest.java
git commit -m "feat: 添加独立 JDBC 编辑器会话"
~~~

Expected: session state and isolation tests pass.

---

### Task 5: Asynchronous guarded tab closing

**Files:**

- Create: src/com/datacube/fx/AsyncTabCloseGuard.java
- Create: src/com/datacube/fx/AsyncCloseGate.java
- Modify: src/com/datacube/fx/ContentTabPane.java:33-53
- Test: test/com/datacube/fx/AsyncCloseGateTest.java
- Test: test/com/datacube/fx/ContentTabPaneContractTest.java

**Interfaces:**

- `AsyncTabCloseGuard.requestClose()` immediately returns `CompletionStage<Boolean>`.
- `ContentTabPane` adds `openManagedTab(String, Node, AsyncTabCloseGuard, Runnable)`; the final
  `Runnable` is an FX-only, non-blocking UI finalizer.
- The deprecated three-argument overload remains source-compatible, but runs its disposer on one
  virtual thread. New callers must use the four-argument phase-split API.
- `closeAllManagedTabs()` and `AppShell.shutdownAsync()` return an aggregate
  `CompletionStage<Boolean>`; destructive application teardown starts only after `true`.
  A refused close completes `false`; close-all or virtual-thread startup failure completes
  exceptionally. Both outcomes reset the window-closing latch and permit retry.

- [ ] **Step 1: Write failing close-gate tests**

~~~java
package com.datacube.fx;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncCloseGateTest {
    @Test
    void suppressesDuplicateRequestsAndClosesOnlyAfterApproval() {
        AsyncCloseGate gate = new AsyncCloseGate();
        AtomicInteger closes = new AtomicInteger();

        assertTrue(gate.beginRequest());
        assertFalse(gate.beginRequest());
        gate.complete(false, closes::incrementAndGet);
        assertEquals(0, closes.get());

        assertTrue(gate.beginRequest());
        gate.complete(true, closes::incrementAndGet);
        gate.complete(true, closes::incrementAndGet);
        assertEquals(1, closes.get());
    }
}
~~~

ContentTabPaneContractTest uses reflection to assert both overloads exist, the legacy overload is
deprecated, and AsyncTabCloseGuard has one no-argument method returning CompletionStage.

- [ ] **Step 2: Run tests and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.fx.AsyncCloseGateTest --tests com.datacube.fx.ContentTabPaneContractTest --no-daemon --console=plain
~~~

Expected: compile failure because the close guard types do not exist.

- [ ] **Step 3: Implement the guard and gate**

~~~java
package com.datacube.fx;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface AsyncTabCloseGuard {
    CompletionStage<Boolean> requestClose();
}
~~~

AsyncCloseGate issues a unique request handle/generation. Only that handle can finish its pending
generation; late completion from an older generation cannot consume a newer retry. The coordinator
coalesces duplicate requests and owns exception/null/cancellation/timeout normalization.

- [ ] **Step 4: Wire ContentTabPane**

The guarded overload registers its coordinator before adding the tab. Guard cleanup may block only
off the FX thread. Approval is dispatched back to FX for tab removal and the lightweight finalizer.
External `tabs.remove(tab)` is also intercepted: rejection/failure/timeout restores the tab at its
original index and selection. Timeout completes the caller with `false`, but retains the underlying
cleanup single-flight until that stage reaches a safe terminal state; only then may a new generation
start. Normal completion cancels and removes its daemon-scheduler timer.

- [ ] **Step 5: Verify GREEN and commit**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.fx.AsyncCloseGateTest --tests com.datacube.fx.ContentTabPaneContractTest --tests com.datacube.fx.ManagedTabRegistryTest --no-daemon --console=plain
git diff --check
git add src/com/datacube/fx/AsyncTabCloseGuard.java src/com/datacube/fx/AsyncCloseGate.java src/com/datacube/fx/ContentTabPane.java test/com/datacube/fx/AsyncCloseGateTest.java test/com/datacube/fx/ContentTabPaneContractTest.java
git commit -m "feat: 添加异步标签关闭守卫"
~~~

Expected: gate, contract, and exactly-once lifecycle tests pass.

---

### Task 6: SQL editor safety UI and dedicated-session integration

**Files:**

- Modify: src/com/datacube/fx/SqlEditorPane.java
- Modify: src/com/datacube/fx/AppShell.java:211-220, 236-250
- Modify: test/com/datacube/fx/SqlEditorPaneLifecycleTest.java
- Create: test/com/datacube/fx/SqlEditorSessionContractTest.java

**Interfaces:**

- SqlEditorPane adds `CompletionStage<Boolean> requestClose()`.
- SqlEditorPane lazily binds a previously unbound tab once and owns one JdbcEditorSession.
- All execution and explain calls go through JdbcEditorSession, never ConnectionManager.acquire.
- AppShell passes pane::requestClose to the guarded ContentTabPane overload.

- [ ] **Step 1: Write failing source/lifecycle contract tests**

Extend SqlEditorPaneLifecycleTest:

~~~java
assertEquals(CompletionStage.class,
        SqlEditorPane.class.getMethod("requestClose").getReturnType());
~~~

Create SqlEditorSessionContractTest that reads SqlEditorPane.java and asserts:

~~~java
String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
assertTrue(source.contains("JdbcEditorSession"));
assertTrue(source.contains("SqlSafetyAnalyzer.analyze"));
assertTrue(source.contains("SqlSafetyPolicy.decide"));
assertFalse(source.contains("connections.acquire(connId)"));
assertTrue(source.contains("tasks.submit"));
~~~

- [ ] **Step 2: Run contract tests and verify RED**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest --tests com.datacube.fx.SqlEditorSessionContractTest --no-daemon --console=plain
~~~

Expected: requestClose is absent and the source still uses connections.acquire.

- [ ] **Step 3: Add editor session state and toolbar controls**

Replace the immutable boundConn-only execution model with:

~~~java
private ConnConfig editorConnection;
private JdbcEditorSession jdbcSession;
private ComboBox<JdbcEditorSession.TransactionMode> transactionModeBox;
private Button commitBtn;
private Button rollbackBtn;
private Button cancelBtn;
private Label environmentBadge;
private Label connectionBadge;
private Label transactionStatus;
~~~

Initialize editorConnection from the constructor boundConn. ensureEditorSession takes the current active relational connection only when editorConnection is null, pins it, creates connections.openEditorSession(editorConnection.id()), and never follows later tree selection.

Add a second compact HBox below the existing toolbar with environment and read-only badges, transaction mode combo, commit, rollback, cancel, and timeout text. Production uses red inline CSS, test uses amber, development uses muted text.

- [ ] **Step 4: Gate execution through SQL safety analysis**

Before recordHistory or background submission:

~~~java
boolean oracle = active.type() == DbType.ORACLE;
SqlSafetyAnalyzer.ScriptAnalysis analysis = SqlSafetyAnalyzer.analyze(sql, oracle);
ConnectionSafetyOptions safety = ConnectionSafetyOptions.from(active);
SqlSafetyPolicy.Decision decision = SqlSafetyPolicy.decide(analysis, safety);
if (decision.blocked()) {
    showAlert(decision.message());
    return;
}
if (decision.confirmationRequired() && !confirmSafety(decision, active)) {
    return;
}
~~~

confirmSafety lists environment, connection, statement numbers, risks, and summaries. Its production confirmation button text is “确认在生产环境执行”. It has no persistent ignore option.

The worker operation becomes:

~~~java
JdbcEditorSession editorSession = ensureEditorSession(active);
tasks.submit(() -> editorSession.executeScript(
        sql,
        schema.isEmpty() ? null : schema,
        settings.getMaxResultRows(),
        this::askScriptError,
        oracle), batch -> {
            renderSessionSnapshot(editorSession.snapshot());
            showScriptResults(batch.outcomes(), batch.elapsedMillis());
        }, failure -> {
            renderSessionSnapshot(editorSession.snapshot());
            showError(message(failure), 0);
        });
~~~

Explain uses the same analysis and policy. EXPLAIN ANALYZE analyzes the underlying SQL as executable; ordinary EXPLAIN requires no write confirmation for a read statement.

- [ ] **Step 5: Wire transaction controls and status rendering**

Each control submits one blocking session call:

~~~java
tasks.submit(() -> {
    jdbcSession.setTransactionMode(selectedMode);
    return jdbcSession.snapshot();
}, this::renderSessionSnapshot, failure -> {
    renderSessionSnapshot(jdbcSession.snapshot());
    showError(message(failure), 0);
});

tasks.submit(() -> {
    jdbcSession.commit();
    return jdbcSession.snapshot();
}, this::renderSessionSnapshot, failure -> {
    renderSessionSnapshot(jdbcSession.snapshot());
    showError(message(failure), 0);
});

tasks.submit(() -> {
    jdbcSession.rollback();
    return jdbcSession.snapshot();
}, this::renderSessionSnapshot, failure -> {
    renderSessionSnapshot(jdbcSession.snapshot());
    showError(message(failure), 0);
});

tasks.submit(() -> jdbcSession.cancel(),
        outcome -> renderCancelled(outcome, jdbcSession.snapshot()),
        failure -> {
            renderSessionSnapshot(jdbcSession.snapshot());
            showError(message(failure), 0);
        });
~~~

When switching MANUAL to AUTO_COMMIT with pending work, show commit/rollback/cancel and perform the selected action before changing mode. renderSessionSnapshot independently updates badges and buttons so query result text cannot erase transaction state.

QueryResult FailureKind.CANCELLED displays “已取消”; TIMEOUT displays “执行超时”; SQL_ERROR keeps the existing error presentation.

- [ ] **Step 6: Implement asynchronous close decisions**

`requestClose()` is called on FX and immediately captures the editor/history state plus the user's
close decision. It returns one shared `CompletionStage<Boolean>` for the in-flight attempt.
`requestCancelRollbackClose` offers “取消执行、回滚并关闭 / 取消关闭”;
`requestTransactionClose` offers “提交并关闭 / 回滚并关闭 / 取消”. After the FX decision, run
cancel, wait-for-execution, rollback/commit, history persistence, task-scope close, and JDBC close on
the existing `FxTaskRunner` or a JDK 25 virtual thread. Complete `true` only after all required
blocking cleanup reaches its safe terminal state. A user refusal completes `false`; failure completes
`false` or exceptionally. Never run JDBC/session cleanup from the FX finalizer.

The FX finalizer remains idempotent and lightweight: it only removes listeners and hides UI state.
The coordinator invokes it on the FX Application Thread after the guard completes `true`.

- [ ] **Step 7: Bind AppShell SQL tabs to the guarded overload**

Both openSqlHistory and TreeActions.openSqlEditor construct the Pane as today but call:

~~~java
contentTabs.openManagedTab(
        name,
        pane.getNode(),
        pane::requestClose,
        pane::finalizeCloseOnFx);
~~~

If the generic SQL entry has an active Redis connection, open the tab disconnected rather than binding Redis. Existing tree Redis actions remain unchanged.

- [ ] **Step 8: Verify focused behavior and commit**

Run:

~~~powershell
.\gradlew.bat test --tests com.datacube.fx.SqlEditorPaneLifecycleTest --tests com.datacube.fx.SqlEditorSessionContractTest --tests com.datacube.fx.AsyncCloseGateTest --tests com.datacube.service.JdbcEditorSessionTest --tests com.datacube.sqleditor.SqlSafetyPolicyTest --no-daemon --console=plain
rg -n "connections\.acquire\(connId\)" src/com/datacube/fx/SqlEditorPane.java
git diff --check
git add src/com/datacube/fx/SqlEditorPane.java src/com/datacube/fx/AppShell.java test/com/datacube/fx/SqlEditorPaneLifecycleTest.java test/com/datacube/fx/SqlEditorSessionContractTest.java
git commit -m "feat: 集成安全 SQL 会话"
~~~

Expected: focused tests pass and rg returns no matches.

---

### Task 7: Documentation, full verification, CodeGraph, and release handoff

**Files:**

- Modify: README.md
- Modify: docs/superpowers/plans/2026-08-09-safe-sql-session.md
- Potential generated index update: .codegraph data only when codegraph sync tracks a repository file; do not stage unrelated caches.

**Interfaces:**

- Documents the final user workflow, compatibility behavior, defaults, and limitations.
- Produces fresh evidence for tests, jlink, Git diff, CodeGraph synchronization, and GitHub Actions.

- [ ] **Step 1: Update README with exact user-visible behavior**

Add a “安全 SQL 会话” section containing:

~~~markdown
### 安全 SQL 会话

- 每个 SQL 标签使用独立 JDBC 会话，事务、取消和 schema 不会影响其他标签。
- 支持自动提交、手动事务、提交、回滚、查询超时和执行取消。
- 连接可标记为开发、测试或生产环境，并可设置只读模式。
- 无 WHERE 的 UPDATE/DELETE、DROP/TRUNCATE 和生产环境写入执行前需要确认。
- 关闭未提交事务标签时可提交、回滚或取消关闭；应用退出默认回滚。

关系型连接默认查询超时为 60 秒，可配置为 0 表示不限制。客户端风险分析用于减少误操作，数据库账户权限仍是最终安全边界。
~~~

- [ ] **Step 2: Run the complete local verification**

Run:

~~~powershell
.\gradlew.bat clean test --no-daemon --console=plain
.\gradlew.bat jlink --no-daemon --console=plain
git diff --check
codegraph sync
git status --short
~~~

Expected:

- clean test reports BUILD SUCCESSFUL with zero failed tests.
- jlink reports BUILD SUCCESSFUL and build/image/bin/DataCube.bat exists.
- git diff --check prints nothing.
- codegraph sync completes successfully.
- .testagent/ remains untracked and is not staged.

- [ ] **Step 3: Perform manual JDBC smoke checks**

Using non-production test connections, verify:

~~~text
PostgreSQL tab A: MANUAL -> UPDATE -> tab B SELECT does not see uncommitted value -> COMMIT -> tab B sees value
PostgreSQL: pg_sleep query -> Cancel -> only tab A stops
PostgreSQL read-only: INSERT is blocked before JDBC execution
Oracle: MANUAL -> UPDATE -> ROLLBACK restores value
Oracle: long query -> timeout/cancel returns the editor to usable or reconnectable state
Production-marked connection: SELECT runs directly; UPDATE requires the production confirmation
Close pending tab: Cancel Close keeps tab; Rollback and Close removes it; Commit and Close persists data
Application exit with pending manual transaction: data is rolled back
~~~

For PostgreSQL, use a disposable local Docker database when Docker is available; otherwise use an already configured non-production connection. Oracle smoke testing requires an explicitly supplied non-production Oracle endpoint. If no Oracle endpoint is available, do not invent credentials or target an unknown database: retain the Oracle provider proxy tests as automated evidence and report the missing live Oracle smoke check as residual risk in the handoff. Do not use the user-provided Redis endpoint for relational smoke tests. Redis validation remains the existing automated integration test.

- [ ] **Step 4: Mark plan tasks complete, commit, and push**

After every checkbox has evidence, mark it complete in this plan and run:

~~~powershell
git add README.md docs/superpowers/plans/2026-08-09-safe-sql-session.md
git commit -m "docs: 完善安全 SQL 会话说明"
git push origin main
~~~

Expected: main is synchronized with origin/main and .testagent/ is absent from every commit.

- [ ] **Step 5: Observe GitHub Verify**

Run:

~~~powershell
$sha = git rev-parse HEAD
$runId = gh run list --commit $sha --limit 5 --json databaseId --jq '.[0].databaseId'
if (-not $runId) { throw "Verify run was not created for $sha" }
gh run watch $runId --exit-status --interval 5
~~~

Expected: wrapper-validation, Test (ubuntu-latest), Test (windows-latest), Windows linked image, and redis-integration all succeed.

---

## Plan self-review checklist

- Spec coverage: Tasks 1-7 cover configuration, risk analysis, Statement control, dedicated sessions, transaction state, asynchronous close, UI, documentation, local verification, and CI.
- Scope: SSL/SSH, workspace restore, schema comparison, savepoint UI, connection pooling, and new database types remain excluded.
- Type consistency: ConnectionSafetyOptions, SqlSafetyAnalyzer.ScriptAnalysis, SqlSafetyPolicy.Decision, SqlExecutionOptions, JdbcEditorSession.Snapshot, and AsyncTabCloseGuard signatures are defined once and consumed consistently.
- Security: only whitelisted props persist; transient plaintext credentials are explicitly excluded.
- Concurrency: long JDBC operations run through FxTaskScope; cancel remains able to run concurrently with the active statement.
- Compatibility: shared ConnectionManager.acquire and the old SqlRunner overloads remain available to existing consumers.
