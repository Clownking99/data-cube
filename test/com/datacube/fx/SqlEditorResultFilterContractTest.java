package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.CredentialCipher;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.provider.postgres.PostgresProvider;
import com.datacube.service.ConnectionManager;
import com.datacube.service.JdbcEditorSession;
import com.datacube.spi.DatabaseProvider;
import com.datacube.spi.ScriptErrorPolicy;
import com.datacube.spi.SqlExecutionControl;
import com.datacube.spi.SqlExecutionOptions;
import com.datacube.spi.SqlParameter;
import com.datacube.spi.SqlRunner;
import com.datacube.spi.model.ConnConfig;
import com.datacube.spi.model.ConnectionSafetyOptions;
import com.datacube.spi.model.DbType;
import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.spi.model.ScriptOutcome;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.RenderedFilterQuery;
import com.datacube.sqleditor.result.ResultFilterSqlRenderer;
import com.datacube.sqleditor.result.ResultFilterState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javafx.event.ActionEvent;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.postgresql.util.PGobject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorResultFilterContractTest {
    private static final String SAFE_POSTGRES_REQUERY_SQL =
            "select 'Ada' AS name, 7 AS score, '2026-08-29 10:11:12' AS created_at";
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "NAME", Types.VARCHAR, "varchar"),
            new ResultColumn(1, "SCORE", Types.INTEGER, "int4"),
            new ResultColumn(2, "CREATED_AT", Types.TIMESTAMP, "timestamp"));

    @TempDir Path directory;

    @Test
    void preparedFailureUiMessageRejectsUntrustedDriverTextButKeepsSafeCodes() throws Exception {
        String sentinel = "sentinel-ui-secret-7f3a";
        Method sanitizer = SqlEditorPane.class.getDeclaredMethod(
                "databaseFilterResultFailureMessage", QueryResult.class);
        sanitizer.setAccessible(true);

        String rejected = (String) sanitizer.invoke(null,
                QueryResult.error("driver echoed " + sentinel, 1));
        assertFalse(rejected.contains(sentinel));
        assertEquals("数据库筛选执行失败", rejected);

        String coded = (String) sanitizer.invoke(null,
                QueryResult.error("数据库查询失败 (SQLState=42000, vendorCode=942)", 1));
        assertEquals("数据库查询失败 (SQLState=42000, vendorCode=942)", coded);
    }

    @Test
    void databaseFilterUsesOwnedSessionAndPreservesResultOnFailure() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        assertTrue(source.contains("SafeSelectEligibility.check"));
        assertTrue(source.contains("resultFilterSqlRenderer"));
        assertTrue(source.contains("executePrepared"));
        assertTrue(source.contains("databaseFailed"));
        assertFalse(source.contains("DriverManager.getConnection"));

        String sentinel = "sentinel-pane-secret-7f3a";
        PreparedRunner prepared = new PreparedRunner(QueryResult.error(
                "driver rejected filter containing " + sentinel, 12));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });

            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS), "prepared query did not start");
            SerialSessionOperationQueue operations = operations(fixture.pane);
            assertEquals(SerialSessionOperationQueue.OperationKind.EXECUTE,
                    operations.snapshot().currentKind());
            FxUiTestSupport.call(() -> {
                assertTrue(fixture.pane.getNode().lookup("#sql-result-add-filter").isDisabled());
                assertTrue(fixture.pane.getNode().lookup("#sql-result-clear-filter").isDisabled());
                assertTrue(fixture.pane.getNode().lookup("#sql-execute").isDisabled());
                return null;
            });

            prepared.release.countDown();
            operations.idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(original.rows, snapshot.activeResult().rows,
                        "failed re-query must retain the previous result");
                assertEquals("数据库筛选执行失败", snapshot.recoverableError());
                assertFalse(snapshot.toString().contains(sentinel));
                assertFalse(labelText(fixture.pane, "statusLabel").contains(sentinel));
                assertFalse(((javafx.scene.control.Label) fixture.pane.getNode()
                        .lookup("#sql-result-summary")).getText().contains(sentinel));
                assertEquals(1, resultTable(fixture.pane).getItems().size(),
                        "failure keeps the prior local preview visible");
                assertFalse(operations.snapshot().pending());
                return null;
            });
            assertEquals(1, prepared.preparedCalls.get());
            assertTrue(prepared.lastSql.contains(
                    "FROM (\n" + SAFE_POSTGRES_REQUERY_SQL + "\n)"));
            assertTrue(prepared.lastSql.contains(
                    "\"SCORE\" OPERATOR(pg_catalog.>) ?"));
            assertFalse(prepared.lastSql.contains("\"SCORE\" > ?"));
            assertEquals(1, prepared.lastParameters.size());
            assertSame(fixture.ownedSession, field(fixture.pane, "jdbcSession"));
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void terminalDatabaseFailuresPreserveTheExactVisibleTablePresentation() throws Exception {
        assertTerminalFailurePreservesTable(
                QueryResult.error("unsafe driver diagnostic", 4), "数据库筛选执行失败");
        assertTerminalFailurePreservesTable(
                QueryResult.timeout("unsafe timeout diagnostic", 5), "数据库筛选超时");
        assertTerminalFailurePreservesTable(
                QueryResult.cancelled("unsafe cancel diagnostic", 6), "数据库筛选已取消");
    }

    @Test
    void immediateApplyCommitsPendingSearchAndDelayedDebounceCannotInvalidateSuccess()
            throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Bob", 9, "2026-08-29 11:12:13")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13")), SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((TextField) fixture.pane.getNode().lookup("#sql-result-search")).setText("Bob");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                assertEquals("Bob", state.snapshot().searchText());
                return null;
            });

            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            awaitFxDelay(javafx.util.Duration.millis(300));

            FxUiTestSupport.call(() -> {
                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(ResultFilterState.DatabaseStatus.APPLIED, snapshot.databaseStatus());
                assertEquals("Bob", snapshot.searchText());
                assertEquals(List.of(0), snapshot.visibleRowIndexes());
                assertEquals(1, prepared.preparedCalls.get());
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void synchronousQueueRejectionPreservesTheExactVisibleTablePresentation() throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Bob", 9, "2026-08-29 11:12:13")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13")), SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                TablePresentation before = arrangeAndCaptureTable(fixture.pane);
                operations(fixture.pane).close();

                invoke(fixture.pane, "onApplyDatabaseFilter");

                assertTablePresentation(before, fixture.pane);
                assertEquals(List.of(1), state.snapshot().visibleRowIndexes());
                assertEquals("数据库筛选执行失败", state.snapshot().recoverableError());
                assertEquals(0, prepared.preparedCalls.get());
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void clipboardWritesMustUseAnInjectableBooleanResultInsteadOfAssumingSuccess() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        assertTrue(source.contains("ClipboardWriter"));
        assertTrue(source.contains("return Clipboard.getSystemClipboard().setContent(content)"));
    }

    @Test
    void clipboardFailureNeverClaimsSuccessAndInsertCopyUsesTheSameSeam() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>("unchanged");
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, result(false,
                        row("Ada", 7, "2026-08-29 10:11:12")),
                        "select * from people");
                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                table.getSelectionModel().clearAndSelect(0, table.getColumns().get(1));
                fixture.pane.setClipboardWriterForTesting(ignored -> false);

                ((MenuButton) fixture.pane.getNode().lookup("#sql-result-copy"))
                        .getItems().get(0).fire();
                assertEquals("unchanged", captured.get());
                assertEquals("复制失败：无法写入系统剪贴板",
                        labelText(fixture.pane, "statusLabel"));

                invoke(fixture.pane, "onCopyInsert");
                assertEquals("unchanged", captured.get());
                assertEquals("复制失败：无法写入系统剪贴板",
                        labelText(fixture.pane, "statusLabel"));

                fixture.pane.setClipboardWriterForTesting(text -> {
                    captured.set(text);
                    return true;
                });
                invoke(fixture.pane, "onCopyInsert");
                assertTrue(captured.get().startsWith("INSERT INTO people"));
                assertTrue(labelText(fixture.pane, "statusLabel").startsWith("已复制 1 条 INSERT"));
                return null;
            });
        }
    }

    @Test
    void conditionSecretStaysPrivateThroughSnapshotToolbarStatusAndPreparedExecution() throws Exception {
        String sentinel = "sentinel-pane-condition-secret-92bd";
        PreparedRunner prepared = new PreparedRunner(result(false,
                row(sentinel, 7, "2026-08-29 10:11:12")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row(sentinel, 7, "2026-08-29 10:11:12"),
                    row("other", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.appendCondition(new FilterCondition(
                        0, FilterConnector.AND, FilterOperator.EQ, sentinel));
                invoke(fixture.pane, "renderResultFilterSnapshot");

                ResultFilterState.Snapshot snapshot = state.snapshot();
                Button chip = (Button) fixture.pane.getNode().lookup("#sql-result-filter-remove-0");
                assertEquals("<redacted>", snapshot.conditions().getFirst().value());
                assertFalse(snapshot.toString().contains(sentinel));
                assertEquals(List.of(0), snapshot.visibleRowIndexes(),
                        "the private raw value must still drive local filtering");
                assertFalse(chip.getText().contains(sentinel));
                assertFalse(chip.getAccessibleText().contains(sentinel));
                assertTrue(chip.getText().contains("<redacted>"));
                assertTrue(chip.getAccessibleText().contains("<redacted>"));
                assertFalse(labelText(fixture.pane, "statusLabel").contains(sentinel));
                assertFalse(((javafx.scene.control.Label) fixture.pane.getNode()
                        .lookup("#sql-result-summary")).getText().contains(sentinel));

                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });

            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            assertEquals(sentinel, prepared.lastParameters.getFirst().value(),
                    "prepared execution must receive the real private value");
            assertFalse(prepared.lastParameters.toString().contains(sentinel));
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertFalse(labelText(fixture.pane, "statusLabel").contains(sentinel));
                assertFalse(state(fixture.pane).snapshot().toString().contains(sentinel));
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void unsupportedDatabaseOperatorKeepsLocalPreviewAndCannotReachPreparedExecution() throws Exception {
        String sentinel = "sentinel-json-condition-secret-71ad";
        String unavailable = "列“PAYLOAD”（JSON 类型）不支持数据库筛选运算符“等于”；本地筛选仍可使用";
        List<ResultColumn> columns = List.of(
                new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
                new ResultColumn(1, "PAYLOAD", Types.OTHER, "jsonb"));
        PreparedRunner prepared = new PreparedRunner(QueryResult.queryWithMetadata(
                columns, List.of(List.of("Ada", sentinel)), 1, false));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = QueryResult.queryWithMetadata(
                    columns,
                    List.of(List.of("Ada", sentinel), List.of("Ada", "other"),
                            List.of("Bob", sentinel)),
                    37, false);

            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original,
                        "select 'Ada' AS name, '{}' AS payload");
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(
                        new FilterCondition(
                                0, FilterConnector.AND, FilterOperator.EQ, "Ada"),
                        new FilterCondition(
                                1, FilterConnector.AND, FilterOperator.EQ, sentinel)));
                invoke(fixture.pane, "renderResultFilterSnapshot");

                ResultFilterState.Snapshot snapshot = state.snapshot();
                Button apply = (Button) fixture.pane.getNode()
                        .lookup("#sql-result-apply-database");
                assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                        snapshot.databaseStatus());
                assertEquals(List.of(0), snapshot.visibleRowIndexes(),
                        "provider rejection must not disable local filtering");
                assertEquals(unavailable, snapshot.databaseUnavailableReason());
                assertTrue(apply.isDisabled());
                assertEquals(unavailable, apply.getTooltip().getText());
                assertEquals(unavailable, apply.getAccessibleHelp());
                assertFalse(unavailable.contains(sentinel));
                assertFalse(snapshot.toString().contains(sentinel));

                apply.fire();
                assertEquals(0, prepared.preparedCalls.get());
                assertEquals(unavailable,
                        assertThrows(IllegalStateException.class, state::databaseRequest)
                                .getMessage());

                state.replaceCondition(1, new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.IS_NULL, null));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                assertNull(state.snapshot().databaseUnavailableReason());
                assertFalse(apply.isDisabled(),
                        "parameter-free null predicates remain supported for JSON");
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void ambiguousOracleBareFunctionReferencesCannotReachPreparedExecution() throws Exception {
        String unavailable = "该 Oracle SELECT 超出可证明安全的 SYS.DUAL 通配符子集；本地筛选仍可使用";
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Ada", 7, "2026-08-29 10:11:12")));
        try (PaneFixture fixture = databaseFixture(prepared, DbType.ORACLE)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));

            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, "select d.* from SYS.DUAL d");
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                Button apply = (Button) fixture.pane.getNode()
                        .lookup("#sql-result-apply-database");
                assertNull(state.snapshot().databaseUnavailableReason(),
                        "the explicit plain wildcard subset must remain available");
                assertFalse(apply.isDisabled());

                for (String sql : List.of(
                        "select side_effecting_zero_arg from dual",
                        "select app.pkg.side_effecting_zero_arg from dual",
                        "select * from remote_synonym",
                        "select v.* from app.side_effecting_view v")) {
                    showQuery(fixture.pane, original, sql);
                    state.setConditions(List.of(new FilterCondition(
                            0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                    invoke(fixture.pane, "renderResultFilterSnapshot");

                    assertEquals(unavailable, state.snapshot().databaseUnavailableReason(), sql);
                    assertTrue(apply.isDisabled(), sql);
                    assertEquals(unavailable, apply.getTooltip().getText(), sql);
                    apply.fire();
                    assertEquals(0, prepared.preparedCalls.get(), sql);
                    List<Integer> visibleBefore = state.snapshot().visibleRowIndexes();
                    state.setDatabaseUnavailableReason(null);
                    invoke(fixture.pane, "onApplyDatabaseFilter");
                    assertEquals(unavailable,
                            state.snapshot().databaseUnavailableReason(), sql);
                    assertEquals(visibleBefore, state.snapshot().visibleRowIndexes(), sql);
                    assertEquals(0, prepared.preparedCalls.get(), sql);
                    assertEquals(unavailable,
                            assertThrows(IllegalStateException.class, state::databaseRequest)
                                    .getMessage(),
                            sql);
                }
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void postgresRelationsTypeInputsAndOperatorsCannotReachPreparedExecution() throws Exception {
        String unavailable = "该 PostgreSQL SELECT 超出可证明安全的无 FROM 基础字面量子集；本地筛选仍可使用";
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Ada", 7, "2026-08-29 10:11:12")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));

            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original,
                        "select 'Ada' AS name, 7 AS score, '2026-08-29' AS created_at");
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                Button apply = (Button) fixture.pane.getNode()
                        .lookup("#sql-result-apply-database");
                assertNull(state.snapshot().databaseUnavailableReason());
                assertFalse(apply.isDisabled());

                for (String sql : List.of(
                        "select * from policy_protected_table",
                        "select * from side_effecting_view",
                        "select dangerous_type 'payload'",
                        "select payload + 1 from events")) {
                    showQuery(fixture.pane, original, sql);
                    state.setConditions(List.of(new FilterCondition(
                            0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                    invoke(fixture.pane, "renderResultFilterSnapshot");

                    assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                            state.snapshot().databaseStatus(), sql);
                    assertEquals(List.of(0), state.snapshot().visibleRowIndexes(), sql);
                    assertEquals(unavailable, state.snapshot().databaseUnavailableReason(), sql);
                    assertTrue(apply.isDisabled(), sql);
                    state.setDatabaseUnavailableReason(null);
                    invoke(fixture.pane, "onApplyDatabaseFilter");
                    assertEquals(unavailable,
                            state.snapshot().databaseUnavailableReason(), sql);
                    assertEquals(0, prepared.preparedCalls.get(), sql);
                }
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void capabilityEvaluationFailuresUseAFixedFailClosedReasonWithoutDiagnosticLeakage()
            throws Exception {
        String unavailable = "当前数据库筛选能力无法安全确认；本地筛选仍可使用";
        String sentinel = "sentinel-capability-diagnostic-secret-320d";
        for (String diagnostic : Arrays.asList(null, "", sentinel)) {
            PreparedRunner prepared = new PreparedRunner(result(false,
                    row("Ada", 7, "2026-08-29 10:11:12")));
            ResultFilterSqlRenderer failingRenderer = new ResultFilterSqlRenderer() {
                @Override
                public RenderedFilterQuery render(String originalSql, List<ResultColumn> columns,
                        List<FilterCondition> conditions) {
                    throw new AssertionError("a rejected capability must never render SQL");
                }

                @Override
                public ConditionSupport conditionSupport(
                        ResultColumn column, FilterOperator operator) {
                    throw diagnostic == null
                            ? new IllegalStateException()
                            : new IllegalStateException(diagnostic);
                }
            };
            try (PaneFixture fixture = databaseFixture(prepared, failingRenderer)) {
                QueryResult original = result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13"));

                FxUiTestSupport.call(() -> {
                    showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                    ResultFilterState state = state(fixture.pane);
                    state.setConditions(List.of(new FilterCondition(
                            0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                    invoke(fixture.pane, "renderResultFilterSnapshot");

                    ResultFilterState.Snapshot snapshot = state.snapshot();
                    Button apply = (Button) fixture.pane.getNode()
                            .lookup("#sql-result-apply-database");
                    assertEquals(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                            snapshot.databaseStatus());
                    assertEquals(List.of(0), snapshot.visibleRowIndexes());
                    assertEquals(unavailable, snapshot.databaseUnavailableReason());
                    assertTrue(apply.isDisabled());
                    assertEquals(unavailable, apply.getTooltip().getText());
                    assertEquals(unavailable, apply.getAccessibleHelp());
                    assertFalse(snapshot.toString().contains(sentinel));
                    assertFalse(unavailable.contains(sentinel));
                    apply.fire();
                    assertEquals(0, prepared.preparedCalls.get());
                    assertEquals(unavailable,
                            assertThrows(IllegalStateException.class, state::databaseRequest)
                                    .getMessage());
                    return null;
                });
            } finally {
                prepared.release.countDown();
            }
        }
    }

    @Test
    void applyTimeRendererFailureUsesAFixedReasonWithoutDiagnosticLeakage() throws Exception {
        String unavailable = "当前数据库筛选能力无法安全确认；本地筛选仍可使用";
        String sentinel = "sentinel-renderer-diagnostic-secret-31be";
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Ada", 7, "2026-08-29 10:11:12")));
        ResultFilterSqlRenderer failingRenderer = new ResultFilterSqlRenderer() {
            @Override
            public RenderedFilterQuery render(String originalSql, List<ResultColumn> columns,
                    List<FilterCondition> conditions) {
                throw new IllegalStateException(sentinel);
            }

            @Override
            public ConditionSupport conditionSupport(
                    ResultColumn column, FilterOperator operator) {
                return ConditionSupport.allowed();
            }
        };
        try (PaneFixture fixture = databaseFixture(prepared, failingRenderer)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));

            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original,
                        "select 'Ada' AS name, 7 AS score, '2026-08-29' AS created_at");
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        0, FilterConnector.AND, FilterOperator.EQ, "Ada")));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                assertNull(state.snapshot().databaseUnavailableReason());
                TablePresentation before = arrangeAndCaptureTable(fixture.pane);

                ((Button) fixture.pane.getNode()
                        .lookup("#sql-result-apply-database")).fire();

                ResultFilterState.Snapshot snapshot = state.snapshot();
                assertEquals(unavailable, snapshot.recoverableError());
                assertEquals(List.of(0), snapshot.visibleRowIndexes());
                assertEquals(0, prepared.preparedCalls.get());
                assertTablePresentation(before, fixture.pane);
                assertEquals("数据库筛选失败，仍显示当前结果：" + unavailable,
                        labelText(fixture.pane, "statusLabel"));
                assertFalse(snapshot.toString().contains(sentinel));
                assertFalse(labelText(fixture.pane, "statusLabel").contains(sentinel));
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void databaseFilterReusesSchemaCapturedByTheOriginalPaneExecution() throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Bob", 9, "2026-08-29 11:12:13")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            FxUiTestSupport.call(() -> {
                ((TextField) field(fixture.pane, "schemaField")).setText("schema_a");
                ((org.fxmisc.richtext.CodeArea) field(fixture.pane, "editorArea"))
                        .replaceText(SAFE_POSTGRES_REQUERY_SQL);
                ((Button) fixture.pane.getNode().lookup("#sql-execute")).fire();
                return null;
            });
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState state = state(fixture.pane);
                assertNotNull(state.snapshot().originalResult(), "the pane execution must publish its query result");
                assertEquals("schema_a", state.snapshot().effectiveSchema());
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                ((TextField) field(fixture.pane, "schemaField")).setText("schema_b");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });

            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            assertEquals("schema_a", prepared.lastScriptSchema);
            assertEquals("schema_a", prepared.lastSchema,
                    "database Apply must not silently switch to the edited schema field");
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertTrue(labelText(fixture.pane, "statusLabel").contains("schema_a"));
                assertFalse(labelText(fixture.pane, "statusLabel").contains("schema_b"));
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void schemaEditDuringOriginalExecutionCannotChangePublishedRequestOrApplySchema() throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Bob", 9, "2026-08-29 11:12:13")));
        prepared.blockScript = true;
        try (PaneFixture fixture = databaseFixture(prepared)) {
            FxUiTestSupport.call(() -> {
                ((TextField) field(fixture.pane, "schemaField")).setText("schema_a");
                ((org.fxmisc.richtext.CodeArea) field(fixture.pane, "editorArea"))
                        .replaceText(SAFE_POSTGRES_REQUERY_SQL);
                ((Button) fixture.pane.getNode().lookup("#sql-execute")).fire();
                return null;
            });
            assertTrue(prepared.scriptEntered.await(5, TimeUnit.SECONDS),
                    "original query did not enter the blocked runner");

            FxUiTestSupport.call(() -> {
                ((TextField) field(fixture.pane, "schemaField")).setText("schema_b");
                return null;
            });
            prepared.scriptRelease.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState state = state(fixture.pane);
                assertEquals("schema_a", state.snapshot().effectiveSchema(),
                        "completion must publish the schema captured at submission");
                state.appendCondition(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7));
                assertEquals("schema_a", state.databaseRequest().effectiveSchema(),
                        "generation-bound request must retain the captured schema");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });

            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            assertEquals("schema_a", prepared.lastScriptSchema);
            assertEquals("schema_a", prepared.lastSchema);
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertTrue(labelText(fixture.pane, "statusLabel").contains("schema_a"));
                assertFalse(labelText(fixture.pane, "statusLabel").contains("schema_b"));
                return null;
            });
        } finally {
            prepared.scriptRelease.countDown();
            prepared.release.countDown();
        }
    }

    @Test
    void staleDatabaseCompletionCannotReplaceANewerLocalView() throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(false,
                row("Bob", 9, "2026-08-29 11:12:13")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });
            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));

            FxUiTestSupport.call(() -> {
                ResultFilterState state = state(fixture.pane);
                state.setSearchText("Ada");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                return null;
            });
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(original.rows, snapshot.activeResult().rows);
                assertEquals("Ada", snapshot.searchText());
                assertTrue(snapshot.visibleRowIndexes().isEmpty(),
                        "newer search is combined with the still-present condition");
                assertTrue(resultTable(fixture.pane).getItems().isEmpty());
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void successfulDatabaseFilterCarriesCommentsAndClearRestoresOriginalStatus() throws Exception {
        QueryResult filtered = result(false,
                row("Bob", 9, "2026-08-29 11:12:13"))
                .withColumnComments(List.of("remote-name", "remote-score", "remote-created"));
        PreparedRunner prepared = new PreparedRunner(filtered);
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });
            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState.Snapshot applied = state(fixture.pane).snapshot();
                assertEquals(ResultFilterState.DatabaseStatus.APPLIED, applied.databaseStatus());
                assertEquals(filtered.rows, applied.activeResult().rows);
                assertEquals(original.columnComments, applied.activeResult().columnComments,
                        "database result must retain the original result comments");
                assertEquals(1, resultTable(fixture.pane).getItems().size());
                assertTrue(labelText(fixture.pane, "statusLabel").contains("数据库筛选已应用"));

                ((Button) fixture.pane.getNode().lookup("#sql-result-clear-filter")).fire();
                ResultFilterState.Snapshot cleared = state(fixture.pane).snapshot();
                assertEquals(ResultFilterState.DatabaseStatus.ORIGINAL, cleared.databaseStatus());
                assertEquals(original.rows, cleared.activeResult().rows);
                assertEquals(2, resultTable(fixture.pane).getItems().size());
                assertTrue(labelText(fixture.pane, "statusLabel").contains("已清除筛选"));
                assertTrue(labelText(fixture.pane, "statusLabel").contains("2 rows"));
                assertFalse(labelText(fixture.pane, "statusLabel").contains("数据库筛选已应用"));
                assertFalse(operations(fixture.pane).snapshot().pending());
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void mismatchedDatabaseColumnsBecomeRecoverableFailureWithoutReplacingRows() throws Exception {
        QueryResult mismatched = QueryResult.queryWithMetadata(
                List.of(new ResultColumn(0, "OTHER", Types.VARCHAR, "VARCHAR")),
                List.of(List.of("wrong")), 4, false);
        PreparedRunner prepared = new PreparedRunner(mismatched);
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
                return null;
            });
            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> null);

            FxUiTestSupport.call(() -> {
                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(original.rows, snapshot.activeResult().rows);
                assertTrue(snapshot.recoverableError().contains("不一致的列结构"));
                assertEquals(1, resultTable(fixture.pane).getItems().size(),
                        "the prior local preview remains visible");
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void resultToolbarIsInsideTheResultContainerAndSearchNeverSubmitsSessionWork() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        assertTrue(source.contains("new SqlResultToolbar"));
        assertTrue(source.contains("resultFilterState.setSearchText"));
        assertTrue(source.contains("TsvClipboardFormatter"));

        try (PaneFixture fixture = new PaneFixture(null, null)) {
            Timestamp timestamp = Timestamp.valueOf("2026-08-29 10:11:12");
            QueryResult original = result(false,
                    Arrays.asList("Ada", 7, timestamp),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                Parent resultToolbar = (Parent) fixture.pane.getNode().lookup(".sql-result-toolbar");
                assertNotNull(resultToolbar);
                assertNotNull(resultToolbar.getParent(), "toolbar must be embedded in the result container");

                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                assertInstanceOf(java.time.LocalDateTime.class, table.getItems().getFirst().get(2),
                        "table rows must retain typed, immutable JDBC values");
                TextField search = (TextField) resultToolbar.lookup("#sql-result-search");
                search.setText("Bob");
                search.fireEvent(new ActionEvent());

                assertEquals(1, table.getItems().size());
                assertEquals("Bob", table.getItems().getFirst().getFirst());
                assertEquals(List.of(1), state(fixture.pane).snapshot().visibleRowIndexes());
                assertFalse(operations(fixture.pane).snapshot().pending());
                assertNull(field(fixture.pane, "jdbcSession"));
                return null;
            });
        }
    }

    @Test
    void copyModesUseVisibleFormattedRowsAndClearRestoresCachedOriginal() throws Exception {
        PreparedRunner prepared = new PreparedRunner(QueryResult.error("unused", 0));
        AtomicReference<String> clipboard = new AtomicReference<>();
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                fixture.pane.setClipboardWriterForTesting(text -> {
                    clipboard.set(text);
                    return true;
                });
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                TableColumn<ObservableList<Object>, ?> name = table.getColumns().get(1);
                TableColumn<ObservableList<Object>, ?> score = table.getColumns().get(2);
                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, score);

                MenuButton copy = (MenuButton) fixture.pane.getNode().lookup("#sql-result-copy");
                copy.getItems().get(1).fire();
                assertEquals("Ada\t\n\t9", clipboard.get());
                assertEquals("已复制 2 个单元格", labelText(fixture.pane, "statusLabel"));

                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, score);
                copy.getItems().get(3).fire();
                assertEquals("NAME\tSCORE\tCREATED_AT\n"
                                + "Ada\t7\t2026-08-29 10:11:12\n"
                                + "Bob\t9\t2026-08-29 11:12:13",
                        clipboard.get());
                assertEquals("已复制 2 行", labelText(fixture.pane, "statusLabel"));

                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        0, FilterConnector.AND, FilterOperator.EQ, "Bob")));
                ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();
                assertTrue(state.databaseApplied(request.generation(), result(false,
                        row("Bob", 9, "2026-08-29 11:12:13"))));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                assertEquals(1, table.getItems().size());

                ((Button) fixture.pane.getNode().lookup("#sql-result-clear-filter")).fire();
                assertEquals(original.rows, state.snapshot().activeResult().rows);
                assertEquals(2, table.getItems().size());
                assertFalse(operations(fixture.pane).snapshot().pending(),
                        "clearing filters must not query JDBC");
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void allCopyModesFollowVisibleColumnOrderAndHandleRaggedRowsAndShortcut() throws Exception {
        AtomicReference<String> clipboard = new AtomicReference<>();
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    new ArrayList<Object>(List.of("Ragged")),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                fixture.pane.setClipboardWriterForTesting(text -> {
                    clipboard.set(text);
                    return true;
                });
                showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                table.applyCss();
                table.layout();
                assertEquals(4, table.getVisibleLeafColumns().size());
                TableColumn<ObservableList<Object>, ?> sequence = table.getColumns().get(0);
                TableColumn<ObservableList<Object>, ?> name = table.getColumns().get(1);
                TableColumn<ObservableList<Object>, ?> score = table.getColumns().get(2);
                TableColumn<ObservableList<Object>, ?> created = table.getColumns().get(3);
                table.getColumns().setAll(sequence, created, name, score);
                table.applyCss();
                table.layout();
                MenuButton copy = (MenuButton) fixture.pane.getNode().lookup("#sql-result-copy");

                table.getSelectionModel().clearAndSelect(0, created);
                table.getFocusModel().focus(0, created);
                assertEquals(1, table.getSelectionModel().getSelectedCells().size());
                copy.getItems().get(0).fire();
                assertEquals("2026-08-29 10:11:12", clipboard.get());

                table.getSelectionModel().clearAndSelect(0, created);
                table.getSelectionModel().select(1, name);
                copy.getItems().get(1).fire();
                assertEquals("2026-08-29 10:11:12\t\n\tRagged",
                        clipboard.get());

                copy.getItems().get(2).fire();
                assertEquals("2026-08-29 10:11:12\tAda\t7\n\tRagged\t",
                        clipboard.get());

                copy.getItems().get(3).fire();
                assertEquals("CREATED_AT\tNAME\tSCORE\n"
                                + "2026-08-29 10:11:12\tAda\t7\n\tRagged\t",
                        clipboard.get());

                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, name);
                KeyEvent shortcut = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                        false, true, false, false);
                table.fireEvent(shortcut);
                assertEquals("Ada\nRagged", clipboard.get());

                clipboard.set("keep clipboard");
                table.getSelectionModel().clearSelection();
                table.getFocusModel().focus(-1);
                KeyEvent emptyShortcut = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                        false, true, false, false);
                table.fireEvent(emptyShortcut);
                assertEquals("keep clipboard", clipboard.get());

                state(fixture.pane).setSearchText("Ada");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                table.applyCss();
                table.layout();
                TableColumn<ObservableList<Object>, ?> filteredName = table.getColumns().get(1);
                table.getSelectionModel().clearAndSelect(0, filteredName);
                assertEquals(1, table.getSelectionModel().getSelectedCells().size());
                copy.getItems().get(2).fire();
                assertEquals("Ada\t7\t2026-08-29 10:11:12",
                        clipboard.get(),
                        "copy must use only the currently visible local-filter subset");

                state(fixture.pane).setSearchText("");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                table.applyCss();
                table.layout();
                TableColumn<ObservableList<Object>, ?> sortedScore = table.getColumns().get(2);
                sortedScore.setSortType(TableColumn.SortType.DESCENDING);
                table.getSortOrder().setAll(sortedScore);
                table.sort();
                table.getSelectionModel().clearAndSelect(0, table.getColumns().get(1));
                assertEquals(1, table.getSelectionModel().getSelectedCells().size());
                copy.getItems().get(2).fire();
                assertEquals("Bob\t9\t2026-08-29 11:12:13",
                        clipboard.get(),
                        "copy must follow the TableView's current sorted row order");
                return null;
            });
        }
    }

    @Test
    void cancelButtonCancelsTheOwnedPreparedStatementAndCleansExecutionState() throws Exception {
        PreparedRunner prepared = new PreparedRunner(QueryResult.cancelled("driver cancelled", 8));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            try {
                QueryResult original = startDatabaseFilter(fixture);
                assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
                assertTrue(fixture.ownedSession.snapshot().running());
                assertTrue(prepared.control.hasActiveStatement());

                FxUiTestSupport.call(() -> {
                    ((Button) field(fixture.pane, "cancelBtn")).fire();
                    return null;
                });
                assertTrue(prepared.cancelled.await(5, TimeUnit.SECONDS),
                        "Statement.cancel was not delivered");
                assertEquals(1, prepared.statementCancelCalls.get());
                assertTrue(prepared.control.cancellationRequested());
                assertTrue(fixture.ownedSession.snapshot().cancelling());

                prepared.release.countDown();
                operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
                FxUiTestSupport.call(() -> null);

                JdbcEditorSession.Snapshot session = fixture.ownedSession.snapshot();
                assertFalse(session.running());
                assertFalse(session.cancelling());
                assertFalse(prepared.control.hasActiveStatement());
                assertNull(((AtomicReference<?>) field(fixture.ownedSession, "activeControl")).get());
                FxUiTestSupport.call(() -> {
                    ResultFilterState.Snapshot filter = state(fixture.pane).snapshot();
                    assertEquals(original.rows, filter.originalResult().rows);
                    assertEquals(original.rows, filter.activeResult().rows);
                    assertEquals("数据库筛选已取消", filter.recoverableError());
                    assertFalse(fixture.pane.getNode().lookup(".sql-result-toolbar").isDisabled());
                    assertTrue(((Button) field(fixture.pane, "cancelBtn")).isDisabled());
                    return null;
                });
            } finally {
                prepared.release.countDown();
            }
        }
    }

    @Test
    void mandatoryCloseSuppressesLatePreparedSuccess() throws Exception {
        assertMandatoryCloseSuppressesLateCompletion(new PreparedRunner(result(false,
                row("Late", 99, "2026-08-29 18:19:20"))));
    }

    @Test
    void mandatoryCloseSuppressesLatePreparedFailure() throws Exception {
        assertMandatoryCloseSuppressesLateCompletion(
                new PreparedRunner(null, new IllegalStateException("late prepared failure")));
    }

    @Test
    void newResultResetsFiltersAndTruncationStatusUsesTheActualFlag() throws Exception {
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            fixture.settings.setMaxResultRows(2);
            FxUiTestSupport.call(() -> {
                QueryResult first = result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13"));
                showScriptResults(fixture.pane, first, "select first");
                ResultFilterState state = state(fixture.pane);
                state.setSearchText("Ada");
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 1)));

                QueryResult cappedButComplete = result(false,
                        row("Cara", 5, "2026-08-29 12:13:14"),
                        row("Dan", 6, "2026-08-29 13:14:15"));
                showScriptResults(fixture.pane, cappedButComplete, "select complete");
                assertEquals("", state.snapshot().searchText());
                assertTrue(state.snapshot().conditions().isEmpty());
                assertFalse(labelText(fixture.pane, "statusLabel").contains("截断"));

                QueryResult truncated = result(true,
                        row("Eve", 5, "2026-08-29 14:15:16"),
                        row("Fox", 6, "2026-08-29 15:16:17"));
                showScriptResults(fixture.pane, truncated, "select truncated");
                assertTrue(labelText(fixture.pane, "statusLabel")
                        .contains("2+，当前结果已截断"));

                fixture.settings.setMaxResultRows(999);
                invoke(fixture.pane, "onClearResultFilters");
                assertTrue(labelText(fixture.pane, "statusLabel")
                                .contains("2+，当前结果已截断"),
                        "historical retained-row status must come from the result snapshot");
                assertFalse(labelText(fixture.pane, "statusLabel").contains("999+"));
                return null;
            });
        }
    }

    @Test
    void appliedTruncatedResultStatusUsesItsRetainedRowsAfterSettingsChange() throws Exception {
        PreparedRunner prepared = new PreparedRunner(result(true,
                row("Bob", 9, "2026-08-29 11:12:13")));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            fixture.settings.setMaxResultRows(2);
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13")), SAFE_POSTGRES_REQUERY_SQL);
                state(fixture.pane).setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                invoke(fixture.pane, "renderResultFilterSnapshot");
                invoke(fixture.pane, "onApplyDatabaseFilter");
                return null;
            });
            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            fixture.settings.setMaxResultRows(999);
            prepared.release.countDown();
            operations(fixture.pane).idle().toCompletableFuture().get(5, TimeUnit.SECONDS);
            FxUiTestSupport.call(() -> {
                assertTrue(labelText(fixture.pane, "statusLabel")
                                .contains("1+，当前结果已截断"));
                assertFalse(labelText(fixture.pane, "statusLabel").contains("999+"));
                return null;
            });
        } finally {
            prepared.release.countDown();
        }
    }

    @Test
    void postgresJsonResultSetEntersPaneAsStableText() throws Exception {
        PGobject driverJson = new PGobject();
        driverJson.setType("jsonb");
        driverJson.setValue("{\"name\":\"Ada\"}");
        QueryResult jsonResult = QueryResult.fromResultSet(
                jsonResultSet(driverJson, driverJson.getValue()), 3, 0);

        try (PaneFixture fixture = new PaneFixture(null, null)) {
            FxUiTestSupport.call(() -> {
                showScriptResults(fixture.pane, jsonResult, "select document from events");
                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(driverJson.getValue(), snapshot.activeResult().rows.getFirst().getFirst());
                assertEquals(driverJson.getValue(),
                        resultTable(fixture.pane).getItems().getFirst().getFirst());
                assertEquals("jsonb", snapshot.activeResult().resultColumns.getFirst().jdbcTypeName());
                return null;
            });
        }
    }

    @Test
    void failedResultInitializationLeavesPreviousResultAndTableIntact() throws Exception {
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));

            FxUiTestSupport.call(() -> {
                showScriptResults(fixture.pane, original, "select original");
                invoke(fixture.pane, "showQueryResult",
                        new Class<?>[]{QueryResult.class, String.class}, null, "select unsupported");

                ResultFilterState.Snapshot snapshot = state(fixture.pane).snapshot();
                assertEquals(original.rows, snapshot.activeResult().rows);
                assertEquals(COLUMNS, snapshot.activeResult().resultColumns);
                assertEquals(original.rows, resultTable(fixture.pane).getItems());
                assertTrue(labelText(fixture.pane, "statusLabel").contains("无法显示新查询结果"));
                assertEquals("select original", field(fixture.pane, "lastQuerySql"));
                return null;
            });
        }
    }

    private PaneFixture databaseFixture(PreparedRunner prepared) throws Exception {
        return databaseFixture(prepared, DbType.POSTGRESQL);
    }

    private void assertTerminalFailurePreservesTable(QueryResult failure, String expectedDetail)
            throws Exception {
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, result(false,
                        row("Ada", 7, "2026-08-29 10:11:12"),
                        row("Bob", 9, "2026-08-29 11:12:13")), SAFE_POSTGRES_REQUERY_SQL);
                ResultFilterState state = state(fixture.pane);
                state.setConditions(List.of(new FilterCondition(
                        1, FilterConnector.AND, FilterOperator.GT, 7)));
                state.setDatabaseUnavailableReason(null);
                invoke(fixture.pane, "renderResultFilterSnapshot",
                        new Class<?>[]{ResultFilterState.Snapshot.class}, state.snapshot());

                TablePresentation before = arrangeAndCaptureTable(fixture.pane);
                ResultFilterState.DatabaseFilterRequest request = state.databaseRequest();
                invoke(fixture.pane, "onDatabaseFilterSucceeded",
                        new Class<?>[]{ResultFilterState.DatabaseFilterRequest.class, QueryResult.class},
                        request, failure);

                assertTablePresentation(before, fixture.pane);
                assertEquals(List.of(1), state.snapshot().visibleRowIndexes());
                assertEquals(expectedDetail, state.snapshot().recoverableError());
                assertTrue(labelText(fixture.pane, "statusLabel").contains(expectedDetail));
                assertFalse(labelText(fixture.pane, "statusLabel").contains("unsafe"));
                return null;
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TablePresentation arrangeAndCaptureTable(SqlEditorPane pane) throws Exception {
        TableView<ObservableList<Object>> table = resultTable(pane);
        TableColumn sequence = table.getColumns().get(0);
        TableColumn name = table.getColumns().get(1);
        TableColumn score = table.getColumns().get(2);
        TableColumn created = table.getColumns().get(3);
        table.getColumns().setAll(sequence, created, name, score);
        created.setPrefWidth(237);
        name.setPrefWidth(191);
        score.setSortType(TableColumn.SortType.DESCENDING);
        table.getSortOrder().setAll(score);
        table.sort();
        table.getSelectionModel().clearAndSelect(0, name);
        table.getFocusModel().focus(0, name);
        return new TablePresentation(table.getItems(), List.copyOf(table.getColumns()),
                List.copyOf(table.getSelectionModel().getSelectedCells()),
                table.getFocusModel().getFocusedCell(), List.copyOf(table.getSortOrder()),
                table.getColumns().stream().map(TableColumn::getWidth).toList());
    }

    private static void assertTablePresentation(TablePresentation before, SqlEditorPane pane)
            throws Exception {
        TableView<ObservableList<Object>> table = resultTable(pane);
        assertSame(before.items(), table.getItems());
        assertEquals(before.columns(), List.copyOf(table.getColumns()));
        assertEquals(before.selection(), List.copyOf(table.getSelectionModel().getSelectedCells()));
        assertEquals(before.focus(), table.getFocusModel().getFocusedCell());
        assertEquals(before.sortOrder(), List.copyOf(table.getSortOrder()));
        assertEquals(before.widths(), table.getColumns().stream()
                .map(TableColumn::getWidth).toList());
    }

    private static void awaitFxDelay(javafx.util.Duration duration) throws Exception {
        CountDownLatch elapsed = new CountDownLatch(1);
        FxUiTestSupport.call(() -> {
            javafx.animation.PauseTransition marker = new javafx.animation.PauseTransition(duration);
            marker.setOnFinished(ignored -> elapsed.countDown());
            marker.play();
            return null;
        });
        assertTrue(elapsed.await(2, TimeUnit.SECONDS), "FX delay marker timed out");
        FxUiTestSupport.call(() -> null);
    }

    private record TablePresentation(
            ObservableList<ObservableList<Object>> items,
            List<TableColumn<ObservableList<Object>, ?>> columns,
            List<?> selection, Object focus,
            List<TableColumn<ObservableList<Object>, ?>> sortOrder,
            List<Double> widths) {
    }

    private PaneFixture databaseFixture(
            PreparedRunner prepared, ResultFilterSqlRenderer renderer) throws Exception {
        DatabaseProvider delegate = new PostgresProvider();
        DatabaseProvider provider = (DatabaseProvider) Proxy.newProxyInstance(
                DatabaseProvider.class.getClassLoader(),
                new Class<?>[]{DatabaseProvider.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("resultFilterSqlRenderer")) {
                        return java.util.Optional.of(renderer);
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        Constructor<ConnectionManager> constructor = ConnectionManager.class
                .getDeclaredConstructor(CredentialCipher.class, Function.class);
        constructor.setAccessible(true);
        Function<DbType, DatabaseProvider> resolver = ignored -> provider;
        ConnectionManager connections = constructor.newInstance(
                new CredentialCipher(), resolver);
        return databaseFixture(prepared, DbType.POSTGRESQL, connections);
    }

    private PaneFixture databaseFixture(PreparedRunner prepared, DbType type) throws Exception {
        return databaseFixture(
                prepared, type, new ConnectionManager(new CredentialCipher()));
    }

    private PaneFixture databaseFixture(
            PreparedRunner prepared, DbType type, ConnectionManager connections) throws Exception {
        String id = type == DbType.ORACLE ? "oracle" : "pg";
        int port = type == DbType.ORACLE ? 1521 : 5432;
        ConnConfig config = new ConnConfig(id, type.name(), type,
                "example.invalid", port, "db", "user", "", Map.of());
        connections.register(config);
        JdbcEditorSession owned = preparedSession(config, prepared);
        PaneFixture fixture = new PaneFixture(connections, owned);
        FxUiTestSupport.call(() -> {
            @SuppressWarnings("unchecked")
            Set<String> prewarmed = (Set<String>) field(fixture.pane, "prewarmed");
            prewarmed.add(config.id());
            fixture.context.setActiveConnection(config);
            invoke(fixture.pane, "admitCurrentConnection");
            setField(fixture.pane, "jdbcSession", owned);
            return null;
        });
        return fixture;
    }

    private QueryResult startDatabaseFilter(PaneFixture fixture) throws Exception {
        QueryResult original = result(false,
                row("Ada", 7, "2026-08-29 10:11:12"),
                row("Bob", 9, "2026-08-29 11:12:13"));
        FxUiTestSupport.call(() -> {
            showQuery(fixture.pane, original, SAFE_POSTGRES_REQUERY_SQL);
            ResultFilterState state = state(fixture.pane);
            state.setConditions(List.of(new FilterCondition(
                    1, FilterConnector.AND, FilterOperator.GT, 7)));
            invoke(fixture.pane, "renderResultFilterSnapshot");
            ((Button) fixture.pane.getNode().lookup("#sql-result-apply-database")).fire();
            return null;
        });
        return original;
    }

    private void assertMandatoryCloseSuppressesLateCompletion(PreparedRunner prepared) throws Exception {
        PaneFixture fixture = databaseFixture(prepared);
        try {
            startDatabaseFilter(fixture);
            assertTrue(prepared.entered.await(5, TimeUnit.SECONDS));
            String statusBeforeClose = FxUiTestSupport.call(
                    () -> labelText(fixture.pane, "statusLabel"));

            var closing = FxUiTestSupport.call(fixture.pane::requestMandatoryClose);
            assertFalse((boolean) field(operations(fixture.pane), "callbacksEnabled"),
                    "accepted mandatory close must suppress callbacks before waiting for JDBC");
            assertTrue(prepared.cancelled.await(5, TimeUnit.SECONDS),
                    "mandatory close did not cancel the owned statement");
            assertEquals(1, prepared.statementCancelCalls.get());
            assertTrue(fixture.ownedSession.snapshot().cancelling());

            prepared.release.countDown();
            assertEquals(CloseGuardOutcome.APPROVED,
                    closing.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                fixture.pane.finalizeCloseOnFx();
                return null;
            });

            JdbcEditorSession.Snapshot session = fixture.ownedSession.snapshot();
            assertFalse(session.running());
            assertFalse(session.cancelling());
            assertEquals(JdbcEditorSession.ConnectionState.CLOSED, session.connectionState());
            assertFalse(prepared.control.hasActiveStatement());
            assertNull(((AtomicReference<?>) field(fixture.ownedSession, "activeControl")).get());
            assertEquals(1, prepared.statementCloseCalls.get());
            assertEquals(1, prepared.connectionCloseCalls.get());
            FxUiTestSupport.call(() -> {
                assertNull(state(fixture.pane).snapshot().activeResult());
                assertTrue(fixture.pane.getNode().lookup(".sql-result-toolbar").isDisabled());
                assertEquals(statusBeforeClose, labelText(fixture.pane, "statusLabel"),
                        "late success/failure must not publish a terminal status after close begins");
                return null;
            });
        } finally {
            prepared.release.countDown();
            fixture.close();
        }
    }

    private JdbcEditorSession preparedSession(ConnConfig config, PreparedRunner runner) throws Exception {
        Constructor<?> constructor = Arrays.stream(JdbcEditorSession.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 4)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        Class<?> openerType = constructor.getParameterTypes()[2];
        Object opener = Proxy.newProxyInstance(openerType.getClassLoader(),
                new Class<?>[]{openerType}, (proxy, method, args) -> connection(runner));
        return (JdbcEditorSession) constructor.newInstance(config.id(),
                ConnectionSafetyOptions.from(config), opener, runner);
    }

    private static Connection connection(PreparedRunner observer) {
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> {
                        if (closed.compareAndSet(false, true)) observer.connectionCloseCalls.incrementAndGet();
                        yield null;
                    }
                    case "isClosed" -> closed.get();
                    case "getAutoCommit" -> true;
                    case "setAutoCommit", "setReadOnly", "commit", "rollback" -> null;
                    case "isWrapperFor" -> false;
                    case "unwrap" -> null;
                    case "toString" -> "ResultFilterConnection";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static ResultSet jsonResultSet(PGobject driverJson, String text) {
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
                ResultSetMetaData.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> 1;
                    case "getColumnLabel" -> "document";
                    case "getColumnType" -> Types.OTHER;
                    case "getColumnTypeName" -> "jsonb";
                    default -> defaultValue(method.getReturnType());
                });
        AtomicBoolean beforeRow = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "next" -> beforeRow.getAndSet(false);
                    case "getCharacterStream" -> new java.io.StringReader(text);
                    case "getString" -> text;
                    case "getObject" -> driverJson;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    @SafeVarargs
    private static QueryResult result(boolean truncated, List<Object>... rows) {
        return QueryResult.queryWithMetadata(COLUMNS, List.of(rows), 37, truncated)
                .withColumnComments(List.of("姓名", "分数", "创建时间"));
    }

    private static List<Object> row(String name, int score, String timestamp) {
        return Arrays.asList(name, score, Timestamp.valueOf(timestamp));
    }

    private static void showQuery(SqlEditorPane pane, QueryResult result, String sql) throws Exception {
        setField(pane, "lastQuerySql", sql);
        invoke(pane, "showQueryResult", new Class<?>[]{QueryResult.class}, result);
    }

    private static void showScriptResults(SqlEditorPane pane, QueryResult result, String sql) throws Exception {
        invoke(pane, "showScriptResults", new Class<?>[]{List.class, long.class},
                List.of(new ScriptOutcome(1, sql, result)), result.elapsedMillis);
    }

    private static void invoke(SqlEditorPane pane, String name) throws Exception {
        invoke(pane, name, new Class<?>[0]);
    }

    private static void invoke(SqlEditorPane pane, String name, Class<?>[] parameterTypes,
            Object... arguments) throws Exception {
        Method method = SqlEditorPane.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(pane, arguments);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(SqlEditorPane pane, String name, Object value) throws Exception {
        Field field = SqlEditorPane.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pane, value);
    }

    private static ResultFilterState state(SqlEditorPane pane) throws Exception {
        return (ResultFilterState) field(pane, "resultFilterState");
    }

    private static SerialSessionOperationQueue operations(SqlEditorPane pane) throws Exception {
        return (SerialSessionOperationQueue) field(pane, "sessionOperations");
    }

    @SuppressWarnings("unchecked")
    private static TableView<ObservableList<Object>> resultTable(SqlEditorPane pane) throws Exception {
        return (TableView<ObservableList<Object>>) field(pane, "resultTable");
    }

    private static String labelText(SqlEditorPane pane, String name) throws Exception {
        return ((javafx.scene.control.Label) field(pane, name)).getText();
    }

    private final class PaneFixture implements AutoCloseable {
        final FxTaskRunner runner = new FxTaskRunner();
        final SessionContext context = new SessionContext();
        final AppSettings settings = new AppSettings(directory.resolve("settings.properties"));
        final JdbcEditorSession ownedSession;
        final SqlEditorPane pane;

        PaneFixture(ConnectionManager connections, JdbcEditorSession ownedSession) throws Exception {
            this.ownedSession = ownedSession;
            try {
                pane = FxUiTestSupport.call(() -> {
                    SqlEditorPane created = new SqlEditorPane(context, connections, null, settings,
                            (id, table) -> {}, null, null,
                            new SqlHistoryStore(directory.resolve("history.txt")),
                            new ShortcutSettings(directory.resolve("shortcuts.properties")), runner);
                    new Scene((Parent) created.getNode(), 1200, 800);
                    created.getNode().applyCss();
                    return created;
                });
            } catch (Throwable failure) {
                runner.close();
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            try {
                CompletionStageAssertions.close(pane);
            } finally {
                runner.close();
            }
        }
    }

    private static final class CompletionStageAssertions {
        private CompletionStageAssertions() {
        }

        static void close(SqlEditorPane pane) throws Exception {
            var closing = FxUiTestSupport.call(pane::requestMandatoryClose);
            assertEquals(CloseGuardOutcome.APPROVED,
                    closing.toCompletableFuture().get(5, TimeUnit.SECONDS));
            FxUiTestSupport.call(() -> {
                pane.finalizeCloseOnFx();
                return null;
            });
        }
    }

    private static final class PreparedRunner implements SqlRunner {
        final QueryResult result;
        final RuntimeException failure;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch scriptEntered = new CountDownLatch(1);
        final CountDownLatch scriptRelease = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger preparedCalls = new AtomicInteger();
        final AtomicInteger statementCancelCalls = new AtomicInteger();
        final AtomicInteger statementCloseCalls = new AtomicInteger();
        final AtomicInteger connectionCloseCalls = new AtomicInteger();
        volatile SqlExecutionControl control;
        volatile String lastSql;
        volatile String lastSchema;
        volatile String lastScriptSchema;
        volatile List<SqlParameter> lastParameters = List.of();
        volatile boolean blockScript;

        PreparedRunner(QueryResult result) {
            this(result, null);
        }

        PreparedRunner(QueryResult result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public QueryResult executePrepared(Connection connection, String sql,
                List<SqlParameter> parameters, String schema, SqlExecutionOptions options) {
            preparedCalls.incrementAndGet();
            lastSql = sql;
            lastSchema = schema;
            lastParameters = List.copyOf(parameters);
            control = options.control();
            Statement statement = (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("cancel")) {
                            statementCancelCalls.incrementAndGet();
                            cancelled.countDown();
                            return null;
                        }
                        if (method.getName().equals("close")) {
                            statementCloseCalls.incrementAndGet();
                            return null;
                        }
                        return defaultValue(method.getReturnType());
                    });
            SqlExecutionControl.Activation activation;
            try {
                activation = control.activate(statement, options.queryTimeoutSeconds());
            } catch (java.sql.SQLException activationFailure) {
                throw new AssertionError(activationFailure);
            }
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    return QueryResult.error("test release timed out", 0);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return QueryResult.cancelled("interrupted", 0);
            } finally {
                control.release(activation);
                try {
                    statement.close();
                } catch (java.sql.SQLException closeFailure) {
                    throw new AssertionError(closeFailure);
                }
            }
            if (failure != null) throw failure;
            return result;
        }

        @Override
        public QueryResult execute(Connection connection, String sql,
                String schema, SqlExecutionOptions options) {
            return QueryResult.error("unexpected execute", 0);
        }

        @Override
        public List<ScriptOutcome> executeScript(Connection connection, String script,
                String schema, SqlExecutionOptions options, ScriptErrorPolicy policy) {
            lastScriptSchema = schema;
            if (blockScript) {
                scriptEntered.countDown();
                try {
                    if (!scriptRelease.await(5, TimeUnit.SECONDS)) {
                        return List.of(new ScriptOutcome(1, script,
                                QueryResult.error("test script release timed out", 0)));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return List.of(new ScriptOutcome(1, script,
                            QueryResult.cancelled("interrupted", 0)));
                }
            }
            return List.of(new ScriptOutcome(1, script, result));
        }

        @Override
        public QueryResult explain(Connection connection, String sql, String schema,
                boolean analyze, SqlExecutionOptions options) {
            return QueryResult.error("unexpected explain", 0);
        }
    }
}
