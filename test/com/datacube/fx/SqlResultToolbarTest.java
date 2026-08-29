package com.datacube.fx;

import com.datacube.spi.model.QueryResult;
import com.datacube.spi.model.ResultColumn;
import com.datacube.sqleditor.result.FilterCondition;
import com.datacube.sqleditor.result.FilterConnector;
import com.datacube.sqleditor.result.FilterOperator;
import com.datacube.sqleditor.result.ResultFilterState;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlResultToolbarTest {
    private static final List<ResultColumn> COLUMNS = List.of(
            new ResultColumn(0, "NAME", Types.VARCHAR, "VARCHAR"),
            new ResultColumn(1, "SCORE", Types.INTEGER, "INTEGER"),
            new ResultColumn(2, "CREATED_AT", Types.TIMESTAMP, "TIMESTAMP"),
            new ResultColumn(3, "ACTIVE", Types.BOOLEAN, "BOOLEAN"),
            new ResultColumn(4, "NOTE", Types.VARCHAR, "VARCHAR"),
            new ResultColumn(5, "ID", Types.BIGINT, "BIGINT"));

    @Test
    void compactToolbarReflectsLocalPreviewAndDoesNotApplyDatabaseImplicitly() throws Exception {
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger databaseRequests = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(searchRequests, databaseRequests);
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    "ada", List.of(condition(0, FilterConnector.AND, FilterOperator.CONTAINS, "Ada")),
                    12, null, null));

            Parent root = toolbar.getNode();
            assertEquals("本地预览：显示 12 / 186 行 · 6 列 · 37 ms",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            assertEquals("ada", ((TextField) root.lookup("#sql-result-search")).getText());
            assertEquals(0, searchRequests.get(), "render must not replay search callbacks");
            assertEquals(0, databaseRequests.get());
            assertFalse(root.lookup("#sql-result-apply-database").isDisabled());
            assertNull(((Button) root.lookup("#sql-result-apply-database")).getTooltip());
            return null;
        });
    }

    @Test
    void stateMappingKeepsLabelsAndDisabledReasonsInSync() throws Exception {
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
            Parent root = toolbar.getNode();

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            Button apply = (Button) root.lookup("#sql-result-apply-database");
            assertTrue(apply.isDisabled());
            assertEquals("请先添加筛选条件", apply.getTooltip().getText());
            assertEquals("应用数据库筛选", apply.getAccessibleText());
            assertEquals("请先添加筛选条件", apply.getAccessibleHelp());
            assertEquals("原始结果：186 行 · 6 列 · 37 ms",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            assertTrue(root.lookup("#sql-result-clear-filter").isDisabled());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    "", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    12, "当前 SQL 不能安全包装", null));
            assertTrue(apply.isDisabled());
            assertEquals("当前 SQL 不能安全包装", apply.getTooltip().getText());
            assertEquals("当前 SQL 不能安全包装", apply.getAccessibleHelp());
            assertFalse(root.lookup("#sql-result-clear-filter").isDisabled());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.APPLIED,
                    "", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    72, null, null));
            assertEquals("重新应用", apply.getText());
            assertEquals("重新应用数据库筛选", apply.getAccessibleText());
            assertNull(apply.getAccessibleHelp());
            assertEquals("数据库筛选已应用：显示 72 / 186 行 · 6 列 · 37 ms",
                    ((Label) root.lookup("#sql-result-summary")).getText());

            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.DIRTY_AFTER_APPLY,
                    "Ada", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    4, null, "数据库筛选失败，仍显示当前结果"));
            assertEquals("重新应用", apply.getText());
            assertEquals("本地预览 / 有未应用更改：显示 4 / 186 行 · 6 列 · 37 ms · 数据库筛选失败，仍显示当前结果",
                    ((Label) root.lookup("#sql-result-summary")).getText());
            return null;
        });
    }

    @Test
    void noQueryDisablesEveryResultActionAndExposesTheReasonAccessibly() throws Exception {
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
            toolbar.render(new ResultFilterState.Snapshot(
                    null, null, null, null, "", List.of(), List.of(),
                    ResultFilterState.DatabaseStatus.ORIGINAL, null, null));
            Parent root = toolbar.getNode();

            for (String id : List.of("sql-result-search", "sql-result-add-filter",
                    "sql-result-apply-database", "sql-result-copy", "sql-result-clear-filter")) {
                Node control = root.lookup("#" + id);
                assertNotNull(control, id);
                assertTrue(control.isDisabled(), id);
                assertNotNull(control.getAccessibleText(), id);
                assertFalse(control.getAccessibleText().isBlank(), id);
            }
            Button apply = (Button) root.lookup("#sql-result-apply-database");
            assertEquals("当前没有查询结果", apply.getTooltip().getText());
            assertEquals("当前没有查询结果", apply.getAccessibleHelp());
            Label summary = (Label) root.lookup("#sql-result-summary");
            assertEquals("暂无查询结果", summary.getText());
            assertEquals("查询结果摘要：暂无查询结果", summary.getAccessibleText());
            return null;
        });
    }

    @Test
    void zeroRowSummaryIncludesColumnsAndElapsedTime() throws Exception {
        FxUiTestSupport.call(() -> {
            QueryResult empty = result(0, 0, false);
            SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
            toolbar.render(snapshot(empty, ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), List.of(), null, null));

            Label summary = (Label) toolbar.getNode().lookup("#sql-result-summary");
            assertEquals("原始结果：0 行 · 6 列 · 0 ms", summary.getText());
            assertEquals("查询结果摘要：原始结果：0 行 · 6 列 · 0 ms",
                    summary.getAccessibleText());
            return null;
        });
    }

    @Test
    void truncatedSummaryUsesLocaleStableThousandsAndMirrorsAccessibleText() throws Exception {
        Locale original = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
        try {
            FxUiTestSupport.call(() -> {
                QueryResult truncated = result(10_000, 1_234, true);
                SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
                toolbar.render(snapshot(truncated, ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                        "ada", List.of(condition(0, FilterConnector.AND,
                                FilterOperator.CONTAINS, "Ada")), indexes(12), null, null));

                Label summary = (Label) toolbar.getNode().lookup("#sql-result-summary");
                String expected = "本地预览：显示 12 / 10,000+ 行 · 6 列 · "
                        + "1,234 ms（当前结果已截断）";
                assertEquals(expected, summary.getText());
                assertEquals("查询结果摘要：" + expected, summary.getAccessibleText());
                return null;
            });
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, original);
        }
    }

    @Test
    void buttonsMenusAndConditionChipsDispatchOnlyTheirExplicitCallbacks() throws Exception {
        AtomicInteger add = new AtomicInteger();
        AtomicInteger remove = new AtomicInteger(-1);
        AtomicInteger apply = new AtomicInteger();
        AtomicInteger clear = new AtomicInteger();
        AtomicReference<SqlResultToolbar.CopyMode> copy = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = new SqlResultToolbar(new SqlResultToolbar.Actions(
                    ignored -> {}, add::incrementAndGet, remove::set,
                    apply::incrementAndGet, clear::incrementAndGet, copy::set));
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, "",
                    List.of(condition(0, FilterConnector.AND, FilterOperator.EQ, "Ada"),
                            condition(1, FilterConnector.OR, FilterOperator.GTE, 90)),
                    7, null, null));
            Parent root = toolbar.getNode();

            ((Button) root.lookup("#sql-result-add-filter")).fire();
            ((Button) root.lookup("#sql-result-filter-remove-1")).fire();
            ((Button) root.lookup("#sql-result-apply-database")).fire();
            ((Button) root.lookup("#sql-result-clear-filter")).fire();
            assertEquals(1, add.get());
            assertEquals(1, remove.get());
            assertEquals(1, apply.get());
            assertEquals(1, clear.get());

            MenuButton menu = (MenuButton) root.lookup("#sql-result-copy");
            assertEquals(List.of("当前单元格", "选中区域", "选中行", "选中行（含表头）"),
                    menu.getItems().stream().map(item -> item.getText()).toList());
            for (int index = 0; index < menu.getItems().size(); index++) {
                menu.getItems().get(index).fire();
                assertEquals(SqlResultToolbar.CopyMode.values()[index], copy.get());
            }

            Button chip = (Button) root.lookup("#sql-result-filter-remove-1");
            assertTrue(chip.getText().contains("OR"));
            assertTrue(chip.getText().contains("SCORE"));
            assertTrue(chip.getAccessibleText().contains("删除筛选条件"));
            assertEquals("搜索当前结果", root.lookup("#sql-result-search").getAccessibleText());
            assertEquals("添加筛选条件", root.lookup("#sql-result-add-filter").getAccessibleText());
            assertEquals("应用数据库筛选", root.lookup("#sql-result-apply-database").getAccessibleText());
            assertEquals("复制查询结果", root.lookup("#sql-result-copy").getAccessibleText());
            assertEquals("清除结果筛选", root.lookup("#sql-result-clear-filter").getAccessibleText());
            assertEquals(1, apply.get(), "non-database actions must never apply a database filter");
            return null;
        });
    }

    @Test
    void conditionChipAndAccessibilityUseANeutralValueMarker() throws Exception {
        String sentinel = "sentinel-toolbar-secret-92bd";
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(new AtomicInteger(), new AtomicInteger());
            ResultFilterState.Snapshot snapshot = snapshot(
                    ResultFilterState.DatabaseStatus.LOCAL_PREVIEW, "",
                    List.of(condition(0, FilterConnector.AND, FilterOperator.EQ, sentinel)),
                    7, null, null);

            toolbar.render(snapshot);

            Button chip = (Button) toolbar.getNode().lookup("#sql-result-filter-remove-0");
            Label summary = (Label) toolbar.getNode().lookup("#sql-result-summary");
            assertFalse(String.valueOf(snapshot.conditions().getFirst().value()).contains(sentinel));
            assertFalse(chip.getText().contains(sentinel));
            assertFalse(chip.getAccessibleText().contains(sentinel));
            assertFalse(summary.getText().contains(sentinel));
            assertFalse(summary.getAccessibleText().contains(sentinel));
            assertTrue(chip.getText().contains("<redacted>"));
            assertTrue(chip.getAccessibleText().contains("<redacted>"));
            return null;
        });
    }

    @Test
    void searchUsesAResettableLocalDebounceWithoutDatabaseExecution() throws Exception {
        AtomicReference<String> search = new AtomicReference<>();
        AtomicInteger searchCount = new AtomicInteger();
        AtomicInteger database = new AtomicInteger();
        CountDownLatch searched = new CountDownLatch(1);
        AtomicReference<SqlResultToolbar> toolbar = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar created = new SqlResultToolbar(new SqlResultToolbar.Actions(
                    text -> {
                        search.set(text);
                        searchCount.incrementAndGet();
                        searched.countDown();
                    }, () -> {}, ignored -> {}, database::incrementAndGet,
                    () -> {}, ignored -> {}));
            created.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            toolbar.set(created);
            return null;
        });
        FxUiTestSupport.call(() -> {
            TextField field = (TextField) toolbar.get().getNode().lookup("#sql-result-search");
            field.setText("a");
            field.setText("ada");
            assertNull(search.get(), "search must not run synchronously for every keystroke");
            assertEquals(0, database.get());
            return null;
        });
        assertTrue(searched.await(2, TimeUnit.SECONDS), "debounced search timed out");
        FxUiTestSupport.call(() -> null); // drain every FX event queued before this barrier
        assertEquals("ada", search.get());
        assertEquals(1, searchCount.get());
        assertEquals(0, database.get());
    }

    @Test
    void applyCommitsPendingSearchExactlyOnceBeforeDispatchAndCancelsTheDebounce() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicReference<SqlResultToolbar> toolbar = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar created = new SqlResultToolbar(new SqlResultToolbar.Actions(
                    text -> events.add("search:" + text), () -> {}, ignored -> {},
                    () -> events.add("apply"), () -> {}, ignored -> {}));
            created.render(snapshot(ResultFilterState.DatabaseStatus.LOCAL_PREVIEW,
                    "old", List.of(condition(1, FilterConnector.AND, FilterOperator.GT, 60)),
                    12, null, null));
            toolbar.set(created);

            TextField field = (TextField) created.getNode().lookup("#sql-result-search");
            field.setText("current");
            ((Button) created.getNode().lookup("#sql-result-apply-database")).fire();
            assertEquals(List.of("search:current", "apply"), events,
                    "Apply must commit the exact visible search before creating a request");
            return null;
        });

        awaitFxDelay(Duration.millis(300));
        assertEquals(List.of("search:current", "apply"), events,
                "the stopped debounce must not replay a stale search callback");
    }

    @Test
    void renderImmediatelyCancelsPendingSearchAndRepeatedRenderStaysSilent() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger database = new AtomicInteger();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(searches, database);
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            TextField field = (TextField) toolbar.getNode().lookup("#sql-result-search");
            field.setText("stale input");
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            assertEquals(0, searches.get());
            return null;
        });

        awaitFxDelay(Duration.millis(300));
        assertEquals(0, searches.get());
        assertEquals(0, database.get());
    }

    @Test
    void removingToolbarFromSceneCancelsPendingSearchWithoutGhostCallback() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger database = new AtomicInteger();
        AtomicReference<Scene> retainedScene = new AtomicReference<>();
        FxUiTestSupport.call(() -> {
            SqlResultToolbar toolbar = toolbar(searches, database);
            toolbar.render(snapshot(ResultFilterState.DatabaseStatus.ORIGINAL,
                    "", List.of(), 186, null, null));
            Group host = new Group(toolbar.getNode());
            retainedScene.set(new Scene(host));
            TextField field = (TextField) toolbar.getNode().lookup("#sql-result-search");
            field.setText("must be cancelled");
            host.getChildren().clear();
            return null;
        });

        awaitFxDelay(Duration.millis(300));
        assertNotNull(retainedScene.get());
        assertEquals(0, searches.get());
        assertEquals(0, database.get());
    }

    @Test
    void conditionDialogRestrictsOperatorsValidatesValuesAndForcesFirstConnectorToAnd() throws Exception {
        FxUiTestSupport.call(() -> {
            AtomicReference<List<FilterOperator>> allowed = new AtomicReference<>();
            AtomicReference<String> invalidMessage = new AtomicReference<>();
            AtomicReference<Boolean> invalidDisabled = new AtomicReference<>();
            AtomicReference<Boolean> nullValueDisabled = new AtomicReference<>();
            AtomicReference<String> invalidAccessibleText = new AtomicReference<>();
            AtomicReference<String> clearedAccessibleText = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelPresent = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelVisible = new AtomicReference<>();
            AtomicReference<Boolean> connectorLabelManaged = new AtomicReference<>();
            AtomicReference<Throwable> interactionFailure = new AtomicReference<>();

            Platform.runLater(() -> {
                try {
                    DialogPane pane = showingDialogPane();
                    @SuppressWarnings("unchecked")
                    ComboBox<ResultColumn> column = (ComboBox<ResultColumn>) pane.lookup("#filter-condition-column");
                    @SuppressWarnings("unchecked")
                    ComboBox<FilterOperator> operator = (ComboBox<FilterOperator>) pane.lookup("#filter-condition-operator");
                    TextField value = (TextField) pane.lookup("#filter-condition-value");
                    Label error = (Label) pane.lookup("#filter-condition-error");
                    assertTrue(error.getStyle().contains("-status-error"));
                    Node connectorLabel = pane.lookup("#filter-condition-connector-label");
                    ButtonBase ok = (ButtonBase) pane.lookupButton(pane.getButtonTypes().get(0));

                    connectorLabelPresent.set(connectorLabel != null);
                    connectorLabelVisible.set(connectorLabel != null && connectorLabel.isVisible());
                    connectorLabelManaged.set(connectorLabel != null && connectorLabel.isManaged());

                    column.getSelectionModel().select(1);
                    allowed.set(List.copyOf(operator.getItems()));
                    value.setText("not-a-number");
                    invalidMessage.set(error.getText());
                    invalidAccessibleText.set(error.getAccessibleText());
                    invalidDisabled.set(ok.isDisabled());
                    operator.setValue(FilterOperator.IS_NULL);
                    nullValueDisabled.set(value.isDisabled());
                    clearedAccessibleText.set(error.getAccessibleText());
                    ok.fire();
                } catch (Throwable failure) {
                    interactionFailure.set(failure);
                    closeShowingDialog();
                }
            });

            Optional<FilterCondition> result = FilterConditionDialog.show(
                    null, COLUMNS, 0, FilterConnector.OR);
            if (interactionFailure.get() != null) throw new AssertionError(interactionFailure.get());
            assertEquals(FilterOperator.allowedFor(COLUMNS.get(1)), allowed.get());
            assertTrue(invalidMessage.get().matches(".*[\\u4e00-\\u9fff].*"));
            assertEquals(invalidMessage.get(), invalidAccessibleText.get());
            assertTrue(invalidDisabled.get());
            assertTrue(nullValueDisabled.get());
            assertEquals("", clearedAccessibleText.get());
            assertTrue(connectorLabelPresent.get());
            assertFalse(connectorLabelVisible.get());
            assertFalse(connectorLabelManaged.get());
            assertTrue(result.isPresent());
            assertEquals(new FilterCondition(1, FilterConnector.AND,
                    FilterOperator.IS_NULL, null), result.get());
            return null;
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operatorCases")
    void conditionDialogUsesTypeSpecificOperators(
            String caseName, ResultColumn selectedColumn,
            List<FilterOperator> expectedOperators) throws Exception {
        AtomicReference<List<FilterOperator>> actual = new AtomicReference<>();
        Optional<FilterCondition> result = showConditionDialog(
                List.of(selectedColumn), 0, FilterConnector.AND, pane -> {
                    @SuppressWarnings("unchecked")
                    ComboBox<FilterOperator> operator =
                            (ComboBox<FilterOperator>) pane.lookup("#filter-condition-operator");
                    actual.set(List.copyOf(operator.getItems()));
                    cancelButton(pane).fire();
                });

        assertEquals(expectedOperators, actual.get(), caseName);
        assertTrue(result.isEmpty(), caseName + " cancel result");
    }

    @Test
    void laterConditionPreservesSelectedOrConnector() throws Exception {
        AtomicReference<Boolean> connectorVisible = new AtomicReference<>();
        AtomicReference<Boolean> connectorManaged = new AtomicReference<>();
        Optional<FilterCondition> result = showConditionDialog(
                COLUMNS, 1, FilterConnector.AND, pane -> {
                    @SuppressWarnings("unchecked")
                    ComboBox<FilterConnector> connector =
                            (ComboBox<FilterConnector>) pane.lookup("#filter-condition-connector");
                    TextField value = (TextField) pane.lookup("#filter-condition-value");
                    connectorVisible.set(connector.isVisible());
                    connectorManaged.set(connector.isManaged());
                    connector.setValue(FilterConnector.OR);
                    value.setText("Ada");
                    okButton(pane).fire();
                });

        assertTrue(connectorVisible.get());
        assertTrue(connectorManaged.get());
        assertEquals(Optional.of(new FilterCondition(
                0, FilterConnector.OR, FilterOperator.EQ, "Ada")), result);
    }

    @Test
    void cancelButtonReturnsEmptyCondition() throws Exception {
        Optional<FilterCondition> result = showConditionDialog(
                COLUMNS, 0, FilterConnector.AND,
                pane -> cancelButton(pane).fire());

        assertTrue(result.isEmpty());
    }

    @Test
    void windowCloseReturnsEmptyCondition() throws Exception {
        Optional<FilterCondition> result = showConditionDialog(
                COLUMNS, 0, FilterConnector.AND, pane -> {
                    Window window = pane.getScene().getWindow();
                    window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
                });

        assertTrue(result.isEmpty());
    }

    private static Stream<Arguments> operatorCases() {
        List<FilterOperator> text = List.of(
                FilterOperator.EQ, FilterOperator.NE, FilterOperator.CONTAINS,
                FilterOperator.STARTS_WITH, FilterOperator.ENDS_WITH,
                FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        List<FilterOperator> comparable = List.of(
                FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT,
                FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE,
                FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        List<FilterOperator> booleanOperators = List.of(
                FilterOperator.EQ, FilterOperator.NE,
                FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        return Stream.of(
                Arguments.of("文本", new ResultColumn(0, "TEXT", Types.VARCHAR, "VARCHAR"), text),
                Arguments.of("数字", new ResultColumn(0, "NUMBER", Types.DECIMAL, "DECIMAL"), comparable),
                Arguments.of("日期", new ResultColumn(0, "DATE", Types.DATE, "DATE"), comparable),
                Arguments.of("时间", new ResultColumn(0, "TIME", Types.TIME, "TIME"), comparable),
                Arguments.of("时间戳", new ResultColumn(
                        0, "TIMESTAMP", Types.TIMESTAMP, "TIMESTAMP"), comparable),
                Arguments.of("布尔", new ResultColumn(
                        0, "BOOLEAN", Types.BOOLEAN, "BOOLEAN"), booleanOperators));
    }

    private static Optional<FilterCondition> showConditionDialog(
            List<ResultColumn> columns, int conditionIndex,
            FilterConnector connector, Consumer<DialogPane> interaction) throws Exception {
        return FxUiTestSupport.call(() -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Platform.runLater(() -> {
                try {
                    interaction.accept(showingDialogPane());
                } catch (Throwable invalidInteraction) {
                    failure.set(invalidInteraction);
                    closeShowingDialog();
                }
            });
            Optional<FilterCondition> result = FilterConditionDialog.show(
                    null, columns, conditionIndex, connector);
            if (failure.get() != null) throw new AssertionError(failure.get());
            return result;
        });
    }

    private static ButtonBase okButton(DialogPane pane) {
        return (ButtonBase) pane.lookupButton(pane.getButtonTypes().get(0));
    }

    private static ButtonBase cancelButton(DialogPane pane) {
        return (ButtonBase) pane.lookupButton(pane.getButtonTypes().get(1));
    }

    private static void awaitFxDelay(Duration duration) throws Exception {
        CountDownLatch elapsed = new CountDownLatch(1);
        FxUiTestSupport.call(() -> {
            PauseTransition marker = new PauseTransition(duration);
            marker.setOnFinished(ignored -> elapsed.countDown());
            marker.play();
            return null;
        });
        assertTrue(elapsed.await(2, TimeUnit.SECONDS), "FX delay marker timed out");
        FxUiTestSupport.call(() -> null);
    }

    private static SqlResultToolbar toolbar(
            AtomicInteger searchRequests, AtomicInteger databaseRequests) {
        return new SqlResultToolbar(new SqlResultToolbar.Actions(
                ignored -> searchRequests.incrementAndGet(), () -> {}, ignored -> {},
                databaseRequests::incrementAndGet, () -> {}, ignored -> {}));
    }

    private static FilterCondition condition(
            int columnIndex, FilterConnector connector, FilterOperator operator, Object value) {
        return new FilterCondition(columnIndex, connector, operator, value);
    }

    private static ResultFilterState.Snapshot snapshot(
            ResultFilterState.DatabaseStatus status, String search,
            List<FilterCondition> conditions, int visibleRows,
            String unavailableReason, String error) {
        return snapshot(result(186), status, search, conditions,
                indexes(visibleRows), unavailableReason, error);
    }

    private static ResultFilterState.Snapshot snapshot(
            QueryResult result, ResultFilterState.DatabaseStatus status, String search,
            List<FilterCondition> conditions, List<Integer> visible,
            String unavailableReason, String error) {
        return new ResultFilterState.Snapshot(result, result, "select * from people",
                null, search, conditions, visible, status, unavailableReason, error);
    }

    private static List<Integer> indexes(int count) {
        List<Integer> visible = new ArrayList<>(count);
        for (int index = 0; index < count; index++) visible.add(index);
        return visible;
    }

    private static QueryResult result(int rowCount) {
        return result(rowCount, 37, false);
    }

    private static QueryResult result(int rowCount, long elapsedMillis, boolean truncated) {
        List<List<Object>> rows = new ArrayList<>(rowCount);
        for (int index = 0; index < rowCount; index++) {
            rows.add(List.of("name-" + index, index, "2026-08-29 10:00:00",
                    true, "note", (long) index));
        }
        return QueryResult.queryWithMetadata(COLUMNS, rows, elapsedMillis, truncated);
    }

    private static DialogPane showingDialogPane() {
        return Window.getWindows().stream()
                .filter(Window::isShowing)
                .map(Window::getScene)
                .filter(scene -> scene != null)
                .map(scene -> scene.getRoot())
                .filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("condition dialog is not showing"));
    }

    private static void closeShowingDialog() {
        Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(window -> window.getScene() != null)
                .map(window -> window.getScene().getRoot())
                .filter(DialogPane.class::isInstance)
                .map(Node::getScene)
                .map(scene -> scene.getWindow())
                .forEach(Window::hide);
    }
}
