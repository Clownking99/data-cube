package com.datacube.fx;

import com.datacube.config.AppSettings;
import com.datacube.config.CredentialCipher;
import com.datacube.config.ShortcutSettings;
import com.datacube.config.SqlHistoryStore;
import com.datacube.fx.task.FxTaskRunner;
import com.datacube.fx.task.SerialSessionOperationQueue;
import com.datacube.service.ConnectionManager;
import com.datacube.service.JdbcEditorSession;
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
import javafx.event.ActionEvent;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlEditorResultFilterContractTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
            new ResultColumn(1, "SCORE", Types.INTEGER, "INTEGER"),
            new ResultColumn(2, "CREATED_AT", Types.TIMESTAMP, "TIMESTAMP"));

    @TempDir Path directory;

    @Test
    void databaseFilterUsesOwnedSessionAndPreservesResultOnFailure() throws Exception {
        String source = Files.readString(Path.of("src/com/datacube/fx/SqlEditorPane.java"));
        assertTrue(source.contains("SafeSelectEligibility.check"));
        assertTrue(source.contains("resultFilterSqlRenderer"));
        assertTrue(source.contains("executePrepared"));
        assertTrue(source.contains("databaseFailed"));
        assertFalse(source.contains("DriverManager.getConnection"));

        PreparedRunner prepared = new PreparedRunner(QueryResult.error("database rejected filter", 12));
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, "select name, score, created_at from people");
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
                assertTrue(snapshot.recoverableError().contains("database rejected filter"));
                assertEquals(1, resultTable(fixture.pane).getItems().size(),
                        "failure keeps the prior local preview visible");
                assertFalse(operations.snapshot().pending());
                return null;
            });
            assertEquals(1, prepared.preparedCalls.get());
            assertTrue(prepared.lastSql.contains("FROM (select name, score, created_at from people)"));
            assertEquals(1, prepared.lastParameters.size());
            assertSame(fixture.ownedSession, field(fixture.pane, "jdbcSession"));
        } finally {
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
                showQuery(fixture.pane, original, "select name, score, created_at from people");
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
                showQuery(fixture.pane, original, "select name, score, created_at from people");
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
                showQuery(fixture.pane, original, "select name, score, created_at from people");
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
                showQuery(fixture.pane, original, "select name, score, created_at from people");
                Parent resultToolbar = (Parent) fixture.pane.getNode().lookup(".sql-result-toolbar");
                assertNotNull(resultToolbar);
                assertNotNull(resultToolbar.getParent(), "toolbar must be embedded in the result container");

                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                assertInstanceOf(Timestamp.class, table.getItems().getFirst().get(2),
                        "table rows must retain raw JDBC values");
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
        try (PaneFixture fixture = databaseFixture(prepared)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, "select name, score, created_at from people");
                TableView<ObservableList<Object>> table = resultTable(fixture.pane);
                TableColumn<ObservableList<Object>, ?> name = table.getColumns().get(1);
                TableColumn<ObservableList<Object>, ?> score = table.getColumns().get(2);
                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, score);

                MenuButton copy = (MenuButton) fixture.pane.getNode().lookup("#sql-result-copy");
                copy.getItems().get(1).fire();
                assertEquals("Ada\t\n\t9", Clipboard.getSystemClipboard().getString());
                assertEquals("已复制 2 个单元格", labelText(fixture.pane, "statusLabel"));

                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, score);
                copy.getItems().get(3).fire();
                assertEquals("NAME\tSCORE\tCREATED_AT\n"
                                + "Ada\t7\t2026-08-29 10:11:12\n"
                                + "Bob\t9\t2026-08-29 11:12:13",
                        Clipboard.getSystemClipboard().getString());
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
        try (PaneFixture fixture = new PaneFixture(null, null)) {
            QueryResult original = result(false,
                    row("Ada", 7, "2026-08-29 10:11:12"),
                    new ArrayList<Object>(List.of("Ragged")),
                    row("Bob", 9, "2026-08-29 11:12:13"));
            FxUiTestSupport.call(() -> {
                showQuery(fixture.pane, original, "select name, score, created_at from people");
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
                assertEquals("2026-08-29 10:11:12", Clipboard.getSystemClipboard().getString());

                table.getSelectionModel().clearAndSelect(0, created);
                table.getSelectionModel().select(1, name);
                copy.getItems().get(1).fire();
                assertEquals("2026-08-29 10:11:12\t\n\tRagged",
                        Clipboard.getSystemClipboard().getString());

                copy.getItems().get(2).fire();
                assertEquals("2026-08-29 10:11:12\tAda\t7\n\tRagged\t",
                        Clipboard.getSystemClipboard().getString());

                copy.getItems().get(3).fire();
                assertEquals("CREATED_AT\tNAME\tSCORE\n"
                                + "2026-08-29 10:11:12\tAda\t7\n\tRagged\t",
                        Clipboard.getSystemClipboard().getString());

                table.getSelectionModel().clearAndSelect(0, name);
                table.getSelectionModel().select(1, name);
                KeyEvent shortcut = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                        false, true, false, false);
                table.fireEvent(shortcut);
                assertEquals("Ada\nRagged", Clipboard.getSystemClipboard().getString());

                ClipboardContent sentinel = new ClipboardContent();
                sentinel.putString("keep clipboard");
                Clipboard.getSystemClipboard().setContent(sentinel);
                table.getSelectionModel().clearSelection();
                table.getFocusModel().focus(-1);
                KeyEvent emptyShortcut = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.C,
                        false, true, false, false);
                table.fireEvent(emptyShortcut);
                assertEquals("keep clipboard", Clipboard.getSystemClipboard().getString());

                state(fixture.pane).setSearchText("Ada");
                invoke(fixture.pane, "renderResultFilterSnapshot");
                table.applyCss();
                table.layout();
                TableColumn<ObservableList<Object>, ?> filteredName = table.getColumns().get(1);
                table.getSelectionModel().clearAndSelect(0, filteredName);
                assertEquals(1, table.getSelectionModel().getSelectedCells().size());
                copy.getItems().get(2).fire();
                assertEquals("Ada\t7\t2026-08-29 10:11:12",
                        Clipboard.getSystemClipboard().getString(),
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
                        Clipboard.getSystemClipboard().getString(),
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
                    assertTrue(filter.recoverableError().contains("driver cancelled"));
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
                return null;
            });
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
            QueryResult unsupported = QueryResult.queryWithMetadata(
                    List.of(new ResultColumn(0, "driver_value", Types.OTHER, "vendor_value")),
                    List.of(List.of(new Object())), 4, false);

            FxUiTestSupport.call(() -> {
                showScriptResults(fixture.pane, original, "select original");
                showScriptResults(fixture.pane, unsupported, "select unsupported");

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
        ConnectionManager connections = new ConnectionManager(new CredentialCipher());
        ConnConfig config = new ConnConfig("pg", "Postgres", DbType.POSTGRESQL,
                "example.invalid", 5432, "db", "user", "", Map.of());
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
            showQuery(fixture.pane, original, "select name, score, created_at from people");
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
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicInteger preparedCalls = new AtomicInteger();
        final AtomicInteger statementCancelCalls = new AtomicInteger();
        final AtomicInteger statementCloseCalls = new AtomicInteger();
        final AtomicInteger connectionCloseCalls = new AtomicInteger();
        volatile SqlExecutionControl control;
        volatile String lastSql;
        volatile List<SqlParameter> lastParameters = List.of();

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
            return List.of();
        }

        @Override
        public QueryResult explain(Connection connection, String sql, String schema,
                boolean analyze, SqlExecutionOptions options) {
            return QueryResult.error("unexpected explain", 0);
        }
    }
}
